package com.tvremote.app.protocol.remote

import com.google.protobuf.ByteString
import com.tvremote.app.protocol.crypto.CertificateManager
import com.tvremote.app.protocol.crypto.TlsSupport
import com.tvremote.app.proto.remote.RemoteAppLinkLaunchRequest
import com.tvremote.app.proto.remote.RemoteConfigure
import com.tvremote.app.proto.remote.RemoteDeviceInfo
import com.tvremote.app.proto.remote.RemoteDirection
import com.tvremote.app.proto.remote.RemoteKeyCode
import com.tvremote.app.proto.remote.RemoteKeyInject
import com.tvremote.app.proto.remote.RemoteMessage
import com.tvremote.app.proto.remote.RemotePingResponse
import com.tvremote.app.proto.remote.RemoteSetActive
import com.tvremote.app.proto.remote.RemoteVoiceBegin
import com.tvremote.app.proto.remote.RemoteVoiceEnd
import com.tvremote.app.proto.remote.RemoteVoicePayload
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import javax.net.ssl.SSLSocket

private const val REMOTE_PORT = 6466
private const val CONNECT_TIMEOUT_MS = 6_000

// The TV's Remote Service pings us every few seconds all on its own. Total
// silence for this long means the socket is dead even if TCP hasn't noticed
// yet (TV switched off, Wi-Fi flapped, router dropped the NAT entry...).
// Generous on purpose: the fast paths (the TV's FIN, or a failing write)
// already flip us to disconnected instantly, so this only has to catch the
// silent half-open case, and a short timer here would only risk dropping a
// perfectly good session on a TV that happens to ping less often.
private const val SILENCE_DEAD_MS = 45_000L
private const val WATCHDOG_TICK_MS = 3_000L

/** What the TV itself says its volume is right now. [known] is false until it tells us. */
data class TvVolume(
    val level: Int = 0,
    val max: Int = 0,
    val muted: Boolean = false,
    val known: Boolean = false
) {
    val percent: Int
        get() = if (known && max > 0) ((level * 100) + max / 2) / max else 0
}

/**
 * The live remote-control session: one persistent TLS socket to the TV's
 * already-running Remote Service, so every key press is just a few bytes on
 * an open connection instead of the 1-3 second lag an ADB `input keyevent`
 * has on slower boxes.
 *
 * Two things make this class trustworthy where the old one wasn't:
 *
 *  1. [connected] is a real StateFlow driven by the reader loop and by write
 *     failures. `Socket.isConnected()` NEVER goes back to false once a socket
 *     has connected — that single Java quirk is why the UI used to sit there
 *     with a green dot on a socket the TV had already hung up on.
 *  2. [volume] is whatever the TV last told us, not a number we guessed.
 */
class AndroidTvRemoteClient(private val certManager: CertificateManager) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleLock = Mutex()
    private val writeLock = Any()

    @Volatile private var socket: SSLSocket? = null
    private var readerJob: Job? = null
    private var watchdogJob: Job? = null

    @Volatile private var lastRxMs: Long = 0L

    // Bumped on every teardown. A reader/watchdog from an older generation
    // must never be allowed to tear down the socket a newer connect() just
    // opened, so every one of them checks its captured generation first.
    @Volatile private var generation: Int = 0

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _volume = MutableStateFlow(TvVolume())
    val volume: StateFlow<TvVolume> = _volume.asStateFlow()

    @Volatile private var voiceHandshake: CompletableDeferred<Int>? = null

    val isConnected: Boolean get() = _connected.value

    suspend fun connect(host: String): Result<Unit> = lifecycleLock.withLock {
        withContext(Dispatchers.IO) {
            teardown()
            val gen = generation
            try {
                val sslContext = TlsSupport.buildSslContext(certManager)
                val s = sslContext.socketFactory.createSocket() as SSLSocket
                // Explicit timeout: without it, connecting to a TV that's off
                // blocks for the OS default (~2 minutes) and the retry loop stalls.
                s.connect(InetSocketAddress(host, REMOTE_PORT), CONNECT_TIMEOUT_MS)
                s.tcpNoDelay = true
                s.keepAlive = true
                s.soTimeout = 0
                s.startHandshake()

                socket = s
                lastRxMs = System.currentTimeMillis()
                _volume.value = TvVolume()
                startReaderLoop(s, gen)
                startWatchdog(gen)
                _connected.value = true
                Result.success(Unit)
            } catch (t: Throwable) {
                teardown()
                Result.failure(t)
            }
        }
    }

    /** Deliberate shutdown: invalidates every background job via [generation]. */
    private fun teardown() {
        generation++
        readerJob?.cancel(); readerJob = null
        watchdogJob?.cancel(); watchdogJob = null
        voiceHandshake?.cancel(); voiceHandshake = null
        try { socket?.close() } catch (_: Throwable) {}
        socket = null
        _connected.value = false
    }

    /** The socket died under us. Only acts if this is still the current generation. */
    private fun markDropped(gen: Int) {
        if (gen != generation) return
        if (!_connected.value && socket == null) return
        try { socket?.close() } catch (_: Throwable) {}
        socket = null
        voiceHandshake?.cancel(); voiceHandshake = null
        _connected.value = false
    }

    fun close() {
        teardown()
    }

    private fun startReaderLoop(s: SSLSocket, gen: Int) {
        readerJob = scope.launch {
            try {
                while (isActive) {
                    val msg = RemoteMessage.parseDelimitedFrom(s.inputStream) ?: break
                    lastRxMs = System.currentTimeMillis()
                    handleIncoming(msg)
                }
            } catch (_: Throwable) {
                // Socket closed / reset / TLS torn down — handled below.
            } finally {
                markDropped(gen)
            }
        }
    }

    /**
     * Second line of defence against the "app thinks it's connected but isn't"
     * bug. A half-open TCP connection (TV unplugged, Wi-Fi bounced) never
     * delivers a FIN, so the reader loop above would block forever and the UI
     * would keep its green dot. The TV pings us on its own every few seconds,
     * so prolonged silence is a reliable death signal.
     */
    private fun startWatchdog(gen: Int) {
        watchdogJob = scope.launch {
            while (isActive) {
                delay(WATCHDOG_TICK_MS)
                if (gen != generation) return@launch
                if (System.currentTimeMillis() - lastRxMs > SILENCE_DEAD_MS) {
                    markDropped(gen)
                    return@launch
                }
            }
        }
    }

    private fun handleIncoming(msg: RemoteMessage) {
        when {
            msg.hasRemoteConfigure() -> send(
                RemoteMessage.newBuilder().setRemoteConfigure(
                    RemoteConfigure.newBuilder()
                        .setCode1(622)
                        .setDeviceInfo(
                            RemoteDeviceInfo.newBuilder()
                                .setModel("TvRemoteApp")
                                .setVendor("TvRemoteApp")
                                .setPackageName("com.tvremote.app")
                                .setAppVersion("1.0")
                                .build()
                        )
                        .build()
                ).build()
            )

            msg.hasRemoteSetActive() -> send(
                RemoteMessage.newBuilder().setRemoteSetActive(
                    RemoteSetActive.newBuilder().setActive(622).build()
                ).build()
            )

            msg.hasRemotePingRequest() -> send(
                RemoteMessage.newBuilder().setRemotePingResponse(
                    RemotePingResponse.newBuilder().setVal1(msg.remotePingRequest.val1).build()
                ).build()
            )

            // THE fix for the volume bug: the TV tells us its real level and
            // its real maximum. We never guess either one again.
            msg.hasRemoteSetVolumeLevel() -> {
                val v = msg.remoteSetVolumeLevel
                if (v.volumeMax > 0) {
                    _volume.value = TvVolume(
                        level = v.volumeLevel.coerceIn(0, v.volumeMax),
                        max = v.volumeMax,
                        muted = v.volumeMuted,
                        known = true
                    )
                }
            }

            // TV has accepted our SEARCH press and opened a voice session.
            msg.hasRemoteVoiceBegin() -> {
                val id = msg.remoteVoiceBegin.sessionId
                if (id != 0) voiceHandshake?.complete(id)
            }

            msg.hasRemoteError() -> {
                // The TV rejected something. Not fatal on its own — if it also
                // hangs up, the reader loop will notice.
            }
        }
    }

    /** Writes one message. Returns false — and marks the session dead — if the socket is gone. */
    private fun send(message: RemoteMessage): Boolean {
        val s = socket ?: return false
        val gen = generation
        return try {
            synchronized(writeLock) {
                message.writeDelimitedTo(s.outputStream)
                s.outputStream.flush()
            }
            true
        } catch (_: Throwable) {
            markDropped(gen)
            false
        }
    }

    /** Sends one real key press. Returns false if it could not be delivered. */
    suspend fun sendKey(
        keyCode: RemoteKeyCode,
        direction: RemoteDirection = RemoteDirection.SHORT
    ): Boolean = withContext(Dispatchers.IO) {
        send(
            RemoteMessage.newBuilder().setRemoteKeyInject(
                RemoteKeyInject.newBuilder()
                    .setKeyCode(keyCode)
                    .setDirection(direction)
                    .build()
            ).build()
        )
    }

    /**
     * Opens an app via its deep link / app link URL (e.g. "https://www.netflix.com/title").
     * Package names ("com.google.android.youtube.tv") are NOT accepted here — the TV answers
     * those with a remote_error and tears down the whole session, not just the request.
     */
    suspend fun launchAppLink(appLink: String): Boolean = withContext(Dispatchers.IO) {
        send(
            RemoteMessage.newBuilder().setRemoteAppLinkLaunchRequest(
                RemoteAppLinkLaunchRequest.newBuilder().setAppLink(appLink).build()
            ).build()
        )
    }

    // --- Voice search ------------------------------------------------------

    /**
     * Presses SEARCH and waits for the TV to hand back a voice session id.
     * Returns null if this TV's Remote Service won't open a voice session
     * (some manufacturer builds simply don't), so the caller can fall back.
     */
    suspend fun openVoiceSession(timeoutMs: Long = 2_500): Int? {
        val pending = CompletableDeferred<Int>()
        voiceHandshake = pending
        if (!sendKey(RemoteKeyCode.KEYCODE_SEARCH)) {
            voiceHandshake = null
            return null
        }
        val id = withTimeoutOrNull(timeoutMs) {
            try { pending.await() } catch (_: Throwable) { null }
        }
        voiceHandshake = null
        return id
    }

    suspend fun voiceStart(sessionId: Int): Boolean = withContext(Dispatchers.IO) {
        send(
            RemoteMessage.newBuilder().setRemoteVoiceBegin(
                RemoteVoiceBegin.newBuilder().setSessionId(sessionId).build()
            ).build()
        )
    }

    suspend fun voiceSamples(sessionId: Int, pcm: ByteArray, length: Int): Boolean =
        withContext(Dispatchers.IO) {
            send(
                RemoteMessage.newBuilder().setRemoteVoicePayload(
                    RemoteVoicePayload.newBuilder()
                        .setSessionId(sessionId)
                        .setSamples(ByteString.copyFrom(pcm, 0, length))
                        .build()
                ).build()
            )
        }

    suspend fun voiceEnd(sessionId: Int): Boolean = withContext(Dispatchers.IO) {
        send(
            RemoteMessage.newBuilder().setRemoteVoiceEnd(
                RemoteVoiceEnd.newBuilder().setSessionId(sessionId).build()
            ).build()
        )
    }
}

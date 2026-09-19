package com.tvremote.app

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tvremote.app.protocol.crypto.CertificateManager
import com.tvremote.app.protocol.discovery.DiscoveredTv
import com.tvremote.app.protocol.discovery.TvDiscovery
import com.tvremote.app.protocol.pairing.AndroidTvPairingClient
import com.tvremote.app.protocol.remote.AndroidTvRemoteClient
import com.tvremote.app.protocol.remote.VoiceCapture
import com.tvremote.app.proto.remote.RemoteKeyCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

enum class ConnectionState { DISCONNECTED, SCANNING, CONNECTING, NEEDS_PAIRING_CODE, PAIRING, CONNECTED, ERROR }

enum class VoiceState { IDLE, LISTENING, SENDING }

data class RemoteUiState(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val discoveredTvs: List<DiscoveredTv> = emptyList(),
    val selectedTvName: String = "New TV",
    val pickerOpen: Boolean = false,
    val online: Boolean = false,
    val powerOn: Boolean = true,
    val muted: Boolean = false,
    val cursorMode: Boolean = false,
    val volumePercent: Int = 0,
    val volumeKnown: Boolean = false,
    val voiceState: VoiceState = VoiceState.IDLE,
    val statusMessage: String? = null,
    val errorMessage: String? = null
)

private const val PREFS = "tvremote"
private const val KEY_HOST = "last_host"
private const val KEY_NAME = "last_name"

/** Hard cap on one voice utterance, so a stuck session can't hold the mic forever. */
private const val MAX_VOICE_MS = 9_000L

class RemoteViewModel(app: Application) : AndroidViewModel(app) {

    private val certManager = CertificateManager(app)
    private val remote = AndroidTvRemoteClient(certManager)
    private val pairingClient = AndroidTvPairingClient(certManager)
    private val discovery = TvDiscovery(app)
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Host we're currently trying to pair with (may not be usable yet). */
    private var pendingHost: String? = null

    /** Host that has successfully connected at least once — the reconnect target. */
    private var autoHost: String? = null

    private var codeDeferred: CompletableDeferred<String>? = null
    private var pairingJob: Job? = null
    private var reconnectJob: Job? = null
    private var voiceJob: Job? = null
    private var volumeJob: Job? = null

    @Volatile private var foreground: Boolean = true

    /**
     * True while any connect attempt is in flight. Without this the automatic
     * retry loop and a manual "pick this TV" can both call connect() at once,
     * and the second one tears down the socket the first just built.
     */
    @Volatile private var connectInFlight: Boolean = false

    /**
     * Where a slider drag is heading, in the TV's OWN volume units. Null when
     * no drag is in flight. Deliberately not a percentage: percentages are the
     * thing that got the old code into trouble.
     */
    @Volatile private var volumeTarget: Int? = null

    private val _uiState = MutableStateFlow(RemoteUiState())
    val uiState: StateFlow<RemoteUiState> = _uiState.asStateFlow()

    init {
        observeConnection()
        observeVolume()

        val savedHost = prefs.getString(KEY_HOST, null)
        val savedName = prefs.getString(KEY_NAME, null)
        if (savedHost != null) {
            autoHost = savedHost
            pendingHost = savedHost
            _uiState.update {
                it.copy(
                    selectedTvName = savedName ?: it.selectedTvName,
                    connectionState = ConnectionState.CONNECTING
                )
            }
            viewModelScope.launch {
                if (!doConnect(savedHost)) {
                    // Don't jump straight into a pairing dialog on cold start —
                    // the TV is most likely just asleep. Let the retry loop work.
                    _uiState.update { it.copy(connectionState = ConnectionState.DISCONNECTED) }
                    scheduleReconnect()
                }
            }
        }
    }

    private suspend fun doConnect(host: String): Boolean {
        if (connectInFlight) return remote.isConnected
        connectInFlight = true
        return try {
            remote.connect(host).isSuccess
        } finally {
            connectInFlight = false
        }
    }

    // --- Connection lifecycle ------------------------------------------------

    /**
     * Single source of truth for the green dot. The client flips this the
     * instant the socket dies, which is what stops the UI claiming "Connected"
     * over a session the TV hung up on ten minutes ago.
     */
    private fun observeConnection() {
        viewModelScope.launch {
            remote.connected.collect { isUp ->
                _uiState.update { s ->
                    val next = when {
                        isUp -> ConnectionState.CONNECTED
                        s.connectionState == ConnectionState.NEEDS_PAIRING_CODE ||
                            s.connectionState == ConnectionState.PAIRING ||
                            s.connectionState == ConnectionState.ERROR ||
                            s.connectionState == ConnectionState.SCANNING -> s.connectionState
                        autoHost != null -> ConnectionState.CONNECTING
                        else -> ConnectionState.DISCONNECTED
                    }
                    s.copy(
                        online = isUp,
                        connectionState = next,
                        volumeKnown = if (isUp) s.volumeKnown else false,
                        statusMessage = if (isUp) null else s.statusMessage
                    )
                }
                if (isUp) {
                    reconnectJob?.cancel()
                } else {
                    volumeTarget = null
                    scheduleReconnect()
                }
            }
        }
    }

    /**
     * Silent, automatic re-dial. Runs only while the app is in the foreground
     * so it can't drain the battery in the background, and re-runs mDNS every
     * few attempts in case DHCP handed the TV a different IP while it slept.
     */
    private fun scheduleReconnect() {
        val host = autoHost ?: return
        if (reconnectJob?.isActive == true) return
        if (isMidPairing() || connectInFlight) return
        reconnectJob = viewModelScope.launch {
            var backoff = 700L
            var attempts = 0
            while (isActive && foreground && !remote.isConnected) {
                delay(backoff)
                if (!foreground || remote.isConnected || isMidPairing() || connectInFlight) break
                attempts++

                // Every fourth try, re-run mDNS first: a TV that slept through
                // a DHCP lease renewal comes back on a different IP, and no
                // amount of retrying the old one will ever reach it.
                val target = if (attempts % 4 == 0) {
                    rediscoverHost() ?: autoHost ?: host
                } else {
                    autoHost ?: host
                }
                if (doConnect(target)) {
                    rememberTv(target, _uiState.value.selectedTvName)
                    break
                }
                backoff = (backoff * 2).coerceAtMost(8_000L)
            }
        }
    }

    /** DHCP moved the TV? Find it again by the name we stored. */
    private suspend fun rediscoverHost(): String? {
        val name = _uiState.value.selectedTvName
        val found = discovery.scan()
        if (found.isEmpty()) return null
        val match = found.firstOrNull { it.name == name } ?: found.firstOrNull() ?: return null
        autoHost = match.host
        pendingHost = match.host
        return match.host
    }

    private fun isMidPairing(): Boolean {
        val s = _uiState.value.connectionState
        return s == ConnectionState.NEEDS_PAIRING_CODE || s == ConnectionState.PAIRING
    }

    private fun rememberTv(host: String, name: String) {
        autoHost = host
        prefs.edit().putString(KEY_HOST, host).putString(KEY_NAME, name).apply()
    }

    fun onForeground() {
        foreground = true
        if (!remote.isConnected) scheduleReconnect()
    }

    fun onBackground() {
        foreground = false
        reconnectJob?.cancel()
        stopVoice()
    }

    // --- Discovery & pairing -------------------------------------------------

    fun togglePicker() {
        val opening = !_uiState.value.pickerOpen
        _uiState.update { it.copy(pickerOpen = opening) }
        if (opening) scanForTvs()
    }

    fun closePicker() {
        _uiState.update { it.copy(pickerOpen = false) }
    }

    private fun scanForTvs() {
        _uiState.update { it.copy(connectionState = ConnectionState.SCANNING) }
        viewModelScope.launch {
            val found = discovery.scan()
            _uiState.update {
                it.copy(
                    discoveredTvs = found,
                    connectionState = if (remote.isConnected) ConnectionState.CONNECTED
                    else if (autoHost != null) ConnectionState.CONNECTING
                    else ConnectionState.DISCONNECTED
                )
            }
        }
    }

    fun connectTo(tv: DiscoveredTv) {
        reconnectJob?.cancel()
        pendingHost = tv.host
        _uiState.update {
            it.copy(
                connectionState = ConnectionState.CONNECTING,
                selectedTvName = tv.name,
                pickerOpen = false,
                errorMessage = null
            )
        }
        viewModelScope.launch { attemptConnect(tv.host) }
    }

    private suspend fun attemptConnect(host: String) {
        if (doConnect(host)) {
            rememberTv(host, _uiState.value.selectedTvName)
            _uiState.update { it.copy(errorMessage = null) }
        } else {
            // Most likely cause: this TV has never seen our certificate before.
            startPairing(host)
        }
    }

    /**
     * Opens the pairing socket and runs the handshake ourselves. The TV only
     * puts the code on screen once we reach the PairingConfiguration step
     * inside [AndroidTvPairingClient.pair] — so we must already be mid-handshake,
     * with that same socket held open, before we ever ask the person to type
     * anything.
     */
    private fun startPairing(host: String) {
        pendingHost = host
        pairingJob?.cancel()
        pairingJob = viewModelScope.launch {
            val result = pairingClient.pair(host) {
                val deferred = CompletableDeferred<String>()
                codeDeferred = deferred
                _uiState.update {
                    it.copy(connectionState = ConnectionState.NEEDS_PAIRING_CODE, errorMessage = null)
                }
                deferred.await()
            }
            codeDeferred = null
            when (result) {
                is AndroidTvPairingClient.PairingResult.Success -> attemptConnect(host)
                is AndroidTvPairingClient.PairingResult.WrongCode -> _uiState.update {
                    it.copy(
                        connectionState = ConnectionState.ERROR,
                        errorMessage = "Code galat tha ya TV pe expire ho gaya. \"Try Again\" dabao — TV nayi screen dikhayega."
                    )
                }
                is AndroidTvPairingClient.PairingResult.Error -> _uiState.update {
                    it.copy(connectionState = ConnectionState.ERROR, errorMessage = result.message)
                }
            }
        }
    }

    fun submitPairingCode(code: String) {
        _uiState.update { it.copy(connectionState = ConnectionState.PAIRING) }
        codeDeferred?.complete(code)
    }

    fun retryPairing() {
        val host = pendingHost ?: autoHost ?: return
        _uiState.update { it.copy(connectionState = ConnectionState.CONNECTING, errorMessage = null) }
        viewModelScope.launch { attemptConnect(host) }
    }

    fun cancelPairing() {
        pairingJob?.cancel()
        codeDeferred = null
        _uiState.update {
            it.copy(
                connectionState = if (remote.isConnected) ConnectionState.CONNECTED else ConnectionState.DISCONNECTED,
                errorMessage = null
            )
        }
    }

    // --- Volume --------------------------------------------------------------

    /**
     * The TV broadcasts its real level/max on connect and after EVERY change,
     * including ones made with the plastic remote. Mirroring that is the whole
     * fix: no hardcoded step count, no guessed starting point, no drift.
     */
    private fun observeVolume() {
        viewModelScope.launch {
            remote.volume.collect { v ->
                if (!v.known) return@collect
                _uiState.update {
                    it.copy(
                        volumeKnown = true,
                        muted = v.muted,
                        // Mid-drag we show our own optimistic number so the handle
                        // tracks the finger; the TV's report takes over the moment
                        // the drag finishes.
                        volumePercent = if (volumeTarget != null) it.volumePercent else v.percent
                    )
                }
            }
        }
    }

    /** +/- buttons and the rocker: one real key press, no clamping, ever. */
    fun stepVolume(up: Boolean) = act {
        volumeTarget = null
        remote.sendKey(if (up) RemoteKeyCode.KEYCODE_VOLUME_UP else RemoteKeyCode.KEYCODE_VOLUME_DOWN)
    }

    /**
     * Slider drag. Converts the percentage into the TV's own units using the
     * max the TV reported, then walks there one key at a time. A single worker
     * services the whole drag — a fresh coroutine per drag event is what would
     * turn a flick of the thumb into a hundred interleaved key presses.
     */
    fun setVolume(percent: Int) {
        val v = remote.volume.value
        if (!v.known || v.max <= 0) return
        val target = ((percent.coerceIn(0, 100) * v.max) + 50) / 100
        volumeTarget = target
        _uiState.update { it.copy(volumePercent = percent.coerceIn(0, 100)) }
        if (volumeJob?.isActive == true) return

        volumeJob = viewModelScope.launch {
            var cursor = remote.volume.value.level
            var idleTicks = 0
            while (isActive) {
                val want = volumeTarget ?: break
                val max = remote.volume.value.max.takeIf { it > 0 } ?: break
                if (cursor == want) {
                    // The finger may still be moving — wait a moment before giving up.
                    if (++idleTicks > 6) break
                    delay(50)
                    continue
                }
                idleTicks = 0
                if (!remote.isConnected && !ensureConnected()) break
                val up = want > cursor
                if (!remote.sendKey(
                        if (up) RemoteKeyCode.KEYCODE_VOLUME_UP else RemoteKeyCode.KEYCODE_VOLUME_DOWN
                    )
                ) break
                cursor = (cursor + (if (up) 1 else -1)).coerceIn(0, max)
                _uiState.update { it.copy(volumePercent = ((cursor * 100) + max / 2) / max) }
                delay(35)
            }
            volumeTarget = null
            // Snap back to whatever the TV actually ended up at.
            val settled = remote.volume.value
            if (settled.known) _uiState.update { it.copy(volumePercent = settled.percent) }
        }
    }

    // --- Voice ---------------------------------------------------------------

    /**
     * Real voice search: we press SEARCH, the TV opens a voice session and
     * hands back a session id, and then we stream the phone's microphone to
     * it as raw PCM. Pressing an "assistant" key alone — which is all the old
     * code did — opens the Assistant on the TV but sends it nothing to hear,
     * which is exactly why it never responded.
     */
    fun startVoice() {
        if (voiceJob?.isActive == true) return
        voiceJob = viewModelScope.launch {
            if (!remote.isConnected && !ensureConnected()) {
                flashStatus("TV se connection nahi hai.")
                return@launch
            }

            _uiState.update { it.copy(voiceState = VoiceState.LISTENING, statusMessage = "Suno raha hoon\u2026") }

            val sessionId = remote.openVoiceSession()
            if (sessionId == null) {
                // This TV's Remote Service won't take streamed audio. Open the
                // Assistant on the TV so its own far-field mic can take over.
                remote.sendKey(RemoteKeyCode.KEYCODE_ASSIST)
                _uiState.update { it.copy(voiceState = VoiceState.IDLE) }
                flashStatus("Ye TV app se voice nahi leta \u2014 TV pe Assistant khol diya.")
                return@launch
            }

            val capture = withContext(Dispatchers.IO) { VoiceCapture.open() }
            if (capture == null) {
                _uiState.update { it.copy(voiceState = VoiceState.IDLE) }
                remote.voiceEnd(sessionId)
                flashStatus("Mic nahi khul paaya.")
                return@launch
            }

            _uiState.update { it.copy(voiceState = VoiceState.SENDING) }
            try {
                withContext(Dispatchers.IO) {
                    capture.start()
                    remote.voiceStart(sessionId)
                    val deadline = System.currentTimeMillis() + MAX_VOICE_MS
                    while (isActive && System.currentTimeMillis() < deadline && remote.isConnected) {
                        val chunk = capture.read() ?: break
                        if (!remote.voiceSamples(sessionId, chunk.first, chunk.second)) break
                    }
                }
            } catch (_: Throwable) {
                // Fall through — cleanup below runs either way.
            } finally {
                // NonCancellable matters here: tapping the mic again cancels this
                // job, and a plain withContext in a finally block would abort
                // instantly and leave the microphone held open by our process.
                withContext(NonCancellable + Dispatchers.IO) {
                    capture.stop()
                    remote.voiceEnd(sessionId)
                }
                _uiState.update { it.copy(voiceState = VoiceState.IDLE, statusMessage = null) }
            }
        }
    }

    fun stopVoice() {
        voiceJob?.cancel()
        voiceJob = null
        if (_uiState.value.voiceState != VoiceState.IDLE) {
            _uiState.update { it.copy(voiceState = VoiceState.IDLE, statusMessage = null) }
        }
    }

    fun onMicPermissionDenied() {
        flashStatus("Mic permission ke bina voice search nahi chalega.")
    }

    private fun flashStatus(message: String) {
        _uiState.update { it.copy(statusMessage = message) }
        viewModelScope.launch {
            delay(4_000)
            _uiState.update { if (it.statusMessage == message) it.copy(statusMessage = null) else it }
        }
    }

    // --- Plain keys ----------------------------------------------------------

    fun togglePower() = act {
        remote.sendKey(RemoteKeyCode.KEYCODE_POWER)
        _uiState.update { it.copy(powerOn = !it.powerOn) }
    }

    fun toggleMute() = act { remote.sendKey(RemoteKeyCode.KEYCODE_VOLUME_MUTE) }

    fun toggleCursorMode() {
        _uiState.update { it.copy(cursorMode = !it.cursorMode) }
    }

    fun back() = act { remote.sendKey(RemoteKeyCode.KEYCODE_BACK) }
    fun home() = act { remote.sendKey(RemoteKeyCode.KEYCODE_HOME) }
    fun recentApps() = act { remote.sendKey(RemoteKeyCode.KEYCODE_APP_SWITCH) }
    fun assistant() = act { remote.sendKey(RemoteKeyCode.KEYCODE_ASSIST) }
    fun menu() = act { remote.sendKey(RemoteKeyCode.KEYCODE_MENU) }
    fun openSettings() = act { remote.sendKey(RemoteKeyCode.KEYCODE_SETTINGS) }
    fun openKeyboard() = act { remote.sendKey(RemoteKeyCode.KEYCODE_DPAD_CENTER) }

    fun dpadTap() = act { remote.sendKey(RemoteKeyCode.KEYCODE_DPAD_CENTER) }
    fun dpadDirection(dx: Int, dy: Int) = act {
        val key = when {
            kotlin.math.abs(dx) > kotlin.math.abs(dy) && dx > 0 -> RemoteKeyCode.KEYCODE_DPAD_RIGHT
            kotlin.math.abs(dx) > kotlin.math.abs(dy) && dx < 0 -> RemoteKeyCode.KEYCODE_DPAD_LEFT
            dy > 0 -> RemoteKeyCode.KEYCODE_DPAD_DOWN
            dy < 0 -> RemoteKeyCode.KEYCODE_DPAD_UP
            else -> null
        }
        key?.let { remote.sendKey(it) }
    }

    // Deep links, not package names: sending a bare package name
    // (e.g. "com.google.android.youtube.tv") makes the TV answer with a
    // remote_error and then drop the whole session, not just the request —
    // a documented quirk of the real Remote Service, not a guess.
    fun launchYoutube() = act { remote.launchAppLink("https://www.youtube.com/tv") }
    fun launchNetflix() = act { remote.launchAppLink("https://www.netflix.com/title") }
    fun launchPrime() = act { remote.launchAppLink("https://app.primevideo.com") }

    // --- Plumbing ------------------------------------------------------------

    /**
     * Reconnects on demand instead of silently swallowing the key press. This
     * is the other half of the "remote keeps falling asleep" fix: the user
     * shouldn't have to re-pick the TV from the list to wake the app up.
     */
    private suspend fun ensureConnected(): Boolean {
        if (remote.isConnected) return true
        if (isMidPairing()) return false
        val host = autoHost ?: pendingHost ?: return false
        val ok = doConnect(host)
        if (ok) rememberTv(host, _uiState.value.selectedTvName) else scheduleReconnect()
        return ok
    }

    private fun act(block: suspend () -> Unit) {
        viewModelScope.launch {
            if (!remote.isConnected && !ensureConnected()) return@launch
            block()
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopVoice()
        remote.close()
    }
}

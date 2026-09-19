package com.tvremote.app.protocol.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import kotlin.coroutines.resume

data class DiscoveredTv(val name: String, val host: String)

private const val SERVICE_TYPE = "_androidtvremote2._tcp."
private const val SCAN_WINDOW_MS = 4000L

/**
 * Every Android TV / Google TV device with the Remote Service enabled
 * (the default on virtually all of them) advertises itself over mDNS as
 * "_androidtvremote2._tcp." — this is the exact same service the official
 * Google Home app looks for. No ADB, no developer mode, nothing to enable
 * on the TV.
 */
class TvDiscovery(context: Context) {

    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    // Android drops multicast (mDNS) packets by default to save battery; this
    // lock is what lets the phone actually receive the TV's mDNS announcement.
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val multicastLock = wifiManager?.createMulticastLock("tvremote-mdns")

    suspend fun scan(): List<DiscoveredTv> {
        val found = mutableListOf<DiscoveredTv>()
        try { multicastLock?.setReferenceCounted(true); multicastLock?.acquire() } catch (_: Throwable) {}
        withTimeoutOrNull(SCAN_WINDOW_MS) {
            suspendCancellableCoroutine<Unit> { cont ->
                val listener = object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(serviceType: String?) = Unit
                    override fun onDiscoveryStopped(serviceType: String?) {
                        if (cont.isActive) cont.resume(Unit)
                    }
                    override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                        if (cont.isActive) cont.resume(Unit)
                    }
                    override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) = Unit
                    override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                        resolve(serviceInfo) { tv -> found.add(tv) }
                    }
                    override fun onServiceLost(serviceInfo: NsdServiceInfo?) = Unit
                }

                cont.invokeOnCancellation {
                    try { nsdManager.stopServiceDiscovery(listener) } catch (_: Throwable) {}
                }

                try {
                    nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
                } catch (_: Throwable) {
                    if (cont.isActive) cont.resume(Unit)
                    return@suspendCancellableCoroutine
                }

                // Let it run for the scan window, then stop it ourselves.
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    try { nsdManager.stopServiceDiscovery(listener) } catch (_: Throwable) {}
                }, SCAN_WINDOW_MS - 200)
            }
        }
        try { if (multicastLock?.isHeld == true) multicastLock.release() } catch (_: Throwable) {}
        return found.distinctBy { it.host }
    }

    @Suppress("DEPRECATION")
    private fun resolve(serviceInfo: NsdServiceInfo, onResolved: (DiscoveredTv) -> Unit) {
        nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) = Unit
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val host: InetAddress? = serviceInfo.host
                if (host != null) {
                    onResolved(DiscoveredTv(name = serviceInfo.serviceName, host = host.hostAddress ?: return))
                }
            }
        })
    }
}

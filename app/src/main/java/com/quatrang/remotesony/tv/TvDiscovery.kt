package com.quatrang.remotesony.tv

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class FoundTv(val name: String, val host: String)

/** Dò Android TV / Google TV trong mạng Wi-Fi (dịch vụ _androidtvremote2._tcp). */
class TvDiscovery(context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var lock: WifiManager.MulticastLock? = null
    private var listener: NsdManager.DiscoveryListener? = null

    private val _found = MutableStateFlow<List<FoundTv>>(emptyList())
    val found: StateFlow<List<FoundTv>> = _found

    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    fun start() {
        stop()
        _found.value = emptyList()
        lock = wifi.createMulticastLock("remotesony").apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
        val l = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.w(TAG, "start failed $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String?) {}
            override fun onDiscoveryStopped(serviceType: String?) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                synchronized(queue) { queue.addLast(info) }
                resolveNext()
            }
            override fun onServiceLost(info: NsdServiceInfo) {}
        }
        listener = l
        runCatching { nsd.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, l) }
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        val next = synchronized(queue) {
            if (resolving || queue.isEmpty()) null
            else {
                resolving = true
                queue.removeFirst()
            }
        } ?: return
        val cb = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo?, errorCode: Int) {
                synchronized(queue) { resolving = false }
                resolveNext()
            }

            override fun onServiceResolved(info: NsdServiceInfo) {
                val host = info.host?.hostAddress
                if (host != null) {
                    val tv = FoundTv(info.serviceName ?: host, host)
                    _found.value = (_found.value.filter { it.host != host } + tv).sortedBy { it.name }
                }
                synchronized(queue) { resolving = false }
                resolveNext()
            }
        }
        runCatching { nsd.resolveService(next, cb) }.onFailure {
            synchronized(queue) { resolving = false }
        }
    }

    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        runCatching { lock?.release() }
        lock = null
        synchronized(queue) {
            queue.clear()
            resolving = false
        }
    }

    companion object {
        private const val TAG = "TvDiscovery"
        private const val SERVICE = "_androidtvremote2._tcp."
    }
}

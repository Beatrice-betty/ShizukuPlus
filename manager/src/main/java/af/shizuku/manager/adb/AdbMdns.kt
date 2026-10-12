package af.shizuku.manager.adb

import af.shizuku.manager.ShizukuSettings
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.lifecycle.Observer
import kotlinx.coroutines.*
import timber.log.Timber
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket

@RequiresApi(Build.VERSION_CODES.R)
class AdbMdns(
    context: Context,
    private val serviceType: String,
    private val observer: Observer<Int>,
) {
    private var registered = false
    private var running = false
    private var serviceName: String? = null

    /**
     * Host the discovered adb service resolved to, for consumers to connect to. Normalized so
     * loopback stays "127.0.0.1" (byte-identical to the previous hard-coded behavior for the common
     * on-device case); only a non-loopback *local-interface* address — as can happen under Android
     * 16+ Local Network Protection, where the adb daemon may advertise a real interface rather than
     * loopback — is surfaced, so pairing connects to the actual host instead of failing on 127.0.0.1.
     */
    @Volatile
    var resolvedHost: String = "127.0.0.1"
        private set
    private val listener = DiscoveryListener(this)
    private val nsdManager: NsdManager = context.getSystemService(NsdManager::class.java)
    private val mdnsScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var restartJob: Job? = null
    private var restartScheduled = false
    private var attempts = 0
    private val resolveQueue = java.util.ArrayDeque<NsdServiceInfo>()
    private var isResolving = false

    fun start() {
        if (running) return
        running = true
        if (!registered) {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        }
    }

    fun stop() {
        if (!running) return
        running = false
        restartJob?.cancel()
        mdnsScope.cancel()
        synchronized(resolveQueue) {
            resolveQueue.clear()
            isResolving = false
        }
        if (registered) {
            try {
                nsdManager.stopServiceDiscovery(listener)
            } catch (e: IllegalArgumentException) {
                Timber.tag(TAG).e(e, "listener not registered when stopping service discovery")
            }
            registered = false
        }
    }

    private fun onDiscoveryStart() {
        registered = true
    }

    private fun onDiscoveryStop() {
        registered = false
    }

    private fun resolveNext() {
        synchronized(resolveQueue) {
            if (!running || isResolving || resolveQueue.isEmpty()) return
            val next = resolveQueue.poll() ?: return
            isResolving = true
            try {
                nsdManager.resolveService(next, ResolveListener(this))
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "resolveService failed to launch for ${next.serviceName}")
                isResolving = false
                resolveNext()
            }
        }
    }

    internal fun onResolveFinished() {
        synchronized(resolveQueue) {
            isResolving = false
            resolveNext()
        }
    }

    internal fun requeueResolve(info: NsdServiceInfo) {
        mdnsScope.launch {
            delay(150L)
            if (running) {
                synchronized(resolveQueue) {
                    resolveQueue.add(info)
                    resolveNext()
                }
            }
        }
    }

    private fun onServiceFound(info: NsdServiceInfo) {
        synchronized(resolveQueue) {
            resolveQueue.add(info)
            resolveNext()
        }
    }

    private fun onServiceLost(info: NsdServiceInfo) {
        if (info.serviceName == serviceName) observer.onChanged(-1)
    }

    private fun onServiceResolved(resolvedService: NsdServiceInfo) {
        val rawHost = resolvedService.host
        val hostAddress = rawHost?.hostAddress
        val cleanHostAddress = hostAddress?.substringBefore('%')
        val isLocal = cleanHostAddress == "127.0.0.1" || cleanHostAddress == "::1" || rawHost?.isLoopbackAddress == true

        val isKnownInterface =
            isLocal ||
                runCatching {
                    NetworkInterface
                        .getNetworkInterfaces()
                        ?.asSequence()
                        ?.any { networkInterface ->
                            networkInterface.inetAddresses
                                .asSequence()
                                .any { addr ->
                                    addr == rawHost || cleanHostAddress == addr.hostAddress?.substringBefore('%')
                                }
                        } == true
                }.getOrDefault(false)

        if (running &&
            isKnownInterface &&
            isPortAvailable(rawHost, resolvedService.port)
        ) {
            serviceName = resolvedService.serviceName
            resolvedHost = if (isLocal || cleanHostAddress == null) "127.0.0.1" else cleanHostAddress
            observer.onChanged(resolvedService.port)
        } else if (running && ShizukuSettings.isAutoReconnectMdnsEnabled() && attempts < 5 && !restartScheduled) {
            attempts++
            restartScheduled = true
            val delayMs = attempts * 1000L
            restartJob =
                mdnsScope.launch {
                    delay(delayMs)
                    if (registered) {
                        try {
                            nsdManager.stopServiceDiscovery(listener)
                        } catch (e: IllegalArgumentException) {
                            Timber.tag(TAG).e(e, "listener not registered when restarting service discovery")
                        }
                    }
                    delay(100L)
                    if (!registered) nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
                    restartScheduled = false
                }
        }
    }

    /**
     * Checks if the ADB service port is actively in use / responding on this device (issue #559).
     *
     * In upstream Shizuku, this only tested binding to `127.0.0.1:port`. On modern Android (15/16)
     * and OEM ROMs (e.g., Honor MagicOS, Xiaomi HyperOS, OnePlus OxygenOS), wireless debugging
     * binds directly to the network interface IP (e.g. wlan0) rather than loopback. A loopback bind
     * succeeds in that case, falsely reporting the port as "not in use" and getting stuck searching forever.
     */
    private fun isPortAvailable(
        host: InetAddress?,
        port: Int,
    ): Boolean {
        // Step 1: If binding to 127.0.0.1 or ::1 throws IOException (EADDRINUSE), adbd is bound on loopback or 0.0.0.0 / ::
        try {
            ServerSocket().use {
                it.bind(InetSocketAddress("127.0.0.1", port), 1)
            }
        } catch (_: IOException) {
            return true
        }

        try {
            ServerSocket().use {
                it.bind(InetSocketAddress("::1", port), 1)
            }
        } catch (_: IOException) {
            return true
        }

        // Step 2: adbd bound to the resolved interface IP only (e.g. wlan0) — the loopback bind
        // above succeeds in that case, so also try binding the exact address mDNS resolved to.
        if (host != null && !host.isLoopbackAddress) {
            try {
                ServerSocket().use {
                    it.bind(InetSocketAddress(host, port), 1)
                }
            } catch (_: IOException) {
                return true
            }
        }

        // Step 3: Check all active network interfaces
        val boundOnAnyInterface =
            runCatching {
                val ifaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching false
                for (iface in ifaces) {
                    if (!iface.isUp || iface.isLoopback) continue
                    for (addr in iface.inetAddresses) {
                        if (addr == host) continue
                        try {
                            ServerSocket().use {
                                it.bind(InetSocketAddress(addr, port), 1)
                            }
                        } catch (_: IOException) {
                            return@runCatching true
                        }
                    }
                }
                false
            }.getOrDefault(false)

        if (boundOnAnyInterface) return true

        // For adb connect (not pairing), a non-blocking TCP socket probe is also safe
        if (serviceType == TLS_CONNECT) {
            val targetHost = host?.hostAddress?.substringBefore('%') ?: "127.0.0.1"
            if (AdbPortProber.isPortOpen(port, 150, targetHost)) {
                return true
            }
        }

        return false
    }

    internal class DiscoveryListener(
        private val adbMdns: AdbMdns,
    ) : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            Timber.tag(TAG).v("onDiscoveryStarted: $serviceType")

            adbMdns.onDiscoveryStart()
        }

        override fun onStartDiscoveryFailed(
            serviceType: String,
            errorCode: Int,
        ) {
            Timber.tag(TAG).v("onStartDiscoveryFailed: $serviceType, $errorCode")
        }

        override fun onDiscoveryStopped(serviceType: String) {
            Timber.tag(TAG).v("onDiscoveryStopped: $serviceType")

            adbMdns.onDiscoveryStop()
        }

        override fun onStopDiscoveryFailed(
            serviceType: String,
            errorCode: Int,
        ) {
            Timber.tag(TAG).v("onStopDiscoveryFailed: $serviceType, $errorCode")
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            Timber.tag(TAG).v("onServiceFound: ${serviceInfo.serviceName}")

            adbMdns.onServiceFound(serviceInfo)
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            Timber.tag(TAG).v("onServiceLost: ${serviceInfo.serviceName}")

            adbMdns.onServiceLost(serviceInfo)
        }
    }

    internal class ResolveListener(
        private val adbMdns: AdbMdns,
    ) : NsdManager.ResolveListener {
        override fun onResolveFailed(
            nsdServiceInfo: NsdServiceInfo,
            i: Int,
        ) {
            Timber.tag(TAG).w("onResolveFailed: ${nsdServiceInfo.serviceName}, code $i")
            adbMdns.onResolveFinished()
            if (i == NsdManager.FAILURE_ALREADY_ACTIVE) {
                adbMdns.requeueResolve(nsdServiceInfo)
            }
        }

        override fun onServiceResolved(nsdServiceInfo: NsdServiceInfo) {
            adbMdns.onResolveFinished()
            adbMdns.onServiceResolved(nsdServiceInfo)
        }
    }

    companion object {
        const val TLS_CONNECT = "_adb-tls-connect._tcp"
        const val TLS_PAIRING = "_adb-tls-pairing._tcp"
        const val TAG = "AdbMdns"
    }
}

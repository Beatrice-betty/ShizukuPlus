package af.shizuku.manager.adb

import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.utils.EnvironmentUtils
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

object AdbPortProber {
    /**
     * Resolves all candidate network host addresses to attempt when connecting to ADB or pairing:
     * 1. [preferredHost] (e.g. from mDNS resolution or intent extra)
     * 2. Loopback addresses: 127.0.0.1 and ::1
     * 3. Local network interface addresses (e.g. wlan0)
     */
    fun getCandidateHosts(preferredHost: String = "127.0.0.1"): List<String> {
        val hosts = LinkedHashSet<String>()
        val cleanPreferred = preferredHost.substringBefore('%').trim()
        if (cleanPreferred.isNotBlank()) {
            hosts.add(cleanPreferred)
        }
        hosts.add("127.0.0.1")
        hosts.add("::1")
        runCatching {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return@runCatching
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses) {
                    val raw = addr.hostAddress ?: continue
                    val clean = raw.substringBefore('%')
                    if (clean.isNotBlank()) {
                        hosts.add(clean)
                    }
                }
            }
        }
        return hosts.toList()
    }

    /**
     * Rapidly probes whether a given port is actively accepting TCP connections on [host].
     * Uses a short timeout (150ms default) to ensure UI responsiveness.
     * When host is "127.0.0.1", also tries "::1" (IPv6 loopback) if IPv4 connection is refused.
     */
    fun isPortOpen(
        port: Int,
        timeoutMs: Int = 150,
        host: String = "127.0.0.1",
    ): Boolean {
        if (port !in 1..65535) return false
        val cleanHost = host.substringBefore('%')
        if (probeAddress(cleanHost, port, timeoutMs)) return true
        if (cleanHost == "127.0.0.1") {
            return probeAddress("::1", port, timeoutMs)
        }
        return false
    }

    private fun probeAddress(
        host: String,
        port: Int,
        timeoutMs: Int,
    ): Boolean =
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (_: IOException) {
            false
        } catch (_: Exception) {
            false
        }

    /**
     * Checks candidate ports on loopback (127.0.0.1 / ::1) in order of likelihood:
     * 1. EnvironmentUtils.getAdbTcpPort() (Active system-reported port from service.adb.tcp.port)
     * 2. 5555 (Standard ADB TCP/IP port - works offline / over 5G)
     * 3. ShizukuSettings.getTcpPort() (Configured custom TCP port)
     * 4. ShizukuSettings.getLastPort() (Previously successful session port)
     *
     * Probes all candidates in parallel for minimal latency and returns the highest-priority
     * open port, or -1 if none.
     */
    suspend fun findActiveLoopbackPort(context: Context? = null): Int =
        withContext(Dispatchers.IO) {
            val candidates = LinkedHashSet<Int>()

            val sysPropPort = EnvironmentUtils.getAdbTcpPort()
            if (sysPropPort in 1..65535) {
                candidates.add(sysPropPort)
            }
            candidates.add(5555)
            val tcpPort = ShizukuSettings.getTcpPort()
            if (tcpPort in 1..65535) {
                candidates.add(tcpPort)
            }
            val lastPort = ShizukuSettings.getLastPort()
            if (lastPort in 1..65535) {
                candidates.add(lastPort)
            }

            coroutineScope {
                candidates
                    .map { port ->
                        async { port to isPortOpen(port, 150) }
                    }.awaitAll()
            }.firstOrNull { it.second }?.first ?: -1
        }
}

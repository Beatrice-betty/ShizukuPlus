package af.shizuku.manager.adb

import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.database.ActivityLogManager
import af.shizuku.manager.utils.EnvironmentUtils
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Decides which loopback ADB ports are worth trying when (re)connecting (startup, watchdog).
 *
 * adbd may already be listening somewhere other than the configured port - after the user changed
 * or randomized it, or when the system reports a port we never saved - and connecting there is
 * enough: [AdbStarter.startAdb] then moves it to the configured port itself.
 */
object AdbConnectionPlanner {
    private const val TAG = "AdbConnectionPlanner"
    private const val GRACE_POLL_INTERVAL_MS = 500L

    /** Ports to try, most likely first, without duplicates. */
    fun candidatePorts(): List<Int> {
        val ports = LinkedHashSet<Int>()
        ports += EnvironmentUtils.getAdbTcpPort() // what adbd itself reports
        ports += ShizukuSettings.getTcpPort() // what the user configured
        ports += ShizukuSettings.getLastPort() // what worked last time
        ports += 5555 // the conventional default
        return ports.filter { it in 1..65535 }
    }

    /** True when adbd is configured to come back on TCP by itself after a reboot. */
    fun adbdRestoresTcpOnBoot(): Boolean =
        runCatching {
            android.os.SystemProperties
                .get("persist.adb.tcp.port", "")
                .toIntOrNull() ?: -1
        }.getOrDefault(-1) > 0

    /**
     * Probes all candidates in parallel and returns the highest-priority open one, or -1.
     * With [graceMs] > 0 keeps re-probing until then, for boots where adbd opens its port a few
     * seconds after we run.
     */
    suspend fun findOpenPort(
        timeoutMs: Int = 400,
        graceMs: Long = 0,
    ): Int =
        withContext(Dispatchers.IO) {
            var waited = 0L
            while (true) {
                val ports = candidatePorts()
                val open =
                    coroutineScope {
                        ports.map { p -> async { p to AdbPortProber.isPortOpen(p, timeoutMs) } }.awaitAll()
                    }
                open.firstOrNull { it.second }?.let { return@withContext it.first }
                if (waited >= graceMs) return@withContext -1
                delay(GRACE_POLL_INTERVAL_MS)
                waited += GRACE_POLL_INTERVAL_MS + timeoutMs
            }
            @Suppress("UNREACHABLE_CODE")
            -1
        }

    /** Records how a start was reached; makes "why did it connect that way" answerable from the log. */
    fun recordMethod(
        context: Context,
        method: String,
        port: Int,
    ) {
        Timber.tag(TAG).i("Connecting via %s on port %d", method, port)
        runCatching { ActivityLogManager.log("Shizuku", context.packageName, "Connecting via $method on port $port") }
    }
}

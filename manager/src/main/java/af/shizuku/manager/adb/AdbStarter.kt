package af.shizuku.manager.adb
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.database.ActivityLogManager
import af.shizuku.manager.starter.Starter
import af.shizuku.manager.utils.EnvironmentUtils
import af.shizuku.manager.utils.SettingsPage
import af.shizuku.manager.utils.ShizukuStateMachine
import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.sentry.Sentry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.EOFException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException

object AdbStarter {
    private const val TAG = "AdbStarter"

    // True while a connection is holding its socket open waiting for the user to tap
    // "Allow USB debugging?". Read by AdbStartWorker.enqueue() to switch from REPLACE
    // to KEEP so the waiting worker is not cancelled and a second dialog is not queued.
    @Volatile var keyOfferInFlight: Boolean = false

    private fun Context.getActivity(): Activity? {
        var context = this
        while (context is ContextWrapper) {
            if (context is Activity) return context
            context = context.baseContext
        }
        return null
    }

    /** Returns true for transient connection errors that are not bugs and should not go to Sentry. */
    private fun Throwable.isExpectedAdbError(includeIllegalState: Boolean = false) =
        this is EOFException ||
            this is SocketException ||
            this is SocketTimeoutException ||
            this is ConnectException ||
            this is SSLException ||
            this is AdbKeyException ||
            (includeIllegalState && this is IllegalStateException)

    suspend fun startAdb(
        context: Context,
        port: Int,
        host: String = "127.0.0.1",
        log: ((String) -> Unit)? = null,
        activityLogMessage: String? = null,
    ) {
        if (port !in 1..65535) {
            Timber.tag(TAG).w("startAdb called with invalid port $port — skipping")
            return
        }

        suspend fun AdbClient.runCommand(cmd: String) {
            command(cmd) { log?.invoke(String(it)) }
        }

        try {
            ShizukuStateMachine.set(ShizukuStateMachine.State.STARTING)
            Timber.tag(TAG).i("startAdb: initiating connection on %s:%d", host, port)
            log?.invoke("Starting with wireless adb...\n")

            withContext(Dispatchers.IO) {
                val key =
                    runCatching { AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku+") }
                        .getOrElse {
                            if (it is CancellationException) {
                                throw it
                            } else {
                                throw AdbKeyException(it)
                            }
                        }

                var activePort = port
                var activeHost = host
                // An untrusted network (#518) must not get a TCP port opened as a side effect of a
                // Wireless Debugging start, or the protection is undone one step later.
                val tcpMode =
                    ShizukuSettings.getTcpMode() &&
                        !af.shizuku.manager.utils.NetworkTrust
                            .shouldBlockTcp(context)
                val tcpPort = ShizukuSettings.getTcpPort()
                if (tcpMode && activePort != tcpPort) {
                    if (tcpPort !in 1..65535) {
                        Timber.tag(TAG).w("TCP mode enabled but stored TCP port is invalid ($tcpPort) — skipping TCP redirect")
                    } else {
                        Timber.tag(TAG).d("Switching ADB from port %d to TCP port %d", activePort, tcpPort)
                        log?.invoke("Connecting on port $activePort...")

                        connectAdaptive(activeHost, activePort, key) { client, connectedHost ->
                            log?.invoke("Successfully connected on port $activePort ($connectedHost)...")
                            log?.invoke("\nRestarting in TCP mode port: $tcpPort")

                            activePort = tcpPort
                            activeHost = "127.0.0.1"
                            runCatching {
                                client.command("tcpip:$activePort")
                            }.onFailure { if (it !is EOFException && it !is SocketException) throw it } // Expected when ADB restarts in TCP mode
                        }
                    }
                }

                Timber.tag(TAG).i("Connecting to ADB daemon at %s:%d", activeHost, activePort)
                log?.invoke("Connecting on port $activePort...")

                connectAdaptive(activeHost, activePort, key) { client, connectedHost ->
                    Timber.tag(TAG).i("Connected to ADB at %s:%d; deploying starter command", connectedHost, activePort)
                    log?.invoke("Successfully connected on port $activePort...\n")
                    client.runCommand("shell:${Starter.internalCommand}")
                    runCatching {
                        client.runCommand("shell:cmd appops set ${context.packageName} ACCESS_RESTRICTED_SETTINGS allow")
                        client.runCommand("shell:pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS")
                    }.onFailure { Timber.tag(TAG).w(it, "Failed to auto-elevate privileges on ADB start") }
                    ShizukuSettings.setLastPort(activePort)
                    af.shizuku.manager.utils.UserStopFlag
                        .clear()
                    val msg = activityLogMessage ?: "Service started via ADB on port $activePort"
                    ActivityLogManager.log("Shizuku", context.packageName, msg)
                    ShizukuStateMachine.update()
                    Timber.tag(TAG).i("Shizuku service started successfully via ADB on %s:%d", connectedHost, activePort)
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "startAdb failed on port %d: %s", port, e.message)
            if (e !is CancellationException) {
                runCatching {
                    ActivityLogManager.log("Shizuku", context.packageName, "Connection: ADB start failed on port $port (${e.javaClass.simpleName}: ${e.message?.take(80)})")
                }
            }
            if (e is SSLException && (e.message?.contains("protocol version") == true || e is javax.net.ssl.SSLProtocolException)) {
                withContext(Dispatchers.Main) {
                    val activity = context.getActivity()
                    if (activity != null && !activity.isFinishing) {
                        MaterialAlertDialogBuilder(activity)
                            .setTitle(R.string.adb_error_ssl_title)
                            .setMessage(R.string.adb_error_ssl_message)
                            .setPositiveButton(R.string.adb_error_ssl_button_reset) { _, _ ->
                                SettingsPage.Developer.Options.launch(activity)
                            }.setNegativeButton(android.R.string.cancel, null)
                            .show()
                    } else {
                        // Fallback for non-activity context
                        val themedContext = ContextThemeWrapper(context, R.style.AppTheme)
                        Toast.makeText(themedContext, R.string.adb_error_ssl_message, Toast.LENGTH_LONG).show()
                    }
                }
            }
            if (e !is CancellationException && !e.isExpectedAdbError()) {
                Sentry.captureException(e)
            }
            throw e
        } finally {
            if (ShizukuSettings.getAutoDisableUsbDebugging() && context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED) {
                Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 0)
            }
        }
    }

    suspend fun stopTcp(
        context: Context,
        port: Int,
    ) {
        if (port !in 1..65535) return
        runCatching {
            val cr = context.contentResolver
            if (context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED) {
                Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1)
                Settings.Global.putLong(cr, "adb_allowed_connection_time", 0L)
            }

            if (!EnvironmentUtils.isAdbEnabled()) throw IllegalStateException("ADB is not enabled")

            ShizukuStateMachine.set(ShizukuStateMachine.State.STOPPING)
            val key = AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku+")
            withContext(Dispatchers.IO) {
                connectAdaptive("127.0.0.1", port, key) { client, _ ->
                    client.command("usb:")
                }
            }
        }.onFailure {
            if (it !is CancellationException && !it.isExpectedAdbError(includeIllegalState = true)) {
                Sentry.captureException(it)
            }
            if (EnvironmentUtils.getAdbTcpPort() > 0) {
                ShizukuStateMachine.update()
                withContext(Dispatchers.Main) {
                    val errorMsg =
                        when (it) {
                            is AdbKeyException -> context.getString(R.string.adb_error_key_store)
                            else -> it.message
                        }
                    Toast
                        .makeText(context, context.getString(R.string.adb_error_stop_tcp) + ". ${errorMsg?.take(80)}", Toast.LENGTH_LONG)
                        .show()
                }
            }
        }
    }

    private fun getCandidateHosts(preferredHost: String): List<String> =
        AdbPortProber.getCandidateHosts(preferredHost)

    private suspend fun connectAdaptive(
        preferredHost: String,
        port: Int,
        key: AdbKey,
        action: suspend (client: AdbClient, connectedHost: String) -> Unit,
    ) {
        val candidateHosts = getCandidateHosts(preferredHost)
        var delayTime = 400L
        val maxAttempts = 8
        var lastException: Exception? = null

        for (attempt in 1..maxAttempts) {
            if (attempt > 1) {
                delay(delayTime)
                delayTime = (delayTime * 1.4).toLong().coerceAtMost(2500L)
            }

            for (targetHost in candidateHosts) {
                val client = AdbClient(targetHost, port, key)
                try {
                    Timber.tag(TAG).d("Connecting to ADB attempt %d/%d (host=%s, port=%d)", attempt, maxAttempts, targetHost, port)
                    client.connect(
                        onKeyOffered = { keyOfferInFlight = true },
                        onKeyOfferDone = { keyOfferInFlight = false },
                    )
                    Timber.tag(TAG).i("Connected to ADB at %s:%d on attempt %d", targetHost, port, attempt)
                    client.use {
                        action(it, targetHost)
                    }
                    return
                } catch (e: Exception) {
                    client.close()
                    lastException = e
                    if (e is CancellationException) throw e
                    if (e is ConnectException || e is java.net.NoRouteToHostException) {
                        Timber.tag(TAG).d("Host %s refused port %d, checking alternate interface...", targetHost, port)
                        continue
                    } else {
                        break
                    }
                }
            }
        }
        throw lastException ?: ConnectException("Could not connect to ADB daemon on port $port across candidate hosts $candidateHosts")
    }
}

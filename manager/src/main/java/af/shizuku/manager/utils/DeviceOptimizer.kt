package af.shizuku.manager.utils

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import timber.log.Timber
import java.io.File

object DeviceOptimizer {
    private fun runCommand(cmd: String): Boolean {
        if (Shizuku.pingBinder()) {
            try {
                val p = Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, null)
                if (p != null) {
                    val code = p.waitFor()
                    p.destroy()
                    return code == 0
                }
            } catch (e: Exception) {
                Timber.tag("DeviceOptimizer").w(e, "Command failed via Shizuku: $cmd")
            }
        }
        if (EnvironmentUtils.isRooted()) {
            try {
                return com.topjohnwu.superuser.Shell
                    .cmd(cmd)
                    .exec()
                    .isSuccess
            } catch (e: Exception) {
                Timber.tag("DeviceOptimizer").w(e, "Command failed via Root: $cmd")
            }
        }
        return false
    }

    /** True when a privileged shell (Shizuku or root) is available to run fixes. */
    fun hasPrivilegedShell(): Boolean = Shizuku.pingBinder() || EnvironmentUtils.isRooted()

    enum class BackgroundState {
        /** Exempt from doze and not restricted - nothing to do. */
        PROTECTED,

        /** The OS or the OEM (Samsung "sleeping apps") is actively restricting background work. */
        RESTRICTED,

        /** Not restricted right now, but not exempt either; may be put to sleep later. */
        UNPROTECTED,
    }

    /**
     * Reads, from observable effects, whether the system will keep this app alive in the background.
     * Samsung's sleeping-apps lists live in Device Care's private storage, but putting an app to
     * sleep shows up as a restricted standby bucket / background restriction, which an app can read
     * about itself without any permission.
     */
    fun backgroundState(context: Context): BackgroundState {
        val restricted =
            runCatching {
                val am = context.getSystemService(android.app.ActivityManager::class.java)
                val usm = context.getSystemService(android.app.usage.UsageStatsManager::class.java)
                val bucketRestricted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && usm != null && usm.appStandbyBucket >= android.app.usage.UsageStatsManager.STANDBY_BUCKET_RESTRICTED
                val bgRestricted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && am?.isBackgroundRestricted == true
                bucketRestricted || bgRestricted
            }.getOrDefault(false)
        return when {
            restricted -> BackgroundState.RESTRICTED
            SettingsHelper.isIgnoringBatteryOptimizations(context) -> BackgroundState.PROTECTED
            else -> BackgroundState.UNPROTECTED
        }
    }

    /**
     * Applies every exemption a privileged shell can: doze whitelist, background app-ops and an
     * active standby bucket. True when the app then reads as [BackgroundState.PROTECTED].
     */
    suspend fun protectSelf(context: Context): Boolean =
        withContext(Dispatchers.IO) {
            if (!hasPrivilegedShell()) return@withContext false
            val pkg = context.packageName
            runCommand("dumpsys deviceidle whitelist +$pkg")
            runCommand("cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow")
            runCommand("cmd appops set $pkg RUN_IN_BACKGROUND allow")
            runCommand("am set-standby-bucket $pkg active")
            backgroundState(context) == BackgroundState.PROTECTED
        }

    private const val KEY_NAG_SNOOZE_UNTIL = "battery_nag_snooze_until"

    fun isNagSnoozed(): Boolean =
        runCatching {
            System.currentTimeMillis() <
                af.shizuku.manager.ShizukuSettings
                    .getPreferences()
                    .getLong(KEY_NAG_SNOOZE_UNTIL, 0L)
        }.getOrDefault(false)

    /** Keeps the Home battery prompt quiet after the user dealt with it or dismissed it. */
    fun snoozeNag(days: Int) {
        runCatching {
            af.shizuku.manager.ShizukuSettings
                .getPreferences()
                .edit()
                .putLong(KEY_NAG_SNOOZE_UNTIL, System.currentTimeMillis() + days * 24L * 60 * 60 * 1000)
                .apply()
        }
    }

    /** Exempts this app from battery optimization without the system prompt. True when it took effect. */
    suspend fun whitelistSelf(context: Context): Boolean =
        withContext(Dispatchers.IO) {
            if (!hasPrivilegedShell()) return@withContext false
            runCommand("dumpsys deviceidle whitelist +${context.packageName}")
            SettingsHelper.isIgnoringBatteryOptimizations(context)
        }

    /** Grants this app a runtime permission without the system prompt. True when it is now granted. */
    suspend fun grantSelf(
        context: Context,
        permission: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            if (!hasPrivilegedShell()) return@withContext false
            runCommand("pm grant ${context.packageName} $permission")
            context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        }

    suspend fun applyFixes(context: Context): Boolean =
        withContext(Dispatchers.IO) {
            if (!Shizuku.pingBinder() && !EnvironmentUtils.isRooted()) {
                return@withContext false
            }

            val pkgName = context.packageName

            // 1. Whitelist Shizuku from Doze / Battery Optimization
            runCommand("dumpsys deviceidle whitelist +$pkgName")

            // 2. Whitelist known terminal and companion packages if installed
            val clientPackages =
                listOf(
                    "com.termux",
                    "com.termux.api",
                    "in.hridayan.ashell",
                    "com.rubex.nfile",
                )
            val pm = context.packageManager
            for (client in clientPackages) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        pm.getPackageInfo(client, PackageManager.PackageInfoFlags.of(0))
                    } else {
                        @Suppress("DEPRECATION")
                        pm.getPackageInfo(client, 0)
                    }
                    runCommand("dumpsys deviceidle whitelist +$client")
                } catch (_: PackageManager.NameNotFoundException) {
                    // Ignore uninstalled packages
                }
            }

            // 3. Grant WRITE_SECURE_SETTINGS to self if not granted
            if (!SettingsHelper.hasWriteSecureSettings(context)) {
                runCommand("pm grant $pkgName android.permission.WRITE_SECURE_SETTINGS")
            }

            // 4. Set AppOps for background resilience
            runCommand("appops set $pkgName SYSTEM_ALERT_WINDOW allow")
            runCommand("appops set $pkgName GET_USAGE_STATS allow")
            runCommand("appops set $pkgName SCHEDULE_EXACT_ALARM allow")

            // 5. Sync native starter binary to /data/local/tmp/shizuku
            try {
                val starterFile = File(context.applicationInfo.nativeLibraryDir, "libshizuku.so")
                if (starterFile.exists()) {
                    val tmpPath = "/data/local/tmp/shizuku"
                    runCommand("cp ${starterFile.absolutePath} $tmpPath")
                    runCommand("chmod 755 $tmpPath")
                }
            } catch (e: Exception) {
                Timber.tag("DeviceOptimizer").w(e, "Failed to sync starter binary")
            }

            true
        }
}

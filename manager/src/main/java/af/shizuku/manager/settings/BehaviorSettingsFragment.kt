package af.shizuku.manager.settings

import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.ShizukuSettings.Keys.*
import af.shizuku.manager.app.SnackbarHelper
import af.shizuku.manager.automation.AutomationService
import af.shizuku.manager.service.AdbProxyService
import af.shizuku.manager.service.ShizukuLiveService
import af.shizuku.manager.service.WatchdogService
import af.shizuku.manager.utils.DeviceOptimizer
import af.shizuku.manager.utils.EnvironmentUtils
import af.shizuku.manager.utils.LiveActivityNotificationManager
import af.shizuku.manager.utils.NetworkTrust
import af.shizuku.manager.utils.ShizukuStateMachine
import af.shizuku.manager.utils.TcpNetworkGuard
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.TwoStatePreference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BehaviorSettingsFragment :
    BaseSettingsFragment(),
    SharedPreferences.OnSharedPreferenceChangeListener {
    companion object {
        private const val TAG = "BehaviorSettingsFragment"
    }

    override fun getTitle(): CharSequence? = getString(R.string.settings_main_nav_startup_behavior_title)

    private lateinit var startOnBootPreference: TwoStatePreference
    private lateinit var watchdogPreference: TwoStatePreference
    private lateinit var deviceHardeningPreference: TwoStatePreference
    private lateinit var wirelessAdbPreference: WirelessAdbPreference
    private lateinit var networkCategory: CollapsiblePreferenceCategory
    private lateinit var startupCategory: CollapsiblePreferenceCategory

    // True when TCP mode is logically available on this device (TLS-capable or TV).
    // The Wireless ADB card is hidden otherwise, and always in root mode.
    private var tcpModeAvailable = false

    private val watchdogRunningListener: (Boolean) -> Unit = {
        syncWatchdogPreference()
    }

    override fun onCreateSettingsPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        migrateAutomationTrustedNetworks()
        setPreferencesFromResource(R.xml.settings_behavior, rootKey)
        val context = requireContext()

        startOnBootPreference = requireNotNull(findPreference(KEY_START_ON_BOOT))
        watchdogPreference = requireNotNull(findPreference(KEY_WATCHDOG))
        deviceHardeningPreference = requireNotNull(findPreference(KEY_DEVICE_HARDENING_ENABLED))
        wirelessAdbPreference = requireNotNull(findPreference("wireless_adb_card"))
        networkCategory = requireNotNull(findPreference("category_network_activity"))
        startupCategory = requireNotNull(findPreference("category_startup"))

        startOnBootPreference.apply {
            isChecked = ShizukuSettings.getStartOnBoot(context)
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue is Boolean) {
                    val doToggle = {
                        maybeToggleSecureSetting(newValue) { secureResult ->
                            if (secureResult) {
                                maybeToggleBatterySensitiveSetting(newValue) { batteryResult ->
                                    if (batteryResult) {
                                        ShizukuSettings.setStartOnBoot(context, newValue)
                                        isChecked = ShizukuSettings.getStartOnBoot(context)
                                    }
                                }
                            }
                        }
                    }
                    // https://r.android.com/2128832
                    if (newValue &&
                        !EnvironmentUtils.isTelevision() &&
                        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                    ) {
                        showDialog(
                            MaterialAlertDialogBuilder(context)
                                .setTitle(android.R.string.dialog_alert_title)
                                .setMessage(R.string.settings_start_on_boot_bug)
                                .setPositiveButton(android.R.string.ok) { _, _ -> doToggle() }
                                .setNegativeButton(android.R.string.cancel) { _, _ -> isChecked = !newValue },
                        )
                    } else {
                        doToggle()
                    }
                }
                false
            }
        }

        watchdogPreference.apply {
            syncWatchdogPreference()
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue is Boolean) {
                    maybeToggleBatterySensitiveSetting(newValue) { result ->
                        if (result) {
                            ShizukuSettings.setWatchdog(context, newValue)
                            syncWatchdogPreference()
                        }
                    }
                }
                false
            }
        }

        deviceHardeningPreference.apply {
            isChecked = ShizukuSettings.isDeviceHardeningEnabled()
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue is Boolean) {
                    ShizukuSettings.setDeviceHardeningEnabled(newValue)
                    isChecked = newValue
                    if (newValue) {
                        lifecycleScope.launch {
                            val success = DeviceOptimizer.applyFixes(context)
                            if (success) {
                                Toast.makeText(context, R.string.device_hardening_applied, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                true
            }
        }

        setupWirelessAdbCard()

        findPreference<TwoStatePreference>(KEY_AUTO_DISABLE_USB_DEBUGGING)?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue is Boolean) {
                    maybeToggleSecureSetting(newValue) { result ->
                        if (result) {
                            isChecked = newValue
                        }
                    }
                }
                false
            }
        }

        findPreference<TwoStatePreference>(KEY_LIVE_ACTIVITY_ENABLED)?.setOnPreferenceChangeListener { _, newValue ->
            val enable = newValue as Boolean
            val ctx = requireContext()
            if (!enable) {
                val svcIntent = Intent(ctx, ShizukuLiveService::class.java)
                ctx.stopService(svcIntent)
                LiveActivityNotificationManager.dismiss(ctx)
            }
            true
        }

        findPreference<TwoStatePreference>("adb_proxy_enabled")?.setOnPreferenceChangeListener { _, newValue ->
            if (newValue is Boolean) {
                val intent = Intent(context, AdbProxyService::class.java)
                if (newValue) context.startService(intent) else context.stopService(intent)
            }
            true
        }

        findPreference<TwoStatePreference>("on_device_adb_tcp")?.setOnPreferenceChangeListener { _, newValue ->
            if (newValue is Boolean) {
                preferenceManager.sharedPreferences
                    ?.edit()
                    ?.putBoolean("on_device_adb_tcp", newValue)
                    ?.apply()
                lifecycleScope.launch(Dispatchers.IO) {
                    if (newValue) {
                        AdbProxyService.enableAdbTcp()
                    } else {
                        AdbProxyService.disableAdbTcp()
                    }
                }
            }
            true
        }

        findPreference<TwoStatePreference>("force_start_wadb")?.setOnPreferenceChangeListener { _, newValue ->
            if (newValue is Boolean) {
                preferenceManager.sharedPreferences
                    ?.edit()
                    ?.putBoolean("force_start_wadb", newValue)
                    ?.apply()
                ShizukuSettings.syncAllPlusFeaturesToServer()
            }
            true
        }

        listOf("network_governor_plus_enabled", "binder_firewall_enabled", "continuity_bridge_enabled").forEach { key ->
            findPreference<TwoStatePreference>(key)?.setOnPreferenceChangeListener { _, _ ->
                ShizukuSettings.syncAllPlusFeaturesToServer()
                true
            }
        }

        findPreference<Preference>("automation_trusted_networks")?.setOnPreferenceChangeListener { _, _ ->
            val ctx = context
            if (ctx != null) {
                if (ShizukuSettings.hasAnyAutomationRulesConfigured()) {
                    AutomationService.startIfNeeded(ctx)
                } else {
                    AutomationService.stopIfRunning(ctx)
                }
            }
            true
        }

        syncModeVisibility()
    }

    /**
     * Wires the Wireless ADB card's restart-sensitive changes (TCP mode, port) through the same
     * restart / close-port prompts the individual rows used. Everything else the card writes
     * straight to prefs; TcpNetworkGuard picks those up via onSharedPreferenceChanged.
     */
    private fun setupWirelessAdbCard() {
        val context = requireContext()
        wirelessAdbPreference.apply {
            when {
                EnvironmentUtils.isTlsSupported() -> tcpModeAvailable = true
                EnvironmentUtils.isTelevision() -> {
                    tcpModeAvailable = true
                    tcpModeLocked = true
                }
                else -> tcpModeAvailable = false
            }
            onTcpModeChangeRequest = { enable, apply ->
                if (!enable && !ShizukuStateMachine.isRunning() && needsRestart(KEY_TCP_MODE, enable)) {
                    promptStopTcp(
                        onClosing = { setClosingPort(true) },
                        onFinished = { setClosingPort(false) },
                        applyChange = apply,
                    )
                } else {
                    maybePromptRestart(KEY_TCP_MODE, enable) { apply() }
                }
            }
            onPortChangeRequest = { port, randomized, apply ->
                maybePromptRestart(KEY_TCP_PORT, port ?: 5555) {
                    apply()
                    if (randomized && port != null) {
                        SnackbarHelper.show(
                            context,
                            requireView(),
                            context.getString(R.string.snackbar_tcp_port_randomized, port),
                        )
                    }
                }
            }
            onInvalidPort = {
                SnackbarHelper.show(context, requireView(), context.getString(R.string.snackbar_invalid_port))
            }
        }
    }

    /**
     * Show/hide preferences that are only relevant to the current connection mode (#433).
     *
     * ADB-only: the Wireless ADB card and auto_reconnect_mdns -- meaningless in root mode since root
     *   starts the server directly without a TCP/wireless ADB connection.
     *
     * When in root mode the "Network" and "Startup & Recovery" category headers gain an
     * explanatory summary so users understand why those options are absent.
     * Mode is refreshed on every onResume() so it updates if the user switches modes.
     */
    private fun syncModeVisibility() {
        val isRootMode =
            EnvironmentUtils.isRooted() ||
                ShizukuSettings.getLastLaunchMode() == ShizukuSettings.LaunchMethod.ROOT
        val isAdbMode = !isRootMode

        // Route through setChildAvailable so the category's expand/collapse cycle
        // does not restore preferences that should stay hidden in root mode.
        networkCategory.setChildAvailable("wireless_adb_card", tcpModeAvailable && !isRootMode)
        val mdnsSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        startupCategory.setChildAvailable(KEY_AUTO_RECONNECT_MDNS, isAdbMode && mdnsSupported)

        // Category summaries as mode indicators
        networkCategory.setCustomSummary(if (isRootMode) getString(R.string.settings_mode_indicator_root) else null)
        startupCategory.setCustomSummary(if (isRootMode) getString(R.string.settings_mode_indicator_root) else null)
    }

    override fun onResume() {
        super.onResume()
        preferenceScreen.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
        WatchdogService.addRunningListener(watchdogRunningListener)
        syncModeVisibility()
        syncWatchdogPreference()
        // Pick up changes made outside the card (e.g. TcpNetworkGuard, quick settings tile).
        wirelessAdbPreference.reload()
        // Ensure the external JSON file reflects current state on every resume, not just on
        // preference change. Without this a new install never writes the file so external
        // automation reads stale or missing data.
        context?.let { syncUsbPhoneSwitchConfig(it, ShizukuSettings.isUsbPhoneSwitchNotifyEnabled()) }
    }

    override fun onPause() {
        WatchdogService.removeRunningListener(watchdogRunningListener)
        preferenceScreen.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
        super.onPause()
    }

    override fun onSharedPreferenceChanged(
        sharedPreferences: SharedPreferences,
        key: String?,
    ) {
        if (key == KEY_WATCHDOG) syncWatchdogPreference()
        if (key == KEY_USB_PHONE_SWITCH_NOTIFY) {
            context?.let { ctx ->
                syncUsbPhoneSwitchConfig(ctx, ShizukuSettings.isUsbPhoneSwitchNotifyEnabled())
            }
        }
        if (key == NetworkTrust.KEY_MODE || key == NetworkTrust.KEY_AUTO_CLOSE || key == NetworkTrust.KEY_TRUSTED_SSIDS) {
            context?.let {
                TcpNetworkGuard.sync(it)
                TcpNetworkGuard.enforce(it)
            }
        }
    }

    private fun syncWatchdogPreference() {
        val isOn = watchdogShownOn()
        watchdogPreference.isChecked = isOn
        if (isOn && !WatchdogService.isRunning()) {
            watchdogPreference.setSummary(R.string.settings_watchdog_not_running)
        } else {
            watchdogPreference.setSummary(R.string.settings_watchdog_summary)
        }
    }

    private fun syncUsbPhoneSwitchConfig(
        context: android.content.Context,
        enabled: Boolean,
    ) {
        try {
            val docsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS)
            if (docsDir != null && (docsDir.exists() || docsDir.mkdirs())) {
                val file = java.io.File(docsDir, "shizuku_usb_switch.json")
                file.writeText("{\"enabled\": $enabled}")
            }
        } catch (_: Throwable) {
        }
    }

    private fun migrateAutomationTrustedNetworks() {
        val prefs = preferenceManager.sharedPreferences ?: return
        try {
            prefs.getString(KEY_AUTOMATION_TRUSTED_NETWORKS, null)
        } catch (_: ClassCastException) {
            val oldSet =
                try {
                    @Suppress("UNCHECKED_CAST")
                    prefs.getStringSet(KEY_AUTOMATION_TRUSTED_NETWORKS, null)
                } catch (_: Exception) {
                    null
                }
            prefs
                .edit()
                .putString(KEY_AUTOMATION_TRUSTED_NETWORKS, oldSet?.joinToString(",") ?: "")
                .apply()
        }
    }

    // A new install's Watchdog is on by default from its first server start; until then the
    // switch shows that default, and turning it off stores the choice.
    private fun watchdogShownOn(): Boolean =
        ShizukuSettings.getWatchdog() || ShizukuSettings.isWatchdogDefaultPending()
}

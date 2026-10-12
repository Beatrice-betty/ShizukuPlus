package af.shizuku.manager.settings

import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.ShizukuSettings.Keys.*
import af.shizuku.manager.app.SnackbarHelper
import af.shizuku.manager.automation.AutomationService
import af.shizuku.manager.service.AdbProxyService
import af.shizuku.manager.service.ShizukuLiveService
import af.shizuku.manager.utils.DeviceOptimizer
import af.shizuku.manager.utils.EnvironmentUtils
import af.shizuku.manager.utils.NetworkTrust
import af.shizuku.manager.utils.ShizukuStateMachine
import af.shizuku.manager.utils.TcpNetworkGuard
import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
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
    private lateinit var tcpModePreference: TwoStatePreference
    private lateinit var tcpPortPreference: EditTextPreference
    private lateinit var tcpRandomizePreference: Preference
    private lateinit var tcpAutoClosePreference: TwoStatePreference
    private lateinit var tcpProtectionPreference: Preference
    private lateinit var tcpTrustedPreference: Preference
    private lateinit var networkCategory: CollapsiblePreferenceCategory
    private lateinit var startupCategory: CollapsiblePreferenceCategory

    // True when tcp_mode is logically available on this device (TLS-capable or TV).
    // Used to gate syncTcpPortVisibility() so root-mode hides are not undone.
    private var tcpModeAvailable = false

    private val stateListener: (ShizukuStateMachine.State) -> Unit = {
        if (ShizukuStateMachine.isRunning()) {
            tcpModePreference.icon = maybeGetRestartIcon(KEY_TCP_MODE, R.drawable.ic_wadb_24)
                ?: tint(requireContext().getDrawable(R.drawable.ic_wadb_24))
            tcpPortPreference.icon = maybeGetRestartIcon(KEY_TCP_PORT, R.drawable.ic_lan_24)
                ?: tint(requireContext().getDrawable(R.drawable.ic_lan_24))
        }
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
        tcpModePreference = requireNotNull(findPreference(KEY_TCP_MODE))
        tcpPortPreference = requireNotNull(findPreference(KEY_TCP_PORT))
        tcpRandomizePreference = requireNotNull(findPreference("tcp_port_randomize"))
        tcpAutoClosePreference = requireNotNull(findPreference(KEY_TCP_AUTO_CLOSE_PORT))
        tcpProtectionPreference = requireNotNull(findPreference("tcp_network_protection"))
        tcpTrustedPreference =
            requireNotNull<Preference>(findPreference("tcp_trusted_networks")).apply {
                setOnPreferenceClickListener {
                    showTrustedNetworksDialog()
                    true
                }
            }
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
            isChecked = ShizukuSettings.isWatchdogRunning()
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue is Boolean) {
                    maybeToggleBatterySensitiveSetting(newValue) { result ->
                        if (result) {
                            ShizukuSettings.setWatchdog(context, newValue)
                            isChecked = newValue
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

        tcpModePreference.apply {
            if (EnvironmentUtils.isTlsSupported()) {
                tcpModeAvailable = true
                setCustomSummary(context.getString(R.string.settings_tcp_mode_summary))
                icon = maybeGetRestartIcon(KEY_TCP_MODE, R.drawable.ic_wadb_24)
                    ?: tint(context.getDrawable(R.drawable.ic_wadb_24))
                setOnPreferenceChangeListener { _, newValue ->
                    if (newValue is Boolean) {
                        val applyChange: () -> Unit = {
                            ShizukuSettings.setTcpMode(newValue)
                            isChecked = newValue
                            isEnabled = true
                            setCustomSummary(context.getString(R.string.settings_tcp_mode_summary))
                            icon = maybeGetRestartIcon(KEY_TCP_MODE, R.drawable.ic_wadb_24)
                                ?: tint(context.getDrawable(R.drawable.ic_wadb_24))
                            syncTcpPortVisibility()
                        }
                        if (!newValue && !ShizukuStateMachine.isRunning() && needsRestart(KEY_TCP_MODE, newValue)) {
                            promptStopTcp(tcpModePreference) { applyChange() }
                        } else {
                            maybePromptRestart(KEY_TCP_MODE, newValue) { applyChange() }
                        }
                    }
                    false
                }
            } else if (EnvironmentUtils.isTelevision()) {
                tcpModeAvailable = true
                isEnabled = false
                isChecked = true
            } else {
                // Non-TLS device: hide tcp_mode, tcp_port, randomize, and auto-close through the
                // category so expand/collapse cycles don't accidentally restore them.
                networkCategory.setChildAvailable(KEY_TCP_MODE, false)
                networkCategory.setChildAvailable(KEY_TCP_PORT, false)
                networkCategory.setChildAvailable("tcp_port_randomize", false)
                networkCategory.setChildAvailable(KEY_TCP_AUTO_CLOSE_PORT, false)
            }
        }

        tcpPortPreference.apply {
            syncTcpPortVisibility()
            icon = maybeGetRestartIcon(KEY_TCP_PORT, R.drawable.ic_lan_24)
                ?: tint(context.getDrawable(R.drawable.ic_lan_24))
            setOnBindEditTextListener { editText ->
                editText.hint = context.getString(R.string.settings_tcp_port_hint)
                editText.inputType = InputType.TYPE_CLASS_NUMBER
                editText.setSelection(editText.text.length)
            }
            summaryProvider =
                Preference.SummaryProvider<EditTextPreference> { pref ->
                    val text = pref.text
                    if (text.isNullOrEmpty()) context.getString(R.string.settings_tcp_port_default) else text
                }
            setOnPreferenceChangeListener { _, newValue ->
                val port = (newValue as? String)?.toIntOrNull()
                if (port == null || port in 1..65535) {
                    val applyChange: () -> Unit = {
                        ShizukuSettings.setTcpPort(port)
                        text = port?.toString()
                        icon = maybeGetRestartIcon(KEY_TCP_PORT, R.drawable.ic_lan_24)
                    }
                    maybePromptRestart(KEY_TCP_PORT, port ?: 5555) { applyChange() }
                } else {
                    SnackbarHelper.show(context, requireView(), context.getString(R.string.snackbar_invalid_port))
                }
                false
            }
        }

        tcpRandomizePreference.setOnPreferenceClickListener {
            val randomPort = (49152..65535).random()
            val applyChange: () -> Unit = {
                ShizukuSettings.setTcpPort(randomPort)
                tcpPortPreference.text = randomPort.toString()
                SnackbarHelper.show(
                    requireContext(),
                    requireView(),
                    requireContext().getString(R.string.snackbar_tcp_port_randomized, randomPort),
                )
            }
            maybePromptRestart(KEY_TCP_PORT, randomPort) { applyChange() }
            true
        }

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
            val svcIntent = Intent(ctx, ShizukuLiveService::class.java)
            if (enable) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(svcIntent)
                } else {
                    ctx.startService(svcIntent)
                }
            } else {
                ctx.stopService(svcIntent)
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
            AutomationService.startOrStop(context)
            true
        }

        syncModeVisibility()
    }

    /**
     * Show/hide preferences that are only relevant to the current connection mode (#433).
     *
     * ADB-only: tcp_mode, tcp_port, auto_reconnect_mdns -- meaningless in root mode since root
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
        if (isRootMode) {
            networkCategory.setChildAvailable(KEY_TCP_MODE, false)
            networkCategory.setChildAvailable(KEY_TCP_PORT, false)
            networkCategory.setChildAvailable("tcp_port_randomize", false)
            networkCategory.setChildAvailable(KEY_TCP_AUTO_CLOSE_PORT, false)
        } else if (tcpModeAvailable) {
            networkCategory.setChildAvailable(KEY_TCP_MODE, true)
            syncTcpPortVisibility()
        }
        startupCategory.setChildAvailable(KEY_AUTO_RECONNECT_MDNS, isAdbMode)

        // Category summaries as mode indicators
        networkCategory.setCustomSummary(if (isRootMode) getString(R.string.settings_mode_indicator_root) else null)
        startupCategory.setCustomSummary(if (isRootMode) getString(R.string.settings_mode_indicator_root) else null)
    }

    private fun syncTcpPortVisibility() {
        val isRootMode =
            EnvironmentUtils.isRooted() ||
                ShizukuSettings.getLastLaunchMode() == ShizukuSettings.LaunchMethod.ROOT
        if (tcpModeAvailable && !isRootMode) {
            val tcpOn = tcpModePreference.isChecked
            networkCategory.setChildAvailable(KEY_TCP_PORT, tcpOn)
            networkCategory.setChildAvailable("tcp_port_randomize", tcpOn)
            networkCategory.setChildAvailable(KEY_TCP_AUTO_CLOSE_PORT, tcpOn)
        }
        syncProtectionVisibility()
    }

    /**
     * TCP network-protection rows (#518) follow the TCP rows' availability, and adapt further:
     * the trusted-networks manager only matters in "trusted only" mode, and auto-close only when
     * protection is on at all.
     */
    private fun syncProtectionVisibility() {
        val isRootMode =
            EnvironmentUtils.isRooted() ||
                ShizukuSettings.getLastLaunchMode() == ShizukuSettings.LaunchMethod.ROOT
        val base = tcpModeAvailable && !isRootMode && tcpModePreference.isChecked
        val mode = NetworkTrust.mode()
        networkCategory.setChildAvailable("tcp_network_protection", base)
        networkCategory.setChildAvailable("tcp_trusted_networks", base && mode == NetworkTrust.MODE_TRUSTED)
        networkCategory.setChildAvailable(NetworkTrust.KEY_AUTO_CLOSE, base && mode != NetworkTrust.MODE_OFF)
        tcpTrustedPreference.setCustomSummary(trustedSummary())
        // tcp_network_protection is a SimpleMenuPreference (ListPreference) with
        // useSimpleSummaryProvider="true", so it already has a SummaryProvider installed;
        // setSummary() throws "Preference already has a SummaryProvider set." if called while one
        // is active (same root cause as #587). Toggle the provider itself instead of the text.
        val limited = !NetworkTrust.canDetectOpenNetworks() && mode != NetworkTrust.MODE_OFF
        (tcpProtectionPreference as? androidx.preference.ListPreference)?.apply {
            if (limited) {
                setCustomSummary(getString(R.string.tcp_protection_limited_summary))
            } else if (summaryProvider == null) {
                summaryProvider =
                    androidx.preference.ListPreference.SimpleSummaryProvider
                        .getInstance()
            }
        }
    }

    private fun trustedSummary(): String {
        val count = NetworkTrust.trustedSsids().size
        return if (count == 0) {
            getString(R.string.settings_tcp_trusted_networks_none)
        } else {
            getString(R.string.settings_tcp_trusted_networks_summary, count)
        }
    }

    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) trustCurrentNetwork()
        }

    private fun trustCurrentNetwork() {
        val context = requireContext()
        if (!af.shizuku.manager.utils.NetworkStateHelper
                .isWifiConnected(context)
        ) {
            Toast.makeText(context, R.string.tcp_trusted_not_on_wifi, Toast.LENGTH_SHORT).show()
            return
        }
        val ssid = NetworkTrust.currentSsid(context)
        if (ssid != null) {
            NetworkTrust.trustSsid(ssid)
            Toast.makeText(context, getString(R.string.tcp_trusted_added, ssid), Toast.LENGTH_SHORT).show()
            syncProtectionVisibility()
            TcpNetworkGuard.enforce(context)
        } else if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            Toast.makeText(context, R.string.tcp_trusted_ssid_hidden, Toast.LENGTH_LONG).show()
        }
    }

    private fun showTrustedNetworksDialog() {
        val context = requireContext()
        val saved = NetworkTrust.trustedSsids().sorted()
        val removed = mutableSetOf<String>()
        val builder =
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.settings_tcp_trusted_networks)
                .setPositiveButton(R.string.tcp_trusted_add_current) { _, _ -> trustCurrentNetwork() }
                .setNegativeButton(android.R.string.cancel, null)
        if (saved.isEmpty()) {
            builder.setMessage(R.string.tcp_trusted_dialog_empty)
        } else {
            builder
                .setMultiChoiceItems(saved.toTypedArray(), null) { _, which, checked ->
                    if (checked) removed += saved[which] else removed -= saved[which]
                }.setNeutralButton(R.string.tcp_trusted_remove_selected) { _, _ ->
                    removed.forEach { NetworkTrust.forgetSsid(it) }
                    syncProtectionVisibility()
                    TcpNetworkGuard.enforce(context)
                }
        }
        showDialog(builder)
    }

    override fun onResume() {
        super.onResume()
        preferenceScreen.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
        ShizukuStateMachine.addListener(stateListener)
        syncModeVisibility()
    }

    override fun onPause() {
        ShizukuStateMachine.removeListener(stateListener)
        preferenceScreen.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
        super.onPause()
    }

    override fun onSharedPreferenceChanged(
        sharedPreferences: SharedPreferences,
        key: String?,
    ) {
        if (key == KEY_WATCHDOG) watchdogPreference.isChecked = ShizukuSettings.isWatchdogRunning()
        if (key == NetworkTrust.KEY_MODE || key == NetworkTrust.KEY_AUTO_CLOSE) {
            context?.let {
                TcpNetworkGuard.sync(it)
                TcpNetworkGuard.enforce(it)
            }
            syncProtectionVisibility()
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
}

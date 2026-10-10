package af.shizuku.manager.utils

import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.settings.ServiceDoctorActivity
import android.content.Context
import android.content.Intent
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Tracks unexpected Shizuku server deactivations/crashes and provides actionable tips
 * and settings suggestions to keep Shizuku alive in the background.
 */
object DeactivationAdvisor {
    private const val PREF_KEY_DEACTIVATION_TIMESTAMPS = "unexpected_deactivation_timestamps"
    private const val WINDOW_MS = 24 * 60 * 60 * 1000L // 24 hours

    /** Record an unexpected server drop or crash. */
    fun recordUnexpectedDrop() {
        val sp = ShizukuSettings.getPreferences() ?: return
        val now = System.currentTimeMillis()
        val raw = sp.getString(PREF_KEY_DEACTIVATION_TIMESTAMPS, "") ?: ""
        val timestamps =
            raw
                .split(",")
                .mapNotNull { it.trim().toLongOrNull() }
                .filter { now - it < WINDOW_MS }
                .toMutableList()
        timestamps.add(now)
        sp.edit().putString(PREF_KEY_DEACTIVATION_TIMESTAMPS, timestamps.joinToString(",")).apply()
    }

    /** Count of unexpected deactivations in the last 24 hours. */
    fun getRecentDropCount(): Int {
        val sp = ShizukuSettings.getPreferences() ?: return 0
        val now = System.currentTimeMillis()
        val raw = sp.getString(PREF_KEY_DEACTIVATION_TIMESTAMPS, "") ?: ""
        return raw
            .split(",")
            .mapNotNull { it.trim().toLongOrNull() }
            .count { now - it < WINDOW_MS }
    }

    fun hasFrequentDeactivations(): Boolean = getRecentDropCount() >= 1

    data class Tip(
        val id: String,
        val title: String,
        val summary: String,
        val isEnabled: Boolean,
        val onEnable: (Context) -> Unit,
    )

    fun getTips(context: Context): List<Tip> {
        val tips = mutableListOf<Tip>()

        val watchdogOn = ShizukuSettings.getWatchdog()
        tips.add(
            Tip(
                id = "watchdog",
                title = context.getString(R.string.settings_watchdog),
                summary = context.getString(R.string.tip_watchdog_desc),
                isEnabled = watchdogOn,
                onEnable = { ShizukuSettings.setWatchdog(it, true) },
            ),
        )

        val autoReconnectOn = ShizukuSettings.isAutoReconnectMdnsEnabled()
        tips.add(
            Tip(
                id = "auto_reconnect",
                title = context.getString(R.string.settings_auto_reconnect_mdns),
                summary = context.getString(R.string.tip_auto_reconnect_desc),
                isEnabled = autoReconnectOn,
                onEnable = { ShizukuSettings.setAutoReconnectMdnsEnabled(true) },
            ),
        )

        val startOnBootOn = ShizukuSettings.getStartOnBoot(context)
        tips.add(
            Tip(
                id = "start_on_boot",
                title = context.getString(R.string.settings_start_on_boot),
                summary = context.getString(R.string.tip_start_on_boot_desc),
                isEnabled = startOnBootOn,
                onEnable = { ShizukuSettings.setStartOnBoot(it, true) },
            ),
        )

        val bgState = DeviceOptimizer.backgroundState(context)
        val protected = bgState == DeviceOptimizer.BackgroundState.PROTECTED
        tips.add(
            Tip(
                id = "battery_doctor",
                title = context.getString(R.string.home_service_doctor_title),
                summary = context.getString(R.string.tip_battery_doctor_desc),
                isEnabled = protected,
                onEnable = { ctx ->
                    ctx.startActivity(Intent(ctx, ServiceDoctorActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                },
            ),
        )

        return tips
    }

    /** Show the Stability / Keep-Alive Tips dialog */
    fun showStabilityTipsDialog(
        context: Context,
        onDismiss: () -> Unit = {},
    ) {
        val tips = getTips(context)
        val disabledTips = tips.filter { !it.isEnabled }

        val builder =
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.stability_tips_dialog_title)
                .setIcon(R.drawable.ic_settings_suggest_24)

        if (disabledTips.isEmpty()) {
            builder
                .setMessage(R.string.stability_tips_all_enabled_message)
                .setPositiveButton(R.string.home_service_doctor_title) { _, _ ->
                    context.startActivity(Intent(context, ServiceDoctorActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.setNegativeButton(android.R.string.ok, null)
        } else {
            val multiChoices = BooleanArray(disabledTips.size) { true }

            builder
                .setMultiChoiceItems(
                    disabledTips.map { it.title }.toTypedArray(),
                    multiChoices,
                ) { _, which, isChecked ->
                    multiChoices[which] = isChecked
                }.setMessage(R.string.stability_tips_dialog_message)
                .setPositiveButton(R.string.stability_tips_apply_selected) { _, _ ->
                    disabledTips.forEachIndexed { index, tip ->
                        if (multiChoices[index]) {
                            tip.onEnable(context)
                        }
                    }
                    onDismiss()
                }.setNeutralButton(R.string.home_service_doctor_title) { _, _ ->
                    context.startActivity(Intent(context, ServiceDoctorActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.setNegativeButton(android.R.string.cancel, null)
        }

        builder.setOnDismissListener { onDismiss() }.show()
    }
}

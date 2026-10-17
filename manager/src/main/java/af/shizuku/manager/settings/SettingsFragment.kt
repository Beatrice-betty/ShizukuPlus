package af.shizuku.manager.settings

import af.shizuku.manager.BuildConfig
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.utils.EnvironmentUtils
import af.shizuku.manager.utils.ShizukuStateMachine
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.preference.Preference
import rikka.shizuku.Shizuku

class SettingsFragment : BaseSettingsFragment() {
    private val stateListener: (ShizukuStateMachine.State) -> Unit = {
        activity?.runOnUiThread {
            updateStatusBanner()
        }
    }

    override fun getTitle(): CharSequence? = getString(R.string.settings_title)

    override fun onCreateSettingsPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        setPreferencesFromResource(R.xml.settings_main, rootKey)
        findPreference<Preference>("nav_about")?.setCustomSummary(BuildConfig.VERSION_NAME)

        updateStatusBanner()
    }

    override fun onResume() {
        super.onResume()
        ShizukuStateMachine.addListener(stateListener)
        updateStatusBanner()
    }

    override fun onPause() {
        super.onPause()
        ShizukuStateMachine.removeListener(stateListener)
    }

    private fun updateStatusBanner() {
        val banner = findPreference<Preference>("status_at_a_glance") ?: return
        val context = context ?: return
        val isRunning = ShizukuStateMachine.isRunning()
        val uid =
            try {
                if (isRunning) Shizuku.getUid() else -1
            } catch (_: Exception) {
                -1
            }

        val isRootMode =
            EnvironmentUtils.isRooted() ||
                ShizukuSettings.getLastLaunchMode() == ShizukuSettings.LaunchMethod.ROOT

        if (isRunning) {
            banner.icon = ContextCompat.getDrawable(context, R.drawable.ic_server_ok_24)
            if (uid == 0 || isRootMode) {
                banner.title = getString(R.string.home_status_service_user_root)
                banner.setCustomSummary(getString(R.string.settings_status_banner_running_root))
            } else {
                banner.title = getString(R.string.home_status_service_user_adb)
                banner.setCustomSummary(getString(R.string.settings_status_banner_running_adb))
            }
        } else {
            banner.icon = ContextCompat.getDrawable(context, R.drawable.ic_server_error_24)
            banner.title = getString(R.string.home_status_service_not_running, getString(R.string.app_name))
            banner.setCustomSummary(getString(R.string.settings_status_banner_stopped))
        }
    }
}

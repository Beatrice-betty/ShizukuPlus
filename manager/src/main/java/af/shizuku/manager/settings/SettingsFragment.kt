package af.shizuku.manager.settings

import af.shizuku.manager.BuildConfig
import af.shizuku.manager.R
import android.os.Bundle
import androidx.preference.Preference

class SettingsFragment : BaseSettingsFragment() {
    override fun getTitle(): CharSequence? = getString(R.string.settings_title)

    override fun onCreateSettingsPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        setPreferencesFromResource(R.xml.settings_main, rootKey)
        findPreference<Preference>("nav_about")?.setCustomSummary(BuildConfig.VERSION_NAME)
    }
}

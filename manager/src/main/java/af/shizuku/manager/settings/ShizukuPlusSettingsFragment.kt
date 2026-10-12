package af.shizuku.manager.settings

import af.shizuku.manager.R
import android.os.Bundle

/**
 * Legacy Feature Hub fragment preserved for backward compatibility / deep-link routing.
 * Automatically forwards to [AdvancedSettingsFragment] where core Plus capabilities now reside.
 */
class ShizukuPlusSettingsFragment : BaseSettingsFragment() {
    override fun getTitle(): CharSequence? = getString(R.string.settings_main_nav_advanced_diagnostics_title)

    override fun onCreateSettingsPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        setPreferencesFromResource(R.xml.settings_shizuku_plus, rootKey)
        if (!isAdded) return
        parentFragmentManager
            .beginTransaction()
            .replace(id, AdvancedSettingsFragment())
            .commit()
    }
}

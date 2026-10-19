package af.shizuku.manager.utils

import af.shizuku.manager.ShizukuSettings
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import timber.log.Timber

object AppAliasManager {
    const val ALIAS_DEFAULT = "af.shizuku.manager.LauncherAlias"
    const val ALIAS_CLASSIC = "af.shizuku.manager.LauncherAliasClassic"
    const val ALIAS_SYSTEM = "af.shizuku.manager.LauncherAliasSystem"
    const val ALIAS_TOOLS = "af.shizuku.manager.LauncherAliasTools"
    const val ALIAS_FILES = "af.shizuku.manager.LauncherAliasFiles"
    const val ALIAS_NOTES = "af.shizuku.manager.LauncherAliasNotes"

    val ALL_ALIASES =
        listOf(
            ALIAS_DEFAULT,
            ALIAS_CLASSIC,
            ALIAS_SYSTEM,
            ALIAS_TOOLS,
            ALIAS_FILES,
            ALIAS_NOTES,
        )

    fun applyAlias(
        context: Context,
        chosenKey: String,
    ) {
        val targetAlias =
            when (chosenKey) {
                "classic" -> ALIAS_CLASSIC
                "system" -> ALIAS_SYSTEM
                "tools" -> ALIAS_TOOLS
                "files" -> ALIAS_FILES
                "notes" -> ALIAS_NOTES
                else -> ALIAS_DEFAULT
            }
        val pm = context.packageManager

        // If stealth mode is enabled, all remain disabled
        if (ShizukuSettings.isHideLauncherIconEnabled()) {
            ALL_ALIASES.forEach { alias ->
                try {
                    pm.setComponentEnabledSetting(
                        ComponentName(context, alias),
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP,
                    )
                } catch (e: Exception) {
                    Timber.w(e, "Failed to disable alias $alias")
                }
            }
            return
        }

        // Enable target alias, disable others
        ALL_ALIASES.forEach { alias ->
            try {
                val newState =
                    if (alias == targetAlias) {
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    } else {
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    }
                pm.setComponentEnabledSetting(
                    ComponentName(context, alias),
                    newState,
                    PackageManager.DONT_KILL_APP,
                )
            } catch (e: Exception) {
                Timber.w(e, "Failed to update alias $alias")
            }
        }
    }
}

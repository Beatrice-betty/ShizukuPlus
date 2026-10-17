package af.shizuku.manager.settings

import af.shizuku.core.ui.compose.AppTheme
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.app.ThemeHelper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Theme wrapper for Compose content hosted inside a preference row. The preference RecyclerView
 * lives in a Fragment embedded via AndroidView, so MaterialTheme from SettingsActivity's
 * setContent does not reach a nested ComposeView - re-apply the same AppTheme here.
 */
@Composable
internal fun PreferenceComposeTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    AppTheme(
        isBlackNightTheme = ThemeHelper.isBlackNightTheme(context),
        isAmoledPlus = ShizukuSettings.isAmoledPlusEnabled(),
        isOneUi = ShizukuSettings.isOneUiThemeEnabled(),
        isRoundedEdges = ShizukuSettings.isRoundedEdgesEnabled(),
        content = content,
    )
}

/** Sets themed content on a preference's ComposeView. Safe to call on every bind. */
internal fun ComposeView.setPreferenceContent(content: @Composable () -> Unit) {
    setContent { PreferenceComposeTheme(content) }
}

/** Standard title/content/summary layout used by the Compose-backed preferences. */
@Composable
internal fun ComposePreferenceRow(
    title: CharSequence?,
    summary: CharSequence?,
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    val disabledAlpha = 0.38f
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        if (!title.isNullOrEmpty()) {
            Text(
                text = title.toString(),
                style = MaterialTheme.typography.titleMedium,
                color =
                    MaterialTheme.colorScheme.onSurface.let {
                        if (enabled) it else it.copy(alpha = disabledAlpha)
                    },
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        content()
        if (!summary.isNullOrEmpty()) {
            Text(
                text = summary.toString(),
                style = MaterialTheme.typography.bodyMedium,
                color =
                    MaterialTheme.colorScheme.onSurfaceVariant.let {
                        if (enabled) it else it.copy(alpha = disabledAlpha)
                    },
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

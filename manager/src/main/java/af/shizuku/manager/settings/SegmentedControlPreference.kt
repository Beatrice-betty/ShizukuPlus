package af.shizuku.manager.settings

import af.shizuku.manager.R
import android.content.Context
import android.content.res.TypedArray
import android.util.AttributeSet
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.style.TextOverflow
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder

/**
 * Single-choice preference rendered as an M3 segmented button row. Stores its value as a String,
 * exactly like ListPreference, so it is a drop-in replacement for short (2-4 option) lists.
 *
 * XML:
 * ```
 * <af.shizuku.manager.settings.SegmentedControlPreference
 *     android:key="..."
 *     android:title="..."
 *     android:entries="@array/..."
 *     android:entryValues="@array/..."
 *     android:defaultValue="..." />
 * ```
 * Listen for changes with setOnPreferenceChangeListener; return false to veto.
 */
class SegmentedControlPreference
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : Preference(context, attrs) {
        var entries: Array<CharSequence> = emptyArray()
        var entryValues: Array<CharSequence> = emptyArray()

        private val selected = mutableStateOf<String?>(null)

        var value: String?
            get() = selected.value
            set(newValue) {
                val changed = selected.value != newValue
                selected.value = newValue
                if (changed) {
                    persistString(newValue)
                    notifyDependencyChange(shouldDisableDependents())
                }
            }

        init {
            layoutResource = R.layout.preference_compose
            isSelectable = false
            context.obtainStyledAttributes(attrs, R.styleable.SegmentedControlPreference).apply {
                getTextArray(R.styleable.SegmentedControlPreference_android_entries)?.let { entries = it }
                getTextArray(R.styleable.SegmentedControlPreference_android_entryValues)?.let { entryValues = it }
                recycle()
            }
        }

        override fun onGetDefaultValue(
            a: TypedArray,
            index: Int,
        ): Any? = a.getString(index)

        override fun onSetInitialValue(defaultValue: Any?) {
            value = getPersistedString(defaultValue as? String)
        }

        override fun onBindViewHolder(holder: PreferenceViewHolder) {
            super.onBindViewHolder(holder)
            val cv =
                (holder.itemView as? ComposeView)
                    ?: (holder.findViewById(R.id.preference_compose_view) as? ComposeView)
            if (cv == null) {
                timber.log.Timber.w("SegmentedControlPreference: ComposeView not found in holder (itemView is %s)", holder.itemView.javaClass.name)
                return
            }
            val labels = entries.map { it.toString() }
            val values = entryValues.map { it.toString() }
            val enabled = isEnabled
            cv.setPreferenceContent {
                ComposePreferenceRow(title = title, summary = summary, enabled = enabled) {
                    SegmentedChoiceRow(
                        labels = labels,
                        selectedIndex = values.indexOf(selected.value),
                        enabled = enabled,
                        onSelect = { index ->
                            val newValue = values.getOrNull(index) ?: return@SegmentedChoiceRow
                            if (newValue != selected.value && callChangeListener(newValue)) {
                                value = newValue
                            }
                        },
                    )
                }
            }
        }
    }

/** Reusable segmented single-choice row (shared by SegmentedControlPreference and compound cards). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SegmentedChoiceRow(
    labels: List<String>,
    selectedIndex: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        labels.forEachIndexed { index, label ->
            SegmentedButton(
                selected = index == selectedIndex,
                onClick = { onSelect(index) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = labels.size),
                enabled = enabled,
                label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

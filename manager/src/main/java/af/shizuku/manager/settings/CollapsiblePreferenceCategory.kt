package af.shizuku.manager.settings

import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import android.content.Context
import android.content.SharedPreferences
import android.util.AttributeSet
import androidx.core.view.ViewCompat
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceViewHolder
import androidx.preference.TwoStatePreference

class CollapsiblePreferenceCategory
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : PreferenceCategory(context, attrs) {
        private var expanded = false
        private var isAnimating = false
        var onExpansionChanged: ((Boolean) -> Unit)? = null

        private var defaultExpanded = false
        private var collapsible = true

        // Keys of children that are conditionally unavailable (e.g. hidden by an OWNER fragment
        // based on OS version or another setting). Such children must stay hidden even when the
        // category is expanded, and the collapse toggle must not resurrect them.
        private val unavailableChildKeys = mutableSetOf<String>()

        // Refreshes the active-count chip when a child switch is toggled while the category
        // is collapsed, so the count is never stale the next time the header rebinds.
        private val childChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (!expanded && key != null) {
                val childKeys = (0 until preferenceCount).mapNotNull { getPreference(it).key }.toSet()
                if (key in childKeys) notifyChanged()
            }
        }
        private var prefListenerRegistered = false

        init {
            layoutResource = R.layout.collapsible_preference_category_card

            val a = context.obtainStyledAttributes(attrs, intArrayOf(android.R.attr.defaultValue, R.attr.collapsible))
            defaultExpanded = a.getBoolean(0, false)
            collapsible = a.getBoolean(1, true)
            a.recycle()

            // A non-collapsible category (e.g. a top-level nav menu) still needs the M3E card-grouping
            // treatment from isHeader()/tag, but must never hide its own entries - there'd be no other
            // way back to them.
            expanded = if (collapsible) defaultExpanded else true
            isSelectable = collapsible
        }

        override fun onBindViewHolder(holder: PreferenceViewHolder) {
            super.onBindViewHolder(holder)

            holder.itemView.tag = "category_header"

            val cornerStyle = ShizukuSettings.getPreferences().getString("settings_card_corner_style", "global") ?: "global"
            if (cornerStyle != "global") {
                val card = holder.itemView as? com.google.android.material.card.MaterialCardView
                if (card != null) {
                    val density = card.context.resources.displayMetrics.density
                    card.radius =
                        when (cornerStyle) {
                            "sharp" -> 0f
                            "rounded" -> 12f * density
                            "large" -> 24f * density
                            "squircle" -> 28f * density
                            "xlarge" -> 36f * density
                            else -> card.radius
                        }
                }
            }

            val (activeCount, totalSwitches) = countActiveSwitches()
            val ctx = holder.itemView.context
            val density = ctx.resources.displayMetrics.density

            // M3E icon pill: when a section has active switches, enclose the icon in a filled
            // colorPrimaryContainer oval so users can scan section state without expanding.
            val iconView = holder.findViewById(android.R.id.icon) as? android.widget.ImageView
            if (iconView != null) {
                val catIcon = icon
                if (catIcon != null) {
                    iconView.setImageDrawable(catIcon)
                    iconView.visibility = android.view.View.VISIBLE
                    if (activeCount > 0 && collapsible) {
                        val bgColor = com.google.android.material.color.MaterialColors.getColor(
                            ctx, com.google.android.material.R.attr.colorPrimaryContainer, 0xFFE8DEF8.toInt(),
                        )
                        val fgColor = com.google.android.material.color.MaterialColors.getColor(
                            ctx, com.google.android.material.R.attr.colorOnPrimaryContainer, 0xFF21005D.toInt(),
                        )
                        val pill = android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.OVAL
                            setColor(bgColor)
                        }
                        val pad = (5 * density).toInt()
                        iconView.setPadding(pad, pad, pad, pad)
                        iconView.background = pill
                        iconView.imageTintList = android.content.res.ColorStateList.valueOf(fgColor)
                    } else {
                        iconView.setPadding(0, 0, 0, 0)
                        iconView.background = null
                        val primaryColor = com.google.android.material.color.MaterialColors.getColor(
                            ctx, R.attr.colorPrimary, android.graphics.Color.BLUE,
                        )
                        iconView.imageTintList = android.content.res.ColorStateList.valueOf(primaryColor)
                    }
                } else {
                    iconView.visibility = android.view.View.GONE
                }
            }

            // M3E active-count chip: "X / Y on" shown as a rounded pill in the summary row
            // when the category is collapsed and at least one switch is on — gives users a
            // live status at a glance without needing to open the section.
            val summaryView = holder.findViewById(android.R.id.summary) as? android.widget.TextView
            if (summaryView != null) {
                if (!collapsible || expanded || activeCount == 0) {
                    summaryView.visibility = android.view.View.GONE
                    summaryView.background = null
                    summaryView.setPadding(0, 0, 0, 0)
                } else {
                    val chipBgColor = com.google.android.material.color.MaterialColors.getColor(
                        ctx, com.google.android.material.R.attr.colorSecondaryContainer, 0xFFE8DEF8.toInt(),
                    )
                    val chipTextColor = com.google.android.material.color.MaterialColors.getColor(
                        ctx, com.google.android.material.R.attr.colorOnSecondaryContainer, 0xFF1D192B.toInt(),
                    )
                    val chipBg = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        cornerRadius = 100f * density  // fully rounded pill
                        setColor(chipBgColor)
                    }
                    val padH = (7 * density).toInt()
                    val padV = (2 * density).toInt()
                    summaryView.background = chipBg
                    summaryView.setPadding(padH, padV, padH, padV)
                    summaryView.setTextColor(chipTextColor)
                    summaryView.text = if (totalSwitches > 1) "$activeCount / $totalSwitches on" else "$activeCount on"
                    summaryView.visibility = android.view.View.VISIBLE
                }
            }

            val arrow = holder.findViewById(R.id.category_arrow)
            val arrowContainer = holder.findViewById(R.id.category_arrow_container)
            if (!collapsible) {
                arrow?.visibility = android.view.View.GONE
                arrowContainer?.visibility = android.view.View.GONE
                holder.itemView.setOnClickListener(null)
                holder.itemView.isClickable = false
                return
            } else {
                arrow?.visibility = android.view.View.VISIBLE
                arrowContainer?.visibility = android.view.View.VISIBLE
            }

            // Cancel any in-flight animator before snapping to current state on rebind —
            // otherwise a running ViewPropertyAnimator takes priority over the direct rotation setter
            // and can leave the arrow pointing the wrong way for the actual expanded state.
            // ViewPropertyAnimator.cancel() does NOT fire withEndAction, so we must reset isAnimating
            // manually here — otherwise a mid-animation rebind leaves it stuck at true forever.
            arrow?.animate()?.cancel()
            isAnimating = false
            arrow?.rotation = if (expanded) 180f else 0f
            updateExpandedStateDescription(holder.itemView)

            holder.itemView.setOnClickListener {
                // Guard against fast double-taps: a second tap before the arrow finishes rotating
                // would flip `expanded` twice and leave the arrow snapped to the wrong angle.
                if (isAnimating) return@setOnClickListener
                expanded = !expanded
                if (expanded) {
                    af.shizuku.manager.utils.HapticUtils
                        .toggleOn(holder.itemView)
                } else {
                    af.shizuku.manager.utils.HapticUtils
                        .toggleOff(holder.itemView)
                }
                if (shouldPersist()) persistBoolean(expanded)
                // Animate arrow with M3E spring-style motion
                arrow
                    ?.animate()
                    ?.rotation(if (expanded) 180f else 0f)
                    ?.setDuration(
                        af.shizuku.manager.ShizukuSettings
                            .scaledAnimationDuration(260),
                    )?.setInterpolator(android.view.animation.OvershootInterpolator(1.1f))
                    ?.withStartAction { isAnimating = true }
                    ?.withEndAction { isAnimating = false }
                    ?.start()
                // updateChildren() already notifies the adapter per child via Preference.setVisible();
                // an additional notifyChanged() here used to schedule a rebind of this same header
                // ViewHolder mid-animation, which snapped the arrow's rotation back and forth against
                // the running ViewPropertyAnimator and could leave the RecyclerView's sync pass
                // needing a second click to fully settle on the expanded child list.
                updateChildren()
                updateExpandedStateDescription(holder.itemView)
                onExpansionChanged?.invoke(expanded)
            }
        }

        /** Returns (activeCount, totalSwitchCount) for TwoState (switch/checkbox) direct children. */
        private fun countActiveSwitches(): Pair<Int, Int> {
            var active = 0
            var total = 0
            for (i in 0 until preferenceCount) {
                val child = getPreference(i)
                if (child is TwoStatePreference && !unavailableChildKeys.contains(child.key)) {
                    total++
                    if (child.isChecked) active++
                }
            }
            return active to total
        }

        /** The rotating arrow is the only visual cue this header is an expand/collapse toggle -
         *  TalkBack users get neither that affordance nor a state change announcement without this. */
        private fun updateExpandedStateDescription(itemView: android.view.View) {
            ViewCompat.setStateDescription(
                itemView,
                itemView.context.getString(
                    if (expanded) R.string.accessibility_state_expanded else R.string.accessibility_state_collapsed,
                ),
            )
        }

        /**
         * Declare whether a child should participate in the expand/collapse cycle. A child marked
         * unavailable stays hidden regardless of the expanded state. Owners should call this instead
         * of setting the child's [Preference.isVisible] directly, so the collapse toggle does not
         * override the condition.
         */
        fun setChildAvailable(
            key: String,
            available: Boolean,
        ) {
            val changed = if (available) unavailableChildKeys.remove(key) else unavailableChildKeys.add(key)
            if (changed) updateChildren()
        }

        private fun updateChildren() {
            for (i in 0 until preferenceCount) {
                val child = getPreference(i)
                child.isVisible = expanded && !unavailableChildKeys.contains(child.key)
            }
        }

        override fun onAttachedToHierarchy(preferenceManager: PreferenceManager) {
            super.onAttachedToHierarchy(preferenceManager)
            // Restore persisted state if we have a key, otherwise use defaultValue. A non-collapsible
            // category always stays expanded regardless, even if a key is added later.
            if (collapsible && shouldPersist()) {
                expanded = getPersistedBoolean(defaultExpanded)
            }
            updateChildren()
            if (collapsible && !prefListenerRegistered) {
                preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(childChangeListener)
                prefListenerRegistered = true
            }
        }

        override fun onPrepareForRemoval() {
            super.onPrepareForRemoval()
            if (prefListenerRegistered) {
                preferenceManager?.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(childChangeListener)
                prefListenerRegistered = false
            }
        }

        fun expand() {
            if (!expanded) {
                expanded = true
                if (shouldPersist()) persistBoolean(true)
                updateChildren()
                notifyChanged()
            }
        }

        override fun addPreference(preference: Preference): Boolean {
            val result = super.addPreference(preference)
            if (result) {
                preference.isVisible = expanded && !unavailableChildKeys.contains(preference.key)
            }
            return result
        }
    }

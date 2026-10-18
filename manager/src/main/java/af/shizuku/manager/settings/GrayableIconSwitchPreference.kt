package af.shizuku.manager.settings

import af.shizuku.manager.ShizukuSettings
import android.content.Context
import android.util.AttributeSet
import android.widget.ImageView
import androidx.preference.PreferenceViewHolder
import androidx.preference.SwitchPreferenceCompat

open class GrayableIconSwitchPreference
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : SwitchPreferenceCompat(context, attrs) {
        private var lastItemView: java.lang.ref.WeakReference<android.view.View>? = null

        override fun onBindViewHolder(holder: PreferenceViewHolder) {
            super.onBindViewHolder(holder)
            lastItemView = java.lang.ref.WeakReference(holder.itemView)
            holder.itemView
                .findViewById<ImageView>(android.R.id.icon)
                ?.alpha = if (isChecked) 1.0f else 0.38f
        }

        override fun onClick() {
            val willBeChecked = !isChecked
            super.onClick()
            if (isChecked == willBeChecked) {
                lastItemView?.get()?.let { v ->
                    if (isChecked) {
                        af.shizuku.manager.utils.HapticUtils
                            .toggleOn(v)
                    } else {
                        af.shizuku.manager.utils.HapticUtils
                            .toggleOff(v)
                    }
                    // Smooth icon alpha transition on toggle instead of a snap rebind.
                    v
                        .findViewById<ImageView>(android.R.id.icon)
                        ?.animate()
                        ?.alpha(if (isChecked) 1.0f else 0.38f)
                        ?.setDuration(ShizukuSettings.scaledAnimationDuration(200))
                        ?.setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
                        ?.start()
                }
            }
        }
    }

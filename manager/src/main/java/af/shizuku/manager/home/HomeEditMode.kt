package af.shizuku.manager.home

import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.databinding.HomeItemContainerBinding
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.animation.OvershootInterpolator
import androidx.core.graphics.ColorUtils
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView

object HomeEditMode {
    var isActive: Boolean = false
        private set

    var onChanged: (() -> Unit)? = null
    var startDragCallback: ((RecyclerView.ViewHolder) -> Unit)? = null
    var removeCardCallback: ((Long) -> Unit)? = null

    /** Moves the card hosting the given view by [delta] positions (-1 up, +1 down); false if not possible. */
    var moveCardCallback: ((android.view.View, Int) -> Boolean)? = null

    fun enter() {
        if (!isActive) {
            isActive = true
            io.sentry.Sentry.addBreadcrumb("HomeEditMode: enter()")
            onChanged?.invoke()
        }
    }

    fun exit() {
        if (isActive) {
            isActive = false
            onChanged?.invoke()
        }
    }

    fun toggle() {
        if (isActive) exit() else enter()
    }

    /**
     * Lightweight overlay apply for mid-drag use — finds handle/remove views directly by ID and
     * shows them with a spring animation. Backgrounds and tints are already set at bind time, so
     * only visibility + animation needs to be handled here.
     */
    fun applyOverlayDirect(itemView: android.view.View) {
        val removeBtn = itemView.findViewById<android.view.View>(R.id.remove_btn) ?: return
        val dragHandle = itemView.findViewById<android.view.View>(R.id.drag_handle) ?: return
        val wasVisible = removeBtn.isVisible
        if (isActive) {
            removeBtn.isVisible = true
            dragHandle.isVisible = true
        }
        if (isActive && !wasVisible) {
            val dur = ShizukuSettings.scaledAnimationDuration(240)
            val interp = android.view.animation.OvershootInterpolator(1.8f)
            listOf(dragHandle, removeBtn).forEachIndexed { i, view ->
                view.animate().cancel()
                view.scaleX = 0.5f
                view.scaleY = 0.5f
                view.alpha = 0f
                view
                    .animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .setDuration(dur)
                    .setStartDelay(ShizukuSettings.scaledAnimationDuration(i * 40L))
                    .setInterpolator(interp)
                    .start()
            }
        } else if (!isActive && wasVisible) {
            val dur = ShizukuSettings.scaledAnimationDuration(160)
            val interp = android.view.animation.AccelerateInterpolator()
            listOf(dragHandle, removeBtn).forEach { view ->
                view.animate().cancel()
                view
                    .animate()
                    .scaleX(0.5f)
                    .scaleY(0.5f)
                    .alpha(0f)
                    .setDuration(dur)
                    .setInterpolator(interp)
                    .withEndAction {
                        view.isVisible = false
                        view.scaleX = 1f
                        view.scaleY = 1f
                        view.alpha = 1f
                    }.start()
            }
        } else if (!isActive) {
            removeBtn.isVisible = false
            dragHandle.isVisible = false
        }
    }

    /** Toggle drag handle / remove button visibility AND reserve end-padding so
     *  the overlay icons don't sit on top of card title/summary text. */
    fun applyOverlay(binding: HomeItemContainerBinding) {
        io.sentry.Sentry.addBreadcrumb("HomeEditMode: applyOverlay() isActive=$isActive")
        val wasVisible = binding.removeBtn.isVisible
        if (isActive) {
            binding.removeBtn.isVisible = true
            binding.dragHandle.isVisible = true
        }

        // Spring-in when controls first appear; scale-out when they leave.
        if (isActive && !wasVisible) {
            val dur = ShizukuSettings.scaledAnimationDuration(240)
            val interp = OvershootInterpolator(1.8f)
            listOf(binding.dragHandle, binding.removeBtn).forEachIndexed { i, view ->
                view.animate().cancel()
                view.scaleX = 0.5f
                view.scaleY = 0.5f
                view.alpha = 0f
                view
                    .animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .setDuration(dur)
                    .setStartDelay(ShizukuSettings.scaledAnimationDuration(i * 40L))
                    .setInterpolator(interp)
                    .start()
            }
        } else if (!isActive && wasVisible) {
            val dur = ShizukuSettings.scaledAnimationDuration(160)
            val interp = android.view.animation.AccelerateInterpolator()
            listOf(binding.dragHandle, binding.removeBtn).forEach { view ->
                view.animate().cancel()
                view
                    .animate()
                    .scaleX(0.5f)
                    .scaleY(0.5f)
                    .alpha(0f)
                    .setDuration(dur)
                    .setInterpolator(interp)
                    .withEndAction {
                        view.isVisible = false
                        view.scaleX = 1f
                        view.scaleY = 1f
                        view.alpha = 1f
                    }.start()
            }
        } else if (!isActive) {
            binding.removeBtn.isVisible = false
            binding.dragHandle.isVisible = false
        }

        val isHidden = binding.root.tag as? Boolean ?: false
        val ctx = binding.root.context
        val density = binding.root.resources.displayMetrics.density

        fun attrColor(attr: Int): Int {
            val tv = android.util.TypedValue()
            ctx.theme.resolveAttribute(attr, tv, true)
            return tv.data
        }

        val onSurfaceVariant = attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
        val outlineVariant = attrColor(com.google.android.material.R.attr.colorOutlineVariant)

        // Darkened, almost-hollow button (subtle hairline outline + darkened translucent fill)
        // so it stays unobtrusive and never clashes or overwhelms in contrast.
        fun darkenedHollowChip(alphaMultiplier: Float = 1f): GradientDrawable {
            val strokeAlpha = (110 * alphaMultiplier).toInt().coerceIn(0, 255)
            val fillAlpha = (0x33 * alphaMultiplier).toInt().coerceIn(0, 255)
            return GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16f * density
                setStroke((1 * density).toInt().coerceAtLeast(1), ColorUtils.setAlphaComponent(outlineVariant, strokeAlpha))
                setColor(Color.argb(fillAlpha, 0, 0, 0))
            }
        }

        val alphaMultiplier = if (isHidden) 0.6f else 1f
        val chipBg = darkenedHollowChip(alphaMultiplier)
        val fgColor = if (isHidden) ColorUtils.setAlphaComponent(onSurfaceVariant, 130) else onSurfaceVariant
        val fgTintList = ColorStateList.valueOf(fgColor)

        if (isActive && isHidden) {
            binding.cardContent.alpha = 0.45f
            binding.dragHandle.alpha = 0.35f
            binding.removeBtn.setImageResource(R.drawable.ic_visibility_off_24)
            binding.removeBtn.contentDescription = ctx.getString(R.string.accessibility_card_show)
        } else {
            binding.cardContent.alpha = 1.0f
            binding.dragHandle.alpha = 0.85f
            binding.removeBtn.setImageResource(R.drawable.ic_visibility_24)
            binding.removeBtn.contentDescription = ctx.getString(R.string.accessibility_card_hide)
        }

        // Dragging is not possible with TalkBack/switch access; offer move actions on the card and drag handle.
        val a11yDelegate =
            object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(
                    host: android.view.View,
                    info: AccessibilityNodeInfoCompat,
                ) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    if (!isActive) return
                    info.addAction(
                        AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                            R.id.action_move_card_up,
                            host.context.getString(R.string.accessibility_move_card_up),
                        ),
                    )
                    info.addAction(
                        AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                            R.id.action_move_card_down,
                            host.context.getString(R.string.accessibility_move_card_down),
                        ),
                    )
                }

                override fun performAccessibilityAction(
                    host: android.view.View,
                    action: Int,
                    args: android.os.Bundle?,
                ): Boolean {
                    if (isActive) {
                        val delta =
                            when (action) {
                                R.id.action_move_card_up -> -1
                                R.id.action_move_card_down -> 1
                                else -> 0
                            }
                        if (delta != 0) return moveCardCallback?.invoke(binding.root, delta) ?: false
                    }
                    return super.performAccessibilityAction(host, action, args)
                }
            }
        ViewCompat.setAccessibilityDelegate(binding.root, a11yDelegate)
        ViewCompat.setAccessibilityDelegate(binding.dragHandle, a11yDelegate)
        binding.dragHandle.isFocusable = isActive

        binding.removeBtn.background = chipBg
        binding.removeBtn.imageTintList = fgTintList

        // Drag handle: matching darkened hollow chip and on-surface-variant tint
        binding.dragHandle.background = darkenedHollowChip(alphaMultiplier)
        binding.dragHandle.imageTintList = fgTintList
    }
}

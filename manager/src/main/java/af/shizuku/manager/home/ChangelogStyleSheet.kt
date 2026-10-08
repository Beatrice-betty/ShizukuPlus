package af.shizuku.manager.home

import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.utils.HapticUtils
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResult
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

/**
 * One-time prompt for returning users: pick between the Timeline and Classic changelog with a
 * live-looking preview of each. Answering (or dismissing) marks the prompt as done.
 */
class ChangelogStyleSheet : BottomSheetDialogFragment() {
    companion object {
        const val TAG = "ChangelogStyleSheet"
        const val RESULT_KEY = "changelog_style_chosen"
    }

    private var selected = "timeline"

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.sheet_changelog_style, container, false)

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        selected = savedInstanceState?.getString("selected") ?: ShizukuSettings.getChangelogStyle()
        val timeline = view.findViewById<MaterialCardView>(R.id.card_timeline)
        val classic = view.findViewById<MaterialCardView>(R.id.card_classic)

        fun render(animate: Boolean) {
            listOf(timeline to "timeline", classic to "classic").forEach { (card, key) ->
                val on = key == selected
                card.isChecked = on
                card.strokeColor =
                    com.google.android.material.color.MaterialColors.getColor(
                        card,
                        if (on) androidx.appcompat.R.attr.colorPrimary else com.google.android.material.R.attr.colorOutlineVariant,
                    )
                val target = if (on) 1f else 0.96f
                if (animate && ShizukuSettings.isExpressiveAnimationsEnabled()) {
                    card
                        .animate()
                        .scaleX(target)
                        .scaleY(target)
                        .setDuration(ShizukuSettings.scaledAnimationDuration(220))
                        .start()
                } else {
                    card.scaleX = target
                    card.scaleY = target
                }
            }
        }
        render(false)

        timeline.setOnClickListener {
            if (selected != "timeline") HapticUtils.toggleOn(it)
            selected = "timeline"
            render(true)
        }
        classic.setOnClickListener {
            if (selected != "classic") HapticUtils.toggleOn(it)
            selected = "classic"
            render(true)
        }
        view.findViewById<MaterialButton>(R.id.btn_confirm).setOnClickListener {
            HapticUtils.success(it)
            dismiss()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("selected", selected)
    }

    override fun onStart() {
        super.onStart()
        val sheet =
            (dialog as? BottomSheetDialog)
                ?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return
        BottomSheetBehavior.from(sheet).apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
    }

    // Swipe-away or confirm both land here: the current selection is kept and the prompt retires.
    override fun onDestroyView() {
        // A rotation recreates the sheet; only a real dismissal retires the prompt.
        if (activity?.isChangingConfigurations != true) {
            ShizukuSettings.setChangelogStyle(selected)
            ShizukuSettings.setChangelogStylePrompted()
            setFragmentResult(RESULT_KEY, bundleOf("style" to selected))
        }
        super.onDestroyView()
    }
}

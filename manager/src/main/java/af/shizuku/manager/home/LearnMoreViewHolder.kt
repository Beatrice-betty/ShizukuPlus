package af.shizuku.manager.home

import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.databinding.HomeItemContainerBinding
import af.shizuku.manager.databinding.HomeLearnMoreBinding
import af.shizuku.manager.utils.CustomTabsHelper
import af.shizuku.manager.utils.IconStyleHelper
import af.shizuku.manager.utils.MotionUtils.applySpringTouch
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator

class LearnMoreViewHolder(
    private val binding: HomeLearnMoreBinding,
    private val containerBinding: HomeItemContainerBinding,
) : BaseViewHolder<Any?>(containerBinding.root) {
    companion object {
        val CREATOR =
            Creator<Any> { inflater: LayoutInflater, parent: ViewGroup? ->
                val outer = HomeItemContainerBinding.inflate(inflater, parent, false)
                val inner = HomeLearnMoreBinding.inflate(inflater, outer.cardContent, true)
                LearnMoreViewHolder(inner, outer)
            }
    }

    private val originalIcon = binding.icon.drawable

    init {
        containerBinding.root.applySpringTouch()
        containerBinding.root.setOnClickListener { v: View ->
            af.shizuku.manager.knowledgebase.KnowledgebaseHelper.show(
                v.context,
                af.shizuku.manager.knowledgebase.KnowledgebaseRegistry.TOPIC_SECURITY_PRIVACY,
            )
        }
        containerBinding.root.setOnLongClickListener {
            HomeEditMode.enter()
            true
        }
        containerBinding.dragHandle.apply {
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) HomeEditMode.startDragCallback?.invoke(this@LearnMoreViewHolder)
                false
            }
            setOnLongClickListener {
                HomeEditMode.enter()
                true
            }
        }
    }

    override fun onBind() {
        HomeEditMode.applyOverlay(containerBinding)
        IconStyleHelper.applyToCardIcon(binding.icon, originalIcon, "home_learn_more")

        val sentryLimitReached = ShizukuSettings.isSentryLimitReached()
        val sentrySection = itemView.findViewById<View>(R.id.sentry_offline_section)
        sentrySection?.isVisible = sentryLimitReached
        if (sentryLimitReached) {
            val chipReport = itemView.findViewById<com.google.android.material.chip.Chip>(R.id.chip_report_issue)
            chipReport?.setOnClickListener { v ->
                CustomTabsHelper.launchUrlOrCopy(v.context, "https://github.com/thejaustin/ShizukuPlus/issues")
            }
        }
    }
}

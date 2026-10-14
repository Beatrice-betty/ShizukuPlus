package af.shizuku.manager.knowledgebase

import af.shizuku.manager.Helps
import af.shizuku.manager.utils.CustomTabsHelper
import android.content.Context
import android.content.ContextWrapper
import androidx.fragment.app.FragmentActivity

object KnowledgebaseHelper {
    fun show(
        context: Context,
        topicId: String,
    ) {
        val activity = findFragmentActivity(context)
        if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
            KnowledgebaseBottomSheet.show(activity.supportFragmentManager, topicId)
        } else {
            val fallbackUrl = KnowledgebaseRegistry.getTopic(topicId)?.webFallbackUrl ?: Helps.HOME.get()
            CustomTabsHelper.launchUrlOrCopy(context, fallbackUrl)
        }
    }

    private fun findFragmentActivity(context: Context): FragmentActivity? {
        var ctx: Context? = context
        while (ctx != null) {
            if (ctx is FragmentActivity) return ctx
            ctx = (ctx as? ContextWrapper)?.baseContext
        }
        return null
    }
}

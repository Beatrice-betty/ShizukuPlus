package af.shizuku.manager.widget

import af.shizuku.manager.MainActivity
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.home.SystemHubActivity
import af.shizuku.manager.management.ApplicationManagementActivity
import af.shizuku.manager.settings.SettingsActivity
import android.app.Activity
import android.app.ActivityOptions
import android.app.Application
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.FrameLayout
import android.widget.ScrollView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import timber.log.Timber
import kotlin.math.min

/**
 * Optional floating navigation bar (#498).
 *
 * Attached from an [Application.ActivityLifecycleCallbacks] to the content root of the four
 * top-level screens, so it needs no per-activity layout changes (and can't break the
 * AppBarActivity/getLayoutId rules). Off by default; toggled in Settings -> Appearance.
 */
object FloatingNavBar : Application.ActivityLifecycleCallbacks {
    const val KEY_ENABLED = "floating_nav_enabled"

    /** Which tab the user came from, so the new screen can slide the indicator to its own tab. */
    private const val EXTRA_FROM_ITEM = "floating_nav_from_item"

    /** Left-to-right order of the tabs; also decides which way screens slide. */
    private val ORDER = listOf(R.id.nav_home, R.id.nav_apps, R.id.nav_hub, R.id.nav_settings)

    private const val TAG = "FloatingNavBar"
    private const val MAX_WIDTH_DP = 440
    private const val SIDE_MARGIN_DP = 24
    private const val BOTTOM_MARGIN_DP = 12

    private var current: Activity? = null

    // Held strongly: SharedPreferences keeps listeners in a weak map.
    private val prefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_ENABLED) current?.let { sync(it) }
        }

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(this)
        runCatching { ShizukuSettings.getPreferences().registerOnSharedPreferenceChangeListener(prefsListener) }
            .onFailure { Timber.tag(TAG).w(it, "Could not observe floating-nav setting") }
    }

    fun isEnabled(): Boolean = runCatching { ShizukuSettings.getPreferences().getBoolean(KEY_ENABLED, false) }.getOrDefault(false)

    private fun itemFor(activity: Activity): Int? =
        when (activity) {
            is MainActivity -> R.id.nav_home
            is ApplicationManagementActivity -> R.id.nav_apps
            is SystemHubActivity -> R.id.nav_hub
            is SettingsActivity -> R.id.nav_settings
            else -> null
        }

    private fun targetFor(itemId: Int): Class<out Activity>? =
        when (itemId) {
            R.id.nav_home -> MainActivity::class.java
            R.id.nav_apps -> ApplicationManagementActivity::class.java
            R.id.nav_hub -> SystemHubActivity::class.java
            R.id.nav_settings -> SettingsActivity::class.java
            else -> null
        }

    private fun sync(activity: Activity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val existing = content.findViewById<View>(R.id.floating_nav_root)
        val selected = itemFor(activity)
        if (!isEnabled() || selected == null) {
            existing?.let { content.removeView(it) }
            return
        }
        val isNew = existing == null
        val root = existing ?: activity.layoutInflater.inflate(R.layout.floating_nav_bar, content, false).also { content.addView(it) }
        val nav = root.findViewById<BottomNavigationView>(R.id.floating_nav)

        // Arriving from another tab: start on that tab and slide the indicator across, so the bar
        // reads as one persistent control even though each screen owns its own copy.
        val from = activity.intent?.getIntExtra(EXTRA_FROM_ITEM, 0) ?: 0
        activity.intent?.removeExtra(EXTRA_FROM_ITEM)
        nav.setOnItemSelectedListener(null)
        if (isNew && from in ORDER && from != selected) {
            nav.selectedItemId = from
            nav.postDelayed({
                nav.setOnItemSelectedListener(null)
                nav.selectedItemId = selected
                attachSelectListener(activity, nav, selected)
            }, 90)
        } else {
            nav.selectedItemId = selected
            attachSelectListener(activity, nav, selected)
        }
        if (isNew && from == 0) playEntrance(root)
        if (isNew) watchScrolling(content, root)

        layoutBar(activity, root)
        root.bringToFront()
        ViewCompat.requestApplyInsets(root)
        root.post { reserveSpace(content, root, activity is SystemHubActivity) }
    }

    private fun attachSelectListener(
        activity: Activity,
        nav: BottomNavigationView,
        selected: Int,
    ) {
        nav.setOnItemSelectedListener { item ->
            if (item.itemId != selected) {
                af.shizuku.manager.utils.HapticUtils
                    .segmentTick(nav)
                navigate(activity, item.itemId, selected)
            }
            false // the destination screen shows its own selected state
        }
    }

    /** First appearance: rise from below with a short fade. Respects the system animation scale. */
    private fun playEntrance(root: View) {
        val rise = 56 * root.resources.displayMetrics.density
        root.alpha = 0f
        root.translationY = rise
        root
            .animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(320)
            .setInterpolator(android.view.animation.PathInterpolator(0.05f, 0.7f, 0.1f, 1f))
            .start()
    }

    /** Hides the bar while a list scrolls down and brings it back on scroll up. */
    private fun watchScrolling(
        content: ViewGroup,
        bar: View,
    ) {
        content.post {
            findRecyclerView(content)?.addOnScrollListener(
                object : RecyclerView.OnScrollListener() {
                    private var hidden = false

                    override fun onScrolled(
                        rv: RecyclerView,
                        dx: Int,
                        dy: Int,
                    ) {
                        if (dy > 12 && !hidden) {
                            hidden = true
                            slideBar(bar, hide = true)
                        } else if (dy < -12 && hidden) {
                            hidden = false
                            slideBar(bar, hide = false)
                        }
                    }
                },
            )
        }
    }

    private fun slideBar(
        bar: View,
        hide: Boolean,
    ) {
        val distance = bar.height + (bar.layoutParams as FrameLayout.LayoutParams).bottomMargin
        bar
            .animate()
            .translationY(if (hide) distance.toFloat() else 0f)
            .setDuration(if (hide) 180 else 240)
            .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f))
            .start()
    }

    private fun findRecyclerView(view: View): RecyclerView? {
        if (view is RecyclerView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findRecyclerView(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    private fun layoutBar(
        activity: Activity,
        root: View,
    ) {
        val density = activity.resources.displayMetrics.density
        val screenWidth = activity.resources.displayMetrics.widthPixels
        val width = min((MAX_WIDTH_DP * density).toInt(), screenWidth - (2 * SIDE_MARGIN_DP * density).toInt())
        val lp = root.layoutParams as FrameLayout.LayoutParams
        lp.width = width
        lp.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
        root.layoutParams = lp

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            // Read the root insets: a parent may already have consumed the ones dispatched to us.
            val rootInsets = ViewCompat.getRootWindowInsets(v) ?: insets
            val bottom = rootInsets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            val imeVisible = rootInsets.isVisible(WindowInsetsCompat.Type.ime())
            val params = v.layoutParams as FrameLayout.LayoutParams
            val margin = bottom + (BOTTOM_MARGIN_DP * density).toInt()
            if (params.bottomMargin != margin) {
                params.bottomMargin = margin
                v.layoutParams = params
            }
            v.visibility = if (imeVisible) View.GONE else View.VISIBLE
            insets
        }
    }

    private fun navigate(
        activity: Activity,
        itemId: Int,
        fromItem: Int,
    ) {
        val target = targetFor(itemId) ?: return
        val intent = Intent(activity, target).putExtra(EXTRA_FROM_ITEM, fromItem)
        // Slide towards the tab that was tapped: screens to the right enter from the right.
        val forward = ORDER.indexOf(itemId) > ORDER.indexOf(fromItem)
        val options =
            ActivityOptions.makeCustomAnimation(
                activity,
                if (forward) R.anim.floating_nav_enter_forward else R.anim.floating_nav_enter_back,
                if (forward) R.anim.floating_nav_exit_forward else R.anim.floating_nav_exit_back,
            )
        if (itemId == R.id.nav_home) {
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        } else {
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        runCatching {
            activity.startActivity(intent, options.toBundle())
        }.onFailure {
            Timber.tag(TAG).w(it, "Floating nav navigation failed")
            activity.startActivity(intent)
        }
    }

    /**
     * Lets lists scroll their last row clear of the bar. Scrollables already padded at least that
     * much (the settings list reserves its own clearance) are left alone.
     */
    private fun reserveSpace(
        content: ViewGroup,
        bar: View,
        padComposeRoot: Boolean,
    ) {
        if (bar.height == 0) return
        val needed = bar.height + (bar.layoutParams as FrameLayout.LayoutParams).bottomMargin

        fun visit(view: View) {
            if (view === bar) return
            val scrollable = view is RecyclerView || view is NestedScrollView || view is ScrollView || view is AbsListView
            val composeRoot = padComposeRoot && view is androidx.compose.ui.platform.AbstractComposeView
            if ((scrollable || composeRoot) && view.paddingBottom < needed) {
                view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, needed)
                if (view is ViewGroup) view.clipToPadding = false
            }
            if (!scrollable && view is ViewGroup) {
                for (i in 0 until view.childCount) visit(view.getChildAt(i))
            }
        }
        visit(content)
    }

    override fun onActivityResumed(activity: Activity) {
        current = activity
        sync(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (current === activity) current = null
    }

    override fun onActivityCreated(
        activity: Activity,
        savedInstanceState: Bundle?,
    ) = Unit

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(
        activity: Activity,
        outState: Bundle,
    ) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}

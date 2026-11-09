package com.example.launcherapp.ui.activities

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.provider.AlarmClock
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.example.launcherapp.LauncherAppEntry
import com.example.launcherapp.R
import com.example.launcherapp.databinding.ActivityLauncherHomeBinding
import com.example.launcherapp.ui.icons.IconShaper
import com.example.launcherapp.ui.icons.IosIconProvider
import com.example.launcherapp.ui.views.HomeCellLayout
import kotlin.concurrent.thread

/** Grid geometry shared by the activity (page chunking) and the pager adapter (cell placement). */
private object HomeGrid {
    const val COLUMNS = 4
    const val ROWS = 6
    const val WIDGET_SPAN = 2

    const val PAGE_CAPACITY = COLUMNS * ROWS                                   // 24
    const val FIRST_PAGE_CAPACITY = PAGE_CAPACITY - WIDGET_SPAN * WIDGET_SPAN  // 20
}

class LauncherHomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLauncherHomeBinding
    private lateinit var iosIcons: IosIconProvider
    private lateinit var iconShaper: IconShaper
    private val restoreSearchPill = Runnable { showSearchPill(true) }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Transparent status + navigation bars with white icons, content drawn edge to edge.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        binding = ActivityLauncherHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets()

        iosIcons = IosIconProvider(this)
        iconShaper = IconShaper(this)
        loadHomeInBackground()

        // A launcher must never close on Back - jump to the first page instead.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                binding.homePager.setCurrentItem(0, true)
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Pressing Home while already on the home screen returns to page 1, like iOS.
        if (intent.action == Intent.ACTION_MAIN) binding.homePager.setCurrentItem(0, true)
    }

    /**
     * Loading + reshaping ~100 icons takes a moment, so it runs off the main thread;
     * the wallpaper shows immediately and the grid + dock appear once ready.
     */
    private fun loadHomeInBackground() {
        thread(name = "launcher-load-apps") {
            val pages = chunkIntoPages(loadLaunchableApps())
            val dockApps = loadDockApps()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                binding.homePager.adapter = HomePagerAdapter(pages, ::launchApp, ::openClockApp)
                setupPageIndicator(pages.size)
                showDock(dockApps)
            }
        }
    }

    /**
     * Wallpaper stays full-bleed; only the content is pushed out from under the system bars.
     * Insets are consumed here so no child view can add the status bar height a second time.
     */
    private fun applySystemBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.homeContent) { view, insets ->
            val status = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val density = resources.displayMetrics.density
            Log.d(TAG, "statusBar top=${status.top / density}dp, navBar bottom=${nav.bottom / density}dp")
            view.updatePadding(left = nav.left, top = status.top, right = nav.right, bottom = nav.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    /** iOS replacement icon if we have one for this app, otherwise the app's own icon - always squircle-shaped. */
    private fun homeIcon(packageName: String, original: () -> Drawable): Drawable =
        iconShaper.shape(iosIcons.iconFor(packageName) ?: original())

    private fun loadLaunchableApps(): List<LauncherAppEntry> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(intent, 0)
            .map { info ->
                val packageName = info.activityInfo.packageName
                LauncherAppEntry(
                    label = info.loadLabel(packageManager).toString(),
                    icon = homeIcon(packageName) { info.loadIcon(packageManager) },
                    packageName = packageName,
                    activityName = info.activityInfo.name
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    private fun chunkIntoPages(apps: List<LauncherAppEntry>): List<List<LauncherAppEntry>> {
        if (apps.isEmpty()) return listOf(emptyList())
        val pages = mutableListOf<List<LauncherAppEntry>>()
        var index = 0
        while (index < apps.size) {
            val capacity = if (pages.isEmpty()) HomeGrid.FIRST_PAGE_CAPACITY else HomeGrid.PAGE_CAPACITY
            val end = minOf(index + capacity, apps.size)
            pages.add(apps.subList(index, end))
            index = end
        }
        return pages
    }

    /**
     * iOS behaviour: the "Search" pill is shown while idle; while the user swipes between pages
     * it cross-fades into the page dots, then fades back shortly after the swipe settles.
     */
    private fun setupPageIndicator(pageCount: Int) {
        val indicator = binding.pageIndicator
        indicator.removeAllViews()
        indicator.alpha = 0f
        if (pageCount <= 1) return

        val density = resources.displayMetrics.density
        val dots = (0 until pageCount).map { index ->
            View(this).apply {
                val size = (7 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginStart = (4.5f * density).toInt()
                    marginEnd = (4.5f * density).toInt()
                }
                setBackgroundResource(R.drawable.dot_shape)
                alpha = if (index == 0) 1f else 0.35f
            }
        }
        dots.forEach(indicator::addView)

        binding.homePager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                dots.forEachIndexed { index, dot -> dot.alpha = if (index == position) 1f else 0.35f }
            }

            override fun onPageScrollStateChanged(state: Int) {
                binding.root.removeCallbacks(restoreSearchPill)
                if (state == ViewPager2.SCROLL_STATE_IDLE) {
                    binding.root.postDelayed(restoreSearchPill, 700)
                } else {
                    showSearchPill(false)
                }
            }
        })
    }

    private fun showSearchPill(show: Boolean) {
        binding.searchPill.animate().alpha(if (show) 1f else 0f).setDuration(160).start()
        binding.pageIndicator.animate().alpha(if (show) 0f else 1f).setDuration(160).start()
    }

    private fun loadDockApps(): List<LauncherAppEntry> = listOfNotNull(
        resolveForIntent(Intent(Intent.ACTION_DIAL)),
        resolveForCategory(Intent.CATEGORY_APP_BROWSER),
        resolveForCategory(Intent.CATEGORY_APP_MESSAGING),
        resolveForCategory(Intent.CATEGORY_APP_MUSIC)
    )

    private fun showDock(dockApps: List<LauncherAppEntry>) {
        binding.dock.removeAllViews()
        dockApps.forEach { app ->
            // Each slot is a weighted FrameLayout (see item_dock_app.xml), so the 60dp icons
            // are spread evenly without being stretched.
            val slot = layoutInflater.inflate(R.layout.item_dock_app, binding.dock, false)
            slot.findViewById<ImageView>(R.id.dockAppIcon).apply {
                setImageDrawable(app.icon)
                contentDescription = app.label
                setOnClickListener { launchApp(app) }
            }
            binding.dock.addView(slot)
        }
    }

    /**
     * Resolves the default handler for [intent], but reports the resolved app's own
     * launcher icon/label rather than the matched activity's icon - the activity that
     * answers e.g. ACTION_DIAL is often an internal component with a plain/generic icon,
     * while the app's launcher icon is the one users actually recognize.
     *
     * If the user never picked a default, Android resolves to its own "Open with" chooser
     * (package "android"); in that case the first real handler is used instead, so the dock
     * never shows the generic system icon.
     */
    private fun resolveForIntent(intent: Intent): LauncherAppEntry? {
        val resolved = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) ?: return null
        val activityInfo = resolved.activityInfo.takeIf { it.packageName != SYSTEM_PACKAGE }
            ?: packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                .firstOrNull { it.activityInfo.packageName != SYSTEM_PACKAGE }
                ?.activityInfo
            ?: return null

        val packageName = activityInfo.packageName
        val appInfo = runCatching { packageManager.getApplicationInfo(packageName, 0) }.getOrNull()
            ?: return null
        return LauncherAppEntry(
            label = packageManager.getApplicationLabel(appInfo).toString(),
            icon = homeIcon(packageName) { packageManager.getApplicationIcon(appInfo) },
            packageName = packageName,
            activityName = activityInfo.name,
            launchIntent = Intent(intent).setPackage(packageName)
        )
    }

    private fun resolveForCategory(category: String): LauncherAppEntry? =
        resolveForIntent(Intent(Intent.ACTION_MAIN).addCategory(category))

    private fun launchApp(app: LauncherAppEntry) {
        // Copy, so the shared dock intent is never mutated.
        val intent = app.launchIntent?.let { Intent(it) } ?: Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            component = ComponentName(app.packageName, app.activityName)
        }
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        runCatching { startActivity(intent) } // app may have been uninstalled since loading
    }

    private fun openClockApp() {
        runCatching {
            startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    companion object {
        private const val TAG = "LauncherHome"
        private const val SYSTEM_PACKAGE = "android"
    }
}

private class HomePagerAdapter(
    private val pages: List<List<LauncherAppEntry>>,
    private val onAppClick: (LauncherAppEntry) -> Unit,
    private val onClockClick: () -> Unit
) : RecyclerView.Adapter<HomePagerAdapter.PageViewHolder>() {

    class PageViewHolder(val cells: HomeCellLayout) : RecyclerView.ViewHolder(cells)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val cells = LayoutInflater.from(parent.context)
            .inflate(R.layout.page_home, parent, false) as HomeCellLayout
        cells.columns = HomeGrid.COLUMNS
        cells.rows = HomeGrid.ROWS
        return PageViewHolder(cells)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        val cells = holder.cells
        val inflater = LayoutInflater.from(cells.context)
        cells.removeAllViews()

        // Page 1: clock widget occupies the top-left 2x2 block.
        if (position == 0) {
            val widget = inflater.inflate(R.layout.item_home_clock_widget, cells, false)
            widget.setOnClickListener { onClockClick() }
            cells.addView(
                widget,
                HomeCellLayout.LayoutParams(
                    cellX = 0,
                    cellY = 0,
                    spanX = HomeGrid.WIDGET_SPAN,
                    spanY = HomeGrid.WIDGET_SPAN,
                    isWidget = true
                )
            )
        }

        // Apps fill every remaining cell in reading order, so on page 1 the first four apps
        // sit to the right of the widget - same as FaceTime/Calendar/Health/Wallet in Figma.
        val freeCells = cells.freeCells().iterator()
        for (app in pages[position]) {
            if (!freeCells.hasNext()) break
            val (x, y) = freeCells.next()
            val item = inflater.inflate(R.layout.item_home_app, cells, false)
            item.findViewById<ImageView>(R.id.appIcon).setImageDrawable(app.icon)
            item.findViewById<TextView>(R.id.appLabel).text = app.label
            item.contentDescription = app.label
            item.setOnClickListener { onAppClick(app) }
            cells.addView(item, HomeCellLayout.LayoutParams(x, y))
        }
    }

    override fun getItemCount() = pages.size
}
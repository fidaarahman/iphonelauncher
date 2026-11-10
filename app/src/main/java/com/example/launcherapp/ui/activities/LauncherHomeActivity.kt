package com.example.launcherapp.ui.activities

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.HapticFeedbackConstants
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import androidx.recyclerview.widget.GridLayoutManager
import com.example.launcherapp.LauncherAppEntry
import com.example.launcherapp.R
import com.example.launcherapp.databinding.ActivityLauncherHomeBinding
import com.example.launcherapp.utils.AppLaunchTracker
import com.example.launcherapp.utils.DragController
import com.example.launcherapp.ui.adapters.AppLibraryAdapter
import com.example.launcherapp.ui.model.AppCategorizer
import com.example.launcherapp.ui.model.AppLibraryBuilder
import com.example.launcherapp.ui.model.AppLibraryGroup
import com.example.launcherapp.ui.model.HomeGrid
import com.example.launcherapp.Repositories.HomeLayoutRepository
import com.example.launcherapp.ui.model.HomeModel
import com.example.launcherapp.Repositories.FirstScreenPolicy
import com.example.launcherapp.Repositories.defaultHandlerPackage
import com.example.launcherapp.ui.icons.FolderIcon
import com.example.launcherapp.ui.icons.IconShaper
import com.example.launcherapp.ui.icons.IosIconProvider
import com.example.launcherapp.ui.menu.AppContextMenu
import com.example.launcherapp.ui.model.SunCalculator
import com.example.launcherapp.views.HomeCellLayout
import com.example.launcherapp.views.SunArcView
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.WeakHashMap

class LauncherHomeActivity : AppCompatActivity(), DragController.Host {

    private lateinit var binding: ActivityLauncherHomeBinding
    private lateinit var iosIcons: IosIconProvider
    private lateinit var iconShaper: IconShaper
    private lateinit var layoutRepository: HomeLayoutRepository
    private lateinit var dragController: DragController
    private lateinit var pagerAdapter: RecyclerView.Adapter<*>

    // Today View widgets page
    private var widgetPermissionsRequested = false
    private var cachedPhotoUri: Uri? = null
    private val requestWidgetPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refreshWidgetsPageIfAttached() }

    /** Current arrangement: which app is on which page / in the dock. */
    override var model = HomeModel(mutableListOf(), mutableListOf())
        private set

    private var appsByKey: Map<String, LauncherAppEntry> = emptyMap()
    private var appLibraryGroups: List<AppLibraryGroup> = emptyList()

    /** Real apps currently bundled inside the "Google" folder icon - populated each load. */
    private var googleFolderMembers: List<LauncherAppEntry> = emptyList()

    // Touch bookkeeping for drag & drop
    private var lastTouchRawX = 0f
    private var lastTouchRawY = 0f
    private var dragTouchCancelled = false
    private val touchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop }

    // ---- Long-press menu + edit mode ----------------------------------------------------------
    private lateinit var contextMenu: AppContextMenu

    /** iOS edit mode ("Edit Home Screen"): icons wiggle, tap does nothing, drag starts on touch. */
    private var editMode = false

    /**
     * Set while the long-press menu is open AND the finger is still down. If that finger then
     * moves, the menu closes and the icon is dragged instead (iOS behaviour).
     */
    private var menuDragKey: String? = null
    private var menuDragIcon: View? = null
    private var menuDownX = 0f
    private var menuDownY = 0f

    /**
     * Icon wiggle. ONE shared animator rotates every wiggling view via the `rotation` property.
     * (A RotateAnimation left on a view that gets removed keeps being drawn as a "ghost" icon.)
     */
    private val wigglingViews = WeakHashMap<View, Float>() // view -> phase
    private val wiggleAnimator = ValueAnimator.ofFloat(0f, (2 * PI).toFloat()).apply {
        duration = WIGGLE_PERIOD_MS
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { animator ->
            val angle = animator.animatedValue as Float
            wigglingViews.forEach { (view, phase) -> view.rotation = WIGGLE_DEGREES * sin(angle + phase) }
        }
    }

    // Reloading when apps are installed / removed
    private var pendingReload = false
    private var layoutGeneration = 0 // bumped on every user change, so a stale reload can't overwrite it
    private val reloadRunnable = Runnable { loadHomeInBackground() }
    private val launcherApps by lazy { getSystemService(LauncherApps::class.java) }
    private val packageCallback = object : LauncherApps.Callback() {
        override fun onPackageAdded(packageName: String, user: UserHandle) = scheduleReload()
        override fun onPackageRemoved(packageName: String, user: UserHandle) = scheduleReload()
        override fun onPackageChanged(packageName: String, user: UserHandle) = scheduleReload()
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) =
            scheduleReload()
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) =
            scheduleReload()
    }

    private var pageDots: List<View> = emptyList()
    private val restoreSearchPill = Runnable { showSearchPill(true) }

    /**
     * Which view currently represents each app key, per page. This is the single source of
     * truth for reuse/cleanup in [renderPage] - we never rebuild it by scanning the ViewGroup's
     * children, so it can't drift from what's actually attached (the cause of ghost icons).
     */
    private val pageViews = WeakHashMap<HomeCellLayout, LinkedHashMap<String, View>>()
    private fun viewsFor(cells: HomeCellLayout) = pageViews.getOrPut(cells) { LinkedHashMap() }

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
        layoutRepository = HomeLayoutRepository(this)
        dragController = DragController(binding.homePager, binding.dock, binding.dragLayer, this)

        setupPager()
        watchHomePageBottomSpace()

        binding.folderOverlay.setOnClickListener { closeFolder() }
        contextMenu = AppContextMenu(binding.menuLayer, contextMenuActions)
        binding.doneButton.setOnClickListener { setEditMode(false) }
        binding.searchPill.setOnClickListener { startActivity(Intent(this, SearchActivity::class.java)) }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // A launcher must never close on Back.
                if (contextMenu.isShowing) {
                    contextMenu.dismiss()
                } else if (binding.folderOverlay.isVisible) {
                    closeFolder()
                } else if (dragController.isDragging) {
                    dragController.cancelDrag()
                } else if (editMode) {
                    setEditMode(false)
                } else {
                    binding.homePager.setCurrentItem(positionForHomeIndex(0), true)
                }
            }
        })

        launcherApps?.registerCallback(packageCallback, Handler(Looper.getMainLooper()))
        loadHomeInBackground()
    }

    override fun onDestroy() {
        launcherApps?.unregisterCallback(packageCallback)
        wiggleAnimator.cancel()
        binding.root.removeCallbacks(reloadRunnable)
        binding.root.removeCallbacks(restoreSearchPill)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home pressed while already on the home screen.
        if (intent.action == Intent.ACTION_MAIN) {
            clearMenuDrag()
            contextMenu.dismiss(animate = false)
            if (dragController.isDragging) dragController.cancelDrag()
            if (editMode) setEditMode(false) else binding.homePager.setCurrentItem(positionForHomeIndex(0), true)
        }
    }

    /**
     * While an icon is being dragged, every touch goes to the drag controller.
     * The views underneath get one CANCEL so pressed states / long-press timers are cleared.
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        lastTouchRawX = event.rawX
        lastTouchRawY = event.rawY

        // Long-press menu is open and the finger is still down: moving it turns into a drag.
        val pendingKey = menuDragKey
        if (pendingKey != null) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    if (hypot(event.rawX - menuDownX, event.rawY - menuDownY) > touchSlop * MENU_DRAG_SLOP_FACTOR) {
                        val icon = menuDragIcon
                        clearMenuDrag()
                        contextMenu.dismiss(animate = false)
                        if (icon != null) startDrag(pendingKey, icon, event.rawX, event.rawY)
                    }
                }
                // Finger lifted without moving: the menu stays open for the user to pick an item.
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> clearMenuDrag()
            }
        }

        if (::dragController.isInitialized && dragController.isDragging) {
            if (!dragTouchCancelled) {
                val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                super.dispatchTouchEvent(cancel)
                cancel.recycle()
                dragTouchCancelled = true
            }
            dragController.onTouchEvent(event)
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    // ---------------------------------------------------------------------------------------
    // Loading
    // ---------------------------------------------------------------------------------------

    /**
     * Loads apps + icons off the main thread, merges them with the saved layout and shows them.
     * Also used to refresh after an app is installed or removed.
     */
    private fun loadHomeInBackground() {
        val generation = layoutGeneration
        thread(name = "launcher-load-apps") {
            val apps = loadLaunchableApps()
            // Non-essential Google apps are bundled into one "Google" folder icon instead of
            // each getting their own home-screen spot; they still exist normally everywhere else
            // (App Library, Suggestions, search) via the untouched `apps` list below.
            // Dock roles (Phone/Browser/Messages/Music) need the same exemption as the home-grid
            // essentials - otherwise whichever of them resolves to a Google app (e.g. Phone ->
            // com.google.android.dialer on this device) gets folded away, leaving the dock short.
            val dockPackages = defaultDockPackages()
            val essentials = layoutRepository.essentialPackages().toSet() + dockPackages
            val folderMembers = apps.filter {
                it.packageName !in essentials && FirstScreenPolicy.isGoogleFolderMember(it.packageName)
            }
            val folderEntry = folderMembers.takeIf { it.isNotEmpty() }?.let {
                LauncherAppEntry(
                    label = FirstScreenPolicy.GOOGLE_FOLDER_LABEL,
                    icon = iconShaper.shape(FolderIcon.draw(this, it.map { member -> member.icon })),
                    packageName = FirstScreenPolicy.GOOGLE_FOLDER_PACKAGE,
                    activityName = ""
                )
            }
            val folderMemberKeys = folderMembers.mapTo(HashSet()) { it.key }
            val homeGridApps = apps.filterNot { it.key in folderMemberKeys } + listOfNotNull(folderEntry)
            val loaded = layoutRepository.load(homeGridApps, dockPackages)
            val suggested = suggestionsFor(apps)
            val libraryGroups = AppLibraryBuilder.build(apps, suggested)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (dragController.isDragging) {
                    pendingReload = true
                    return@runOnUiThread
                }
                if (generation != layoutGeneration) {
                    // The user moved something while we were loading - load again on top of that.
                    scheduleReload()
                    return@runOnUiThread
                }
                appsByKey = (apps + listOfNotNull(folderEntry)).associateBy { it.key }
                googleFolderMembers = folderMembers
                appLibraryGroups = libraryGroups
                model = loaded
                layoutRepository.save(model)

                pagerAdapter.notifyDataSetChanged()
                // Valid range is 0..libraryPosition() inclusive - this must not clamp someone
                // currently viewing the App Library (or the widgets page).
                if (binding.homePager.currentItem > libraryPosition()) {
                    binding.homePager.setCurrentItem(libraryPosition(), false)
                }
                rebuildPageDots()
                refreshDock()
                hideLoadingOverlay()
            }
        }
    }

    /** Fades out the first-load spinner. A no-op on later reloads, since it's already gone. */
    private fun hideLoadingOverlay() {
        val overlay = binding.loadingOverlay
        if (!overlay.isVisible) return
        overlay.animate()
            .alpha(0f)
            .setDuration(OVERLAY_FADE_MS)
            .withEndAction { overlay.isVisible = false }
            .start()
    }

    private fun scheduleReload() {
        if (::dragController.isInitialized && dragController.isDragging) {
            pendingReload = true
            return
        }
        binding.root.removeCallbacks(reloadRunnable)
        binding.root.postDelayed(reloadRunnable, RELOAD_DELAY_MS)
    }

    private fun loadLaunchableApps(): List<LauncherAppEntry> {
        // Default browser / phone / camera... are looked up once per load, not once per app.
        AppCategorizer.refreshRoles(packageManager)
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(intent, 0)
            .map { info ->
                val packageName = info.activityInfo.packageName
                val label = info.loadLabel(packageManager).toString()
                val flags = info.activityInfo.applicationInfo.flags
                LauncherAppEntry(
                    label = label,
                    icon = homeIcon(packageName) { info.loadIcon(packageManager) },
                    packageName = packageName,
                    activityName = info.activityInfo.name,
                    installTime = installTimeOf(packageName),
                    category = AppCategorizer.categorize(packageName, label, packageManager),
                    isSystemApp = flags and ApplicationInfo.FLAG_SYSTEM != 0 &&
                            flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0
                )
            }
            .sortedBy { it.installTime }
    }

    /**
     * App Library "Suggestions", best source first:
     * 1. Usage Access data (your existing HomeLayoutRepository.suggestedApps - empty if not granted);
     * 2. apps launched most from this launcher (AppLaunchTracker);
     * 3. on a fresh install: the default phone, messages, browser, camera, gallery, settings, clock.
     * 2 and 3 are combined so the tile is full even after only a few launches.
     */
    private fun suggestionsFor(apps: List<LauncherAppEntry>): List<LauncherAppEntry> {
        val ownPackage = packageName
        val fromUsage = layoutRepository.suggestedApps(apps).filter { it.packageName != ownPackage }
        if (fromUsage.isNotEmpty()) return fromUsage.take(SUGGESTIONS_COUNT)
        return (AppLaunchTracker.mostLaunched(apps, SUGGESTIONS_COUNT) + defaultSuggestions(apps))
            .filter { it.packageName != ownPackage }
            .distinctBy { it.key }
            .take(SUGGESTIONS_COUNT)
    }

    /**
     * CATEGORY_APP_BROWSER isn't reliably declared by every browser (especially OEM/third-party
     * ones), so resolving a plain http VIEW intent - which every browser must handle - finds the
     * real default far more consistently.
     */
    private fun browserIntent() = Intent(Intent.ACTION_VIEW, Uri.parse("http://"))

    private fun defaultSuggestions(apps: List<LauncherAppEntry>): List<LauncherAppEntry> = listOf(
        Intent(Intent.ACTION_DIAL),
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING),
        browserIntent(),
        Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_GALLERY),
        Intent(Settings.ACTION_SETTINGS),
        Intent(AlarmClock.ACTION_SHOW_ALARMS)
    )
        .mapNotNull { packageManager.defaultHandlerPackage(it) }
        .distinct()
        .mapNotNull { pkg -> apps.firstOrNull { it.packageName == pkg } }

    private fun installTimeOf(packageName: String): Long =
        runCatching { packageManager.getPackageInfo(packageName, 0).firstInstallTime }.getOrDefault(0L)

    /** Packages used for the dock the very first time (after that the user's own dock is saved). */
    private fun defaultDockPackages(): List<String> = listOfNotNull(
        packageManager.defaultHandlerPackage(Intent(Intent.ACTION_DIAL)),
        packageManager.defaultHandlerPackage(browserIntent()),
        packageManager.defaultHandlerPackage(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING)),
        packageManager.defaultHandlerPackage(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MUSIC))
    )

    /** iOS replacement icon if we have one for this app, otherwise the app's own icon - always squircle-shaped. */
    private fun homeIcon(packageName: String, original: () -> Drawable): Drawable =
        iconShaper.shape(iosIcons.iconFor(packageName) ?: original())

    // ---------------------------------------------------------------------------------------
    // Pages
    // ---------------------------------------------------------------------------------------

    // -----------------------------------------------------------------------------------------
    // Pager position <-> home-page-index translation.
    // Adapter position order: Today View widgets, home[0..N], App Library. The widgets page sits
    // BEFORE home page 1, reached by swiping backward from it - setupPager() sets the initial
    // position to home index 0 so launch still lands on the real home page, not the widgets page.
    // Every other part of the activity (DragController.Host included) speaks in home index;
    // only these functions and the ViewPager2 callbacks below ever touch a raw position.
    // -----------------------------------------------------------------------------------------

    private fun widgetsPosition() = 0
    private fun libraryPosition() = model.pages.size + 1

    private fun positionForHomeIndex(index: Int) = index + 1

    /** Null if [position] is the widgets or App Library page (not a home page at all). */
    private fun homeIndexForPosition(position: Int): Int? = when {
        position == widgetsPosition() || position == libraryPosition() -> null
        else -> position - 1
    }

    private fun viewTypeForPosition(position: Int) = when (position) {
        widgetsPosition() -> HomePagerAdapter.VIEW_TYPE_WIDGETS
        libraryPosition() -> HomePagerAdapter.VIEW_TYPE_LIBRARY
        else -> HomePagerAdapter.VIEW_TYPE_HOME
    }

    private fun setupPager() {
        pagerAdapter = HomePagerAdapter(
            bindPage = { cells, homeIndex -> renderPage(cells, homeIndex, rebuild = true) },
            bindWidgets = ::bindWidgetsPage,
            bindLibrary = ::bindLibraryPage,
            itemCount = { model.pages.size + 2 },
            viewTypeFor = ::viewTypeForPosition,
            homeIndexFor = { position -> homeIndexForPosition(position) ?: 0 }
        )
        binding.homePager.adapter = pagerAdapter
        // Lands on the real home page 1, not the widgets page that now sits before it.
        binding.homePager.setCurrentItem(positionForHomeIndex(0), false)
        // No cross-fade when a page is refreshed - icons slide instead (see HomeCellLayout).
        (binding.homePager.getChildAt(0) as? RecyclerView)?.itemAnimator = null

        binding.homePager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updatePageDots(homeIndexForPosition(position) ?: -1)
                // Neither the App Library nor the widgets page has a page dot, dock, or search
                // pill - the widgets page has its own embedded search bar instead, same as iOS
                // hides the dock on Today View.
                val onLibraryPage = position == libraryPosition()
                val onWidgetsPage = position == widgetsPosition()
                val hideChrome = onLibraryPage || onWidgetsPage
                binding.pageIndicator.isVisible = !hideChrome
                // Faded-out dock/pill are made INVISIBLE too: they sit on top of the library now that
                // the pager is full height, and invisible-but-clickable icons would steal its taps.
                fadeTo(binding.dock, visible = !hideChrome)
                if (hideChrome) {
                    binding.root.removeCallbacks(restoreSearchPill)
                    fadeTo(binding.searchPill, visible = false)
                } else if (!binding.searchPill.isVisible) {
                    fadeTo(binding.searchPill, visible = true)
                }
            }

            override fun onPageScrollStateChanged(state: Int) {
                // App Library / widgets page: no pill/dots to swap.
                if (binding.homePager.currentItem == libraryPosition() || binding.homePager.currentItem == widgetsPosition()) return
                // iOS: the "Search" pill turns into page dots while swiping.
                binding.root.removeCallbacks(restoreSearchPill)
                if (state == ViewPager2.SCROLL_STATE_IDLE) {
                    binding.root.postDelayed(restoreSearchPill, 700)
                } else {
                    showSearchPill(false)
                }
            }
        })
    }

    /**
     * Draws one page from the model (or from the drag preview, which has an empty slot where
     * the dragged icon would land). With [rebuild] = false existing views are reused and only
     * moved (see [viewsFor]), and [animateMoves] slides them smoothly to their new spot instead
     * of just snapping - the iOS rearrange feel, without ever re-deriving "what's on screen" by
     * scanning the ViewGroup.
     */
    /**
     * The pager is full height (so the App Library reaches the bottom of the screen). Home pages
     * get this much bottom padding so their icons stay above the search pill and dock, exactly
     * where they were before. Measured from the real layout, so it's right on every screen size.
     *
     * Seeded with a generous guess (dock + pill + their margins) instead of 0: the real
     * measurement only arrives once the layout-change listener fires, and pages can render
     * before that first firing - with 0 here, that first render would place the bottom row of
     * icons flush against the screen edge, underneath the dock, making them unreachable until a
     * later refresh corrected it.
     */
    private var homePageBottomSpace = 0

    private fun watchHomePageBottomSpace() {
        // Seeded here (resources isn't safely available any earlier, e.g. in a field initializer)
        // so the very first page render already has a safe guess, not 0, before the real
        // measurement below ever gets a chance to fire.
        homePageBottomSpace = dp(DEFAULT_BOTTOM_SPACE_DP)
        binding.homeContent.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val pagerBottom = binding.homeContent.height - binding.homeContent.paddingBottom
            val space = (pagerBottom - binding.searchPill.top + dp(PILL_GAP_DP)).coerceAtLeast(0)
            if (space != homePageBottomSpace) {
                homePageBottomSpace = space
                // Applied on the next frame: changing padding inside a layout pass is not allowed.
                binding.homePager.post { applyHomePageBottomSpaceToAttachedPages() }
            }
        }
    }

    private fun applyHomePageBottomSpaceToAttachedPages() {
        val recycler = binding.homePager.getChildAt(0) as? RecyclerView ?: return
        for (i in 0 until recycler.childCount) {
            val holder = recycler.getChildViewHolder(recycler.getChildAt(i)) as? HomePagerAdapter.PageViewHolder ?: continue
            applyHomePageBottomSpace(holder.cells)
        }
    }

    private fun applyHomePageBottomSpace(cells: HomeCellLayout) {
        if (cells.paddingBottom != homePageBottomSpace) cells.updatePadding(bottom = homePageBottomSpace)
    }

    /** Fades a view in/out and makes it INVISIBLE (not tappable) once hidden. */
    private fun fadeTo(view: View, visible: Boolean) {
        view.animate().cancel()
        if (visible) {
            view.visibility = View.VISIBLE
            view.animate().alpha(1f).setDuration(160).start()
        } else {
            view.animate().alpha(0f).setDuration(160)
                .withEndAction { view.visibility = View.INVISIBLE }
                .start()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun renderPage(cells: HomeCellLayout, page: Int, rebuild: Boolean) {
        applyHomePageBottomSpace(cells)
        val views = viewsFor(cells)
        if (rebuild) {
            cells.removeAllViews()
            views.clear()
        }

        val keys: List<String?> = dragController.previewPage(page) ?: model.pages.getOrNull(page).orEmpty()
        val wanted = HashSet<String>()
        val startPositions: Map<String, Pair<Int, Int>> =
            if (rebuild) emptyMap() else views.mapValues { (_, v) -> v.left to v.top }

        // Page 1: clock widget occupies the top-left 2x2 block.
        if (page == 0) {
            wanted += CLOCK_WIDGET_KEY
            val widget = views[CLOCK_WIDGET_KEY] ?: createClockWidget(cells).also {
                cells.addView(
                    it,
                    HomeCellLayout.LayoutParams(
                        cellX = 0,
                        cellY = 0,
                        spanX = HomeGrid.WIDGET_SPAN,
                        spanY = HomeGrid.WIDGET_SPAN,
                        isWidget = true,
                        key = CLOCK_WIDGET_KEY
                    )
                )
                views[CLOCK_WIDGET_KEY] = it
            }
            applyWiggle(widget)
        }

        // Apps fill the free cells in order; a null key is the empty slot of the drag preview.
        val slots = cells.iconCells()
        keys.take(slots.size).forEachIndexed { index, key ->
            if (key == null) return@forEachIndexed
            val app = appsByKey[key] ?: return@forEachIndexed
            wanted += key
            val (x, y) = slots[index]
            val cell = views[key] ?: createAppCell(cells, app).also {
                cells.addView(it, HomeCellLayout.LayoutParams(x, y, key = key))
                views[key] = it
            }
            (cell.layoutParams as HomeCellLayout.LayoutParams).apply {
                cellX = x
                cellY = y
            }
            applyWiggle(cell)
        }

        // Views we own but no longer want: drop from the layout and the registry together, so
        // the registry can never point at something that isn't actually attached (or vice versa).
        val iterator = views.entries.iterator()
        while (iterator.hasNext()) {
            val (key, view) = iterator.next()
            if (key !in wanted) {
                cells.removeView(view)
                iterator.remove()
            }
        }

        if (startPositions.isNotEmpty()) animateMoves(cells, views, startPositions)
        cells.requestLayout()
    }

    /**
     * Slides each reused icon from where it was to where it's about to land, instead of letting
     * it jump. Classic FLIP technique: let the layout pass happen first (so every view already
     * has its final position), then on the next frame compute the delta from the old position,
     * pre-offset the view by that delta, and animate the offset back to zero.
     */
    private fun animateMoves(cells: HomeCellLayout, views: Map<String, View>, startPositions: Map<String, Pair<Int, Int>>) {
        cells.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                cells.viewTreeObserver.removeOnPreDrawListener(this)
                views.forEach { (key, view) ->
                    val (startLeft, startTop) = startPositions[key] ?: return@forEach
                    val dx = (startLeft - view.left).toFloat()
                    val dy = (startTop - view.top).toFloat()
                    if (dx != 0f || dy != 0f) {
                        view.translationX = dx
                        view.translationY = dy
                        view.animate().translationX(0f).translationY(0f).setDuration(MOVE_DURATION_MS).start()
                    }
                }
                return true
            }
        })
    }

    private fun createAppCell(parent: ViewGroup, app: LauncherAppEntry): View {
        val cell = layoutInflater.inflate(R.layout.item_home_app, parent, false)
        val icon = cell.findViewById<ImageView>(R.id.appIcon)
        icon.setImageDrawable(app.icon)
        cell.findViewById<TextView>(R.id.appLabel).text = app.label
        cell.contentDescription = app.label
        attachIconInteractions(touchTarget = cell, iconView = icon, key = app.key, withMenu = true)
        return cell
    }

    /** Fills the App Library page (last position, past the home pages) with its group tiles. */
    private fun bindLibraryPage(root: ViewGroup) {
        val list = root.findViewById<RecyclerView>(R.id.libraryGroups)
        if (list.layoutManager == null) list.layoutManager = GridLayoutManager(this, 2)
        list.adapter = AppLibraryAdapter(appLibraryGroups) { app -> launchApp(app) }
    }

    // ---------------------------------------------------------------------------------------
    // Today View widgets page
    // ---------------------------------------------------------------------------------------

    private fun bindWidgetsPage(root: ViewGroup) {
        bindWeatherTile(root)
        bindBatteryTile(root)
        bindPhotoTile(root)
        root.findViewById<View>(R.id.wallpapersTile).setOnClickListener { openWallpaperPicker() }
        root.findViewById<View>(R.id.controlCenterTile).setOnClickListener {
            startActivity(Intent(this, ControlCenterActivity::class.java))
        }
        requestWidgetPermissionsIfNeeded()
    }

    /** Re-binds just the permission-gated tiles, after the user answers the permission prompt. */
    private fun refreshWidgetsPageIfAttached() {
        val recycler = binding.homePager.getChildAt(0) as? RecyclerView ?: return
        val holder = recycler.findViewHolderForAdapterPosition(widgetsPosition()) as? HomePagerAdapter.WidgetsViewHolder ?: return
        bindWeatherTile(holder.root)
        bindPhotoTile(holder.root)
    }

    private fun requestWidgetPermissionsIfNeeded() {
        if (widgetPermissionsRequested) return
        widgetPermissionsRequested = true
        val mediaPermission =
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        val needed = listOf(Manifest.permission.ACCESS_COARSE_LOCATION, mediaPermission)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) requestWidgetPermissions.launch(needed.toTypedArray())
    }

    /** Leaves the placeholder dash in place if location access isn't granted. */
    private fun bindWeatherTile(root: ViewGroup) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        val sunsetTime = root.findViewById<TextView>(R.id.weatherSunsetTime)
        val sunriseTime = root.findViewById<TextView>(R.id.weatherSunriseTime)
        val sunArc = root.findViewById<SunArcView?>(R.id.sunArc)
        thread(name = "widgets-weather") {
            val times = lastKnownLocation()?.let { SunCalculator.calculate(it.latitude, it.longitude) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val formatter = SimpleDateFormat("h:mm a", Locale.getDefault())
                sunsetTime.text = times?.sunset?.time?.let(formatter::format) ?: getString(R.string.widget_placeholder_dash)
                sunriseTime.text = times?.sunrise?.time?.let { "Sunrise: ${formatter.format(it)}" } ?: ""
                times?.let { sunArc?.setSunTimes(it.sunrise.timeInMillis, it.sunset.timeInMillis) }
            }
        }
    }

    private fun lastKnownLocation(): Location? {
        val locationManager = getSystemService(LocationManager::class.java) ?: return null
        return listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { locationManager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    private fun bindBatteryTile(root: ViewGroup) {
        val level = getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        root.findViewById<TextView>(R.id.batteryPercent).text =
            if (level in 0..100) "$level%" else getString(R.string.widget_placeholder_dash)
    }

    /** Leaves the tile empty if media access isn't granted. */
    private fun bindPhotoTile(root: ViewGroup) {
        val imageView = root.findViewById<ImageView>(R.id.photoTileImage)
        val mediaPermission =
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, mediaPermission) != PackageManager.PERMISSION_GRANTED) {
            imageView.isVisible = false
            return
        }
        cachedPhotoUri?.let {
            imageView.setImageURI(it)
            imageView.isVisible = true
            return
        }
        thread(name = "widgets-photo") {
            val uri = latestPhotoUri()
            runOnUiThread {
                if (isFinishing || isDestroyed || uri == null) return@runOnUiThread
                cachedPhotoUri = uri
                imageView.setImageURI(uri)
                imageView.isVisible = true
            }
        }
    }

    private fun latestPhotoUri(): Uri? = runCatching {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        contentResolver.query(
            collection,
            arrayOf(MediaStore.Images.Media._ID),
            null,
            null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
            Uri.withAppendedPath(collection, id.toString())
        }
    }.getOrNull()

    private fun openWallpaperPicker() {
        runCatching { startActivity(Intent(Intent.ACTION_SET_WALLPAPER)) }
    }

    private fun createClockWidget(parent: ViewGroup): View =
        layoutInflater.inflate(R.layout.item_home_clock_widget, parent, false).apply {
            setOnClickListener { openClockApp() }
        }

    // ---------------------------------------------------------------------------------------
    // Dock
    // ---------------------------------------------------------------------------------------

    override fun refreshDock() {
        val keys: List<String?> = dragController.previewDock() ?: model.dock
        binding.dock.removeAllViews()
        keys.forEach { key ->
            // Each slot is a weighted FrameLayout (see item_dock_app.xml), so icons are spread evenly.
            val slot = layoutInflater.inflate(R.layout.item_dock_app, binding.dock, false)
            val icon = slot.findViewById<ImageView>(R.id.dockAppIcon)
            val app = key?.let { appsByKey[it] }
            if (app == null) {
                icon.visibility = View.INVISIBLE // empty slot of the drag preview
            } else {
                icon.setImageDrawable(app.icon)
                icon.contentDescription = app.label
                // Dock: long-press still drags straight away (menu for the dock decided later).
                attachIconInteractions(touchTarget = icon, iconView = icon, key = app.key, withMenu = false)
                applyWiggle(icon)
            }
            binding.dock.addView(slot)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Drag & drop
    // ---------------------------------------------------------------------------------------

    /**
     * Normal mode: tap opens the app.
     * - [withMenu] (home grid): long-press opens the iOS menu; keep holding and move -> drag.
     * - dock: long-press drags straight away, as before.
     * Edit mode: tapping does nothing, and touching + moving an icon drags it immediately.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachIconInteractions(touchTarget: View, iconView: View, key: String, withMenu: Boolean) {
        touchTarget.setOnClickListener {
            if (editMode) return@setOnClickListener
            if (key == FirstScreenPolicy.GOOGLE_FOLDER_KEY) openFolder() else appsByKey[key]?.let(::launchApp)
        }
        touchTarget.setOnLongClickListener {
            if (dragController.isDragging) return@setOnLongClickListener false
            if (withMenu && !editMode) {
                showContextMenu(key, touchTarget, iconView)
            } else {
                startDrag(key, iconView, lastTouchRawX, lastTouchRawY)
            }
            true
        }
        touchTarget.setOnTouchListener(EditModeDragStarter(key, iconView))
    }

    private fun showContextMenu(key: String, touchTarget: View, iconView: View) {
        touchTarget.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        // Keep the pager from turning the next finger movement into a page swipe - that movement
        // must start a drag instead (see dispatchTouchEvent).
        touchTarget.parent?.requestDisallowInterceptTouchEvent(true)
        menuDragKey = key
        menuDragIcon = iconView
        menuDownX = lastTouchRawX
        menuDownY = lastTouchRawY
        val isRealApp = key != FirstScreenPolicy.GOOGLE_FOLDER_KEY
        contextMenu.show(
            key, iconView, appsByKey[key]?.icon,
            showAppInfo = isRealApp,
            canDelete = canDelete(key)
        )
    }

    /** Pre-installed system apps, the Google folder and this launcher itself can't be uninstalled here. */
    private fun canDelete(key: String): Boolean {
        val app = appsByKey[key] ?: return false
        return key != FirstScreenPolicy.GOOGLE_FOLDER_KEY && !app.isSystemApp && app.packageName != packageName
    }

    private fun clearMenuDrag() {
        menuDragKey = null
        menuDragIcon = null
    }

    private fun startDrag(key: String, iconView: View, rawX: Float, rawY: Float) {
        dragTouchCancelled = false
        dragController.startDrag(key, iconView, rawX, rawY)
    }

    /** In edit mode, a finger that touches an icon and moves picks it up right away (like iOS). */
    private inner class EditModeDragStarter(
        private val key: String,
        private val iconView: View
    ) : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var started = false

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            if (!editMode || dragController.isDragging) return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    started = false
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!started && hypot(event.rawX - downX, event.rawY - downY) > touchSlop) {
                        started = true
                        view.cancelLongPress()
                        val rawX = event.rawX
                        val rawY = event.rawY
                        // Posted, so the view isn't removed while it's still handling this event.
                        view.post { if (editMode && !dragController.isDragging) startDrag(key, iconView, rawX, rawY) }
                    }
                }
            }
            return false
        }
    }

    // ---------------------------------------------------------------------------------------
    // Long-press menu actions
    // ---------------------------------------------------------------------------------------

    private val contextMenuActions = object : AppContextMenu.Listener {
        // "Remove App" opens its options inside the same menu (AppContextMenu), no dialog.
        override fun onDeleteApp(key: String) {
            appsByKey[key]?.let(::uninstallApp)
        }
        override fun onRemoveFromHome(key: String) = removeFromHomeScreen(key)
        override fun onEditHomeScreen() = setEditMode(true)
        override fun onHideApp(key: String) = comingSoon()
        override fun onSelectApps(key: String) = comingSoon()
        override fun onAppInfo(key: String) {
            val app = appsByKey[key] ?: return
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    private fun comingSoon() = Toast.makeText(this, R.string.coming_soon, Toast.LENGTH_SHORT).show()

    /** Android's own uninstall confirmation. The LauncherApps callback reloads the home screen after. */
    private fun uninstallApp(app: LauncherAppEntry) {
        runCatching {
            startActivity(
                Intent(Intent.ACTION_DELETE, Uri.fromParts("package", app.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** Takes the app off the home screen / dock; it stays installed and in the App Library. */
    private fun removeFromHomeScreen(key: String) {
        if (model.remove(key) == null) return
        layoutRepository.markRemovedFromHome(key)
        layoutGeneration++
        layoutRepository.save(model)

        val currentHome = homeIndexForPosition(binding.homePager.currentItem)
        if (model.trimEmptyPages()) {
            pagerAdapter.notifyDataSetChanged()
            rebuildPageDots()
            if (currentHome != null && currentHome > model.pages.lastIndex) {
                binding.homePager.setCurrentItem(positionForHomeIndex(model.pages.lastIndex), false)
            }
        } else {
            refreshAllPages()
        }
        refreshDock()
    }

    // ---------------------------------------------------------------------------------------
    // Edit mode ("Edit Home Screen") + wiggle
    // ---------------------------------------------------------------------------------------

    private fun setEditMode(enabled: Boolean) {
        if (editMode == enabled) return
        editMode = enabled
        binding.doneButton.isVisible = enabled
        refreshAllPages()
        refreshDock()
        if (!enabled) stopAllWiggles()
    }

    /** iOS "jiggle" - while in edit mode or while an icon is being dragged. */
    private fun applyWiggle(view: View) {
        if (editMode || dragController.isDragging) {
            if (view !in wigglingViews) wigglingViews[view] = Random.nextFloat() * (2 * PI).toFloat()
            if (!wiggleAnimator.isStarted) wiggleAnimator.start()
        } else {
            wigglingViews.remove(view)
            view.rotation = 0f
            if (wigglingViews.isEmpty()) wiggleAnimator.cancel()
        }
    }

    private fun stopAllWiggles() {
        wiggleAnimator.cancel()
        wigglingViews.keys.forEach { it.rotation = 0f }
        wigglingViews.clear()
    }

    // ---------------------------------------------------------------------------------------
    // DragController.Host
    // ---------------------------------------------------------------------------------------

    override fun appIcon(key: String): Drawable? = appsByKey[key]?.icon

    override fun pagePosition(page: Int) = positionForHomeIndex(page)

    override fun pageForPosition(position: Int) = homeIndexForPosition(position)

    override fun pageCells(page: Int): HomeCellLayout? {
        val recycler = binding.homePager.getChildAt(0) as? RecyclerView ?: return null
        return (recycler.findViewHolderForAdapterPosition(positionForHomeIndex(page)) as? HomePagerAdapter.PageViewHolder)?.cells
    }

    override fun refreshPage(page: Int) {
        pageCells(page)?.let { renderPage(it, page, rebuild = false) }
    }

    override fun onPageCountChanged() {
        pagerAdapter.notifyDataSetChanged()
        rebuildPageDots()
    }

    override fun onDragFinished(changed: Boolean) {
        dragTouchCancelled = false
        // Outside edit mode the wiggle only lasts while dragging.
        if (!editMode) stopAllWiggles()
        if (model.trimEmptyPages()) {
            pagerAdapter.notifyDataSetChanged()
            rebuildPageDots()
            val lastPagePosition = positionForHomeIndex(model.pages.lastIndex)
            if (binding.homePager.currentItem > lastPagePosition) {
                binding.homePager.setCurrentItem(lastPagePosition, false)
            }
        } else {
            refreshAllPages()
        }
        refreshDock()

        if (changed) {
            layoutGeneration++
            layoutRepository.save(model)
        }
        if (pendingReload) {
            pendingReload = false
            scheduleReload()
        }
    }

    private fun refreshAllPages() {
        val recycler = binding.homePager.getChildAt(0) as? RecyclerView ?: return
        for (i in 0 until recycler.childCount) {
            val holder = recycler.getChildViewHolder(recycler.getChildAt(i)) as? HomePagerAdapter.PageViewHolder ?: continue
            val homeIndex = homeIndexForPosition(holder.bindingAdapterPosition) ?: continue
            renderPage(holder.cells, homeIndex, rebuild = false)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Search pill / page dots / insets
    // ---------------------------------------------------------------------------------------

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

    private fun rebuildPageDots() {
        val indicator = binding.pageIndicator
        indicator.removeAllViews()
        val pageCount = model.pages.size
        if (pageCount <= 1) {
            pageDots = emptyList()
            return
        }
        val density = resources.displayMetrics.density
        pageDots = (0 until pageCount).map {
            View(this).apply {
                val size = (7 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginStart = (4.5f * density).toInt()
                    marginEnd = (4.5f * density).toInt()
                }
                setBackgroundResource(R.drawable.dot_shape)
            }
        }
        pageDots.forEach(indicator::addView)
        updatePageDots(homeIndexForPosition(binding.homePager.currentItem) ?: -1)
    }

    /** [homeIndex] is a home-page index (see positionForHomeIndex/homeIndexForPosition), not a raw pager position. */
    private fun updatePageDots(homeIndex: Int) {
        pageDots.forEachIndexed { index, dot -> dot.alpha = if (index == homeIndex) 1f else 0.35f }
    }

    private fun showSearchPill(show: Boolean) {
        binding.searchPill.animate().alpha(if (show) 1f else 0f).setDuration(160).start()
        binding.pageIndicator.animate().alpha(if (show) 0f else 1f).setDuration(160).start()
    }

    // ---------------------------------------------------------------------------------------
    // Launching
    // ---------------------------------------------------------------------------------------

    private fun launchApp(app: LauncherAppEntry) {
        // Copy, so a shared intent is never mutated.
        val intent = app.launchIntent?.let { Intent(it) } ?: Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            component = ComponentName(app.packageName, app.activityName)
        }
        // FLAG_ACTIVITY_RESET_TASK_IF_NEEDED resets OTHER apps back to their root screen when
        // tapped from home - but this app's own icon targets SplashActivity, which shares this
        // task's affinity with the already-running LauncherHomeActivity (same package), so that
        // flag would reset the task back to LauncherHomeActivity itself instead of showing
        // SplashActivity - i.e. tapping this app's own icon would silently do nothing.
        intent.flags = if (app.packageName == packageName) {
            Intent.FLAG_ACTIVITY_NEW_TASK
        } else {
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        }
        runCatching {
            startActivity(intent)
            AppLaunchTracker.recordLaunch(app.key) // feeds App Library Suggestions
        } // app may have been uninstalled since loading
    }

    /** Shows the Google folder's apps over a dark scrim. Tapping the scrim or Back closes it. */
    private fun openFolder() {
        if (googleFolderMembers.isEmpty()) return
        binding.folderTitle.text = FirstScreenPolicy.GOOGLE_FOLDER_LABEL
        binding.folderGrid.removeAllViews()
        googleFolderMembers.forEach { app ->
            val cell = layoutInflater.inflate(R.layout.item_folder_app, binding.folderGrid, false)
            cell.findViewById<ImageView>(R.id.appIcon).setImageDrawable(app.icon)
            cell.findViewById<TextView>(R.id.appLabel).text = app.label
            cell.setOnClickListener {
                closeFolder()
                launchApp(app)
            }
            binding.folderGrid.addView(cell)
        }
        binding.folderOverlay.isVisible = true
    }

    private fun closeFolder() {
        binding.folderOverlay.isVisible = false
    }

    /**
     * Opens the real installed Clock app - same one that answers ACTION_SHOW_ALARMS, but
     * launched via its own launcher entry (like tapping its icon) instead of firing that action
     * directly, which can resolve to an unrelated app if more than one handles it and the user
     * never picked a default.
     */
    private fun openClockApp() {
        val clockPackage = packageManager.defaultHandlerPackage(Intent(AlarmClock.ACTION_SHOW_ALARMS))
        val clockApp = appsByKey.values.firstOrNull { it.packageName == clockPackage }
        if (clockApp != null) {
            launchApp(clockApp)
        } else {
            runCatching {
                startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    companion object {
        private const val TAG = "LauncherHome"
        private const val CLOCK_WIDGET_KEY = "#clock_widget"
        private const val RELOAD_DELAY_MS = 400L
        private const val WIGGLE_DEGREES = 1.8f
        private const val WIGGLE_PERIOD_MS = 260L
        // How far the finger must move (x touch slop) after the long-press menu opens to start a drag.
        private const val MENU_DRAG_SLOP_FACTOR = 1.5f
        private const val MOVE_DURATION_MS = 220L
        private const val OVERLAY_FADE_MS = 200L
        private const val SUGGESTIONS_COUNT = 8
        private const val PILL_GAP_DP = 6 // gap between the grid and the search pill (old pager marginBottom)
        // Dock (60dp icons + 16dp top/bottom padding + 8dp bottom margin) + search pill (26dp +
        // 12dp bottom margin) + PILL_GAP_DP, rounded up - a safe guess before the real layout
        // measurement is available.
        private const val DEFAULT_BOTTOM_SPACE_DP = 140
    }
}

/**
 * Pages are, in adapter-position order: home page 0, the Today View widgets page, home pages
 * 1..N (shifted one later than their [HomeModel] index to make room for the widgets page), then
 * the App Library. [homeIndexFor]/[viewTypeFor] do that position <-> home-index translation;
 * callers outside this adapter should go through LauncherHomeActivity's own helpers of the same
 * name rather than assume position == home index.
 */
private class HomePagerAdapter(
    private val bindPage: (HomeCellLayout, homeIndex: Int) -> Unit,
    private val bindWidgets: (ViewGroup) -> Unit,
    private val bindLibrary: (ViewGroup) -> Unit,
    private val itemCount: () -> Int,
    private val viewTypeFor: (position: Int) -> Int,
    private val homeIndexFor: (position: Int) -> Int
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    class PageViewHolder(val cells: HomeCellLayout) : RecyclerView.ViewHolder(cells)
    class WidgetsViewHolder(val root: ViewGroup) : RecyclerView.ViewHolder(root)
    class LibraryViewHolder(val root: ViewGroup) : RecyclerView.ViewHolder(root)

    override fun getItemViewType(position: Int) = viewTypeFor(position)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == VIEW_TYPE_LIBRARY) {
            val root = LayoutInflater.from(parent.context)
                .inflate(R.layout.page_app_library, parent, false) as ViewGroup
            return LibraryViewHolder(root)
        }
        if (viewType == VIEW_TYPE_WIDGETS) {
            val root = LayoutInflater.from(parent.context)
                .inflate(R.layout.page_widgets, parent, false) as ViewGroup
            return WidgetsViewHolder(root)
        }
        val cells = LayoutInflater.from(parent.context)
            .inflate(R.layout.page_home, parent, false) as HomeCellLayout
        cells.columns = HomeGrid.COLUMNS
        cells.rows = HomeGrid.ROWS
        return PageViewHolder(cells)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is PageViewHolder -> bindPage(holder.cells, homeIndexFor(position))
            is WidgetsViewHolder -> bindWidgets(holder.root)
            is LibraryViewHolder -> bindLibrary(holder.root)
        }
    }

    override fun getItemCount() = itemCount()

    companion object {
        const val VIEW_TYPE_HOME = 0
        const val VIEW_TYPE_WIDGETS = 1
        const val VIEW_TYPE_LIBRARY = 2
    }
}
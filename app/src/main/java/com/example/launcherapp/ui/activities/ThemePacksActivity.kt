package com.example.launcherapp.ui.activities

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.GridLayoutManager
import com.example.launcherapp.R
import com.example.launcherapp.Repositories.HomeLayoutRepository
import com.example.launcherapp.Repositories.SavedHomeLayout
import com.example.launcherapp.databinding.ActivityThemePacksBinding
import com.example.launcherapp.ui.adapters.ThemePackAdapter
import com.example.launcherapp.ui.icons.DuotoneIconFilter
import com.example.launcherapp.ui.icons.IconShaper
import com.example.launcherapp.ui.icons.IosIconProvider
import com.example.launcherapp.ui.model.ThemeChip
import com.example.launcherapp.ui.model.ThemePack
import com.example.launcherapp.ui.model.ThemePacks
import com.example.launcherapp.utils.SessionManager
import com.google.gson.Gson
import kotlin.concurrent.thread

class ThemePacksActivity : AppCompatActivity() {

    private lateinit var binding: ActivityThemePacksBinding
    private lateinit var adapter: ThemePackAdapter

    private val originalPackId: String? by lazy { SessionManager.getString(ThemePacks.PREF_KEY, "").ifEmpty { null } }

    /** null = "Default" (no theme). */
    private var selectedPackId: String? = null
    private var selectedChip: ThemeChip = ThemeChip.ALL
    private val chipViews = mutableMapOf<ThemeChip, TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityThemePacksBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets()

        selectedPackId = originalPackId
        selectedChip = ThemePacks.find(originalPackId)?.chip ?: ThemeChip.ALL

        binding.themePacksBack.setOnClickListener { finish() }
        binding.themePacksApply.setOnClickListener { applySelection() }

        adapter = ThemePackAdapter { pack -> onCardSelected(pack) }
        binding.themePackGrid.layoutManager = GridLayoutManager(this, 2)
        binding.themePackGrid.adapter = adapter

        buildChipRow()
        refreshGrid()
        refreshApplyButton()
        loadPreviewIconsInBackground()
    }

    private fun buildChipRow() {
        ThemeChip.entries.forEach { chip ->
            val chipView = TextView(this).apply {
                text = chip.label
                textSize = 13f
                setPadding(dp(16), dp(8), dp(16), dp(8))
                setOnClickListener {
                    selectedChip = chip
                    refreshChips()
                    refreshGrid()
                }
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
            binding.themeChipRow.addView(chipView, params)
            chipViews[chip] = chipView
        }
        refreshChips()
    }

    private fun refreshChips() {
        chipViews.forEach { (chip, view) ->
            if (chip == selectedChip) {
                view.background = GradientDrawable().apply {
                    setColor(chip.swatch)
                    cornerRadius = dp(100).toFloat()
                }
                view.setTextColor(Color.WHITE)
            } else {
                view.setBackgroundResource(R.drawable.bg_theme_chip_unselected)
                view.setTextColor(Color.parseColor("#1C1C1E"))
            }
        }
    }

    private fun refreshGrid() {
        val filtered = if (selectedChip == ThemeChip.ALL) {
            ThemePacks.all
        } else {
            ThemePacks.all.filter { it.chip == selectedChip }
        }
        // The "Default" (no theme) card is shown first under "All".
        adapter.submit(filtered, selectedPackId, showDefault = selectedChip == ThemeChip.ALL)
    }

    /** [pack] = null when the "Default" card is tapped. */
    private fun onCardSelected(pack: ThemePack?) {
        selectedPackId = pack?.id
        refreshGrid()
        refreshApplyButton()
    }

    private fun refreshApplyButton() {
        binding.themePacksApply.isVisible = selectedPackId != originalPackId
    }

    private fun applySelection() {
        val id = selectedPackId
        if (id == null) {
            SessionManager.remove(ThemePacks.PREF_KEY) // "Default": back to the original look
        } else {
            SessionManager.setString(ThemePacks.PREF_KEY, id)
        }
        finish()
    }

    // ---------------------------------------------------------------------------------------
    // Card previews: the user's own first-page + dock icons, recoloured per pack
    // ---------------------------------------------------------------------------------------

    private fun loadPreviewIconsInBackground() {
        val iconSize = dp(PREVIEW_ICON_DP)
        thread(name = "theme-preview-icons") {
            val (gridIcons, dockIcons) = runCatching { userHomeIcons() }.getOrDefault(emptyList<Drawable>() to emptyList())
            // Small copies first: recolouring 14 packs x 12 icons stays quick.
            val grid = gridIcons.map { shrink(it, iconSize) }
            val dock = dockIcons.map { shrink(it, iconSize) }
            val original = ThemePackAdapter.PreviewIcons(grid, dock)
            val themed = ThemePacks.all.associate { pack ->
                pack.id to ThemePackAdapter.PreviewIcons(
                    grid.map { DuotoneIconFilter.apply(it, pack.dockAccent, resources) },
                    dock.map { DuotoneIconFilter.apply(it, pack.dockAccent, resources) }
                )
            }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) adapter.setPreviewIcons(original, themed)
            }
        }
    }

    /**
     * The icons on the user's first home page and in their dock, in their saved order, looking
     * exactly like on the home screen (iOS replacement icon or the app's own, squircle-shaped).
     * The saved layout is only READ here - nothing is changed or saved.
     */
    private fun userHomeIcons(): Pair<List<Drawable>, List<Drawable>> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val infos = packageManager.queryIntentActivities(launcherIntent, 0)
        val byKey = infos.associateBy { "${it.activityInfo.packageName}/${it.activityInfo.name}" }

        val saved = readSavedHomeLayout()
        val gridKeys: List<String>
        val dockKeys: List<String>
        if (saved != null) {
            gridKeys = saved.pages.orEmpty().firstOrNull().orEmpty().filter { it in byKey }.take(ThemePackAdapter.GRID_ICONS)
            dockKeys = saved.dock.orEmpty().filter { it in byKey }.take(ThemePackAdapter.DOCK_ICONS)
        } else {
            // No home layout saved yet (launcher never opened): just the first installed apps.
            val keys = byKey.keys.toList()
            gridKeys = keys.take(ThemePackAdapter.GRID_ICONS)
            dockKeys = keys.drop(ThemePackAdapter.GRID_ICONS).take(ThemePackAdapter.DOCK_ICONS)
        }

        val iosIcons = IosIconProvider(this)
        val iconShaper = IconShaper(this)
        fun iconFor(key: String): Drawable? {
            val info = byKey[key] ?: return null
            val packageName = info.activityInfo.packageName
            return iconShaper.shape(iosIcons.iconFor(packageName) ?: info.loadIcon(packageManager))
        }
        return gridKeys.mapNotNull(::iconFor) to dockKeys.mapNotNull(::iconFor)
    }

    /** Same file + key HomeLayoutRepository saves to (read-only here). */
    private fun readSavedHomeLayout(): SavedHomeLayout? = runCatching {
        getSharedPreferences(HomeLayoutRepository.LAYOUT_PREFS_NAME, MODE_PRIVATE)
            .getString(SAVED_HOME_LAYOUT_KEY, null)
            ?.let { Gson().fromJson(it, SavedHomeLayout::class.java) }
    }.getOrNull()

    private fun shrink(icon: Drawable, size: Int): Drawable {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, size, size)
        icon.draw(Canvas(bitmap))
        return BitmapDrawable(resources, bitmap)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** Pushes the top bar below the status bar - enableEdgeToEdge() draws under it by design, so something has to consume the inset. */
    private fun applySystemBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.themePacksRoot) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }
    }

    private companion object {
        const val PREVIEW_ICON_DP = 24

        /** Must match HomeLayoutRepository's KEY_HOME_LAYOUT ("home_layout"). */
        const val SAVED_HOME_LAYOUT_KEY = "home_layout"
    }
}
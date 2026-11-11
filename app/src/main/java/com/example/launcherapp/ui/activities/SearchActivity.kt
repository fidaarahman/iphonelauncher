package com.example.launcherapp.ui.activities

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.example.launcherapp.LauncherAppEntry
import com.example.launcherapp.R
import com.example.launcherapp.Repositories.defaultHandlerPackage
import com.example.launcherapp.databinding.ActivitySearchBinding
import com.example.launcherapp.ui.icons.DuotoneIconFilter
import com.example.launcherapp.ui.icons.IconShaper
import com.example.launcherapp.ui.icons.IosIconProvider
import com.example.launcherapp.ui.model.ThemePack
import com.example.launcherapp.ui.model.ThemePacks
import com.example.launcherapp.utils.AppLaunchTracker
import com.example.launcherapp.utils.HomeWallpaperStore
import com.example.launcherapp.utils.SessionManager
import kotlin.concurrent.thread


class SearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySearchBinding
    private lateinit var iosIcons: IosIconProvider
    private lateinit var iconShaper: IconShaper
    private var allApps: List<LauncherAppEntry> = emptyList()

    /** Theme pack active when the screen opened (null = Default). */
    private var activeTheme: ThemePack? = null

    /** Ignores an older background load if a newer one started (e.g. quick resume). */
    private var backgroundRequest = 0

    private val requestContactsPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) updateContactsSection(currentQuery())
        }
    private val voiceSearch = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (text != null) binding.searchInput.setText(text)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        // Let Android draw the user's real wallpaper behind this screen (same as the home screen).
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets()

        iosIcons = IosIconProvider(this)
        iconShaper = IconShaper(this)
        activeTheme = currentTheme()

        binding.searchCancel.setOnClickListener { finish() }
        binding.searchMic.setOnClickListener { startVoiceSearch() }
        binding.grantContactsPermission.setOnClickListener {
            requestContactsPermission.launch(Manifest.permission.READ_CONTACTS)
        }

        binding.searchInput.requestFocus()
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                onQueryChanged(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        loadApps()
        updateContactsSection(currentQuery())
    }

    override fun onResume() {
        super.onResume()
        applyBackground()
    }

    // -----------------------------------------------------------------------------------------
    // Background (same rules as the home screen)
    // -----------------------------------------------------------------------------------------

    private fun currentTheme(): ThemePack? =
        ThemePacks.find(SessionManager.getString(ThemePacks.PREF_KEY, "").ifEmpty { null })

    private fun applyBackground() {
        val theme = currentTheme()
        val hasWallpaper = HomeWallpaperStore.hasWallpaper()
        val request = ++backgroundRequest

        if (theme == null && !hasWallpaper) {
            showDeviceWallpaper()
            return
        }
        if (theme != null && !hasWallpaper) {
            showBackground(themeGradient(theme))
            return
        }
        val metrics = resources.displayMetrics
        thread(name = "search-wallpaper") {
            // Same value as in LauncherHomeActivity.updateBackground(), so both screens match.
            val themeAmount = 0.35f
            val bitmap = HomeWallpaperStore.load(this, metrics.widthPixels, metrics.heightPixels)
            val shown = if (bitmap != null && theme != null) {
                DuotoneIconFilter.recolor(bitmap, theme.dockAccent, inPlace = true, amount = themeAmount)
            } else {
                bitmap
            }
            runOnUiThread {
                if (isFinishing || isDestroyed || request != backgroundRequest) return@runOnUiThread
                when {
                    shown != null -> {
                        binding.searchWallpaper.setImageBitmap(shown)
                        binding.searchWallpaper.isVisible = true
                    }
                    theme != null -> showBackground(themeGradient(theme))
                    else -> showDeviceWallpaper()
                }
            }
        }
    }
    private fun showBackground(drawable: Drawable) {
        binding.searchWallpaper.setImageDrawable(drawable)
        binding.searchWallpaper.isVisible = true
    }

    /** The real phone wallpaper shows through (FLAG_SHOW_WALLPAPER) - we just hide our own image. */
    private fun showDeviceWallpaper() {
        binding.searchWallpaper.setImageDrawable(null)
        binding.searchWallpaper.isVisible = false
    }

    /** Same gradient as the home screen (softened the same way). */
    private fun themeGradient(theme: ThemePack) =
        GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(softenBackgroundColor(theme.gradientStart), softenBackgroundColor(theme.gradientEnd))
        )

    /** Keep `strength` the same as in LauncherHomeActivity so both screens match. */
    private fun softenBackgroundColor(color: Int): Int {
        val strength = 0.3f
        return androidx.core.graphics.ColorUtils.blendARGB(color, 0xFFE5E5EA.toInt(), strength)
    }

    /** Pushes content below the status bar / above the nav bar, and above the keyboard once it's shown - edge-to-edge draws under all three by default, so something has to consume the insets. */
    private fun applySystemBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.searchColumn) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.updatePadding(top = bars.top, bottom = maxOf(bars.bottom, ime.bottom))
            insets
        }
    }

    private fun currentQuery() = binding.searchInput.text?.toString()?.trim().orEmpty()

    private fun startVoiceSearch() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        runCatching { voiceSearch.launch(intent) }
    }

    // -----------------------------------------------------------------------------------------
    // Apps
    // -----------------------------------------------------------------------------------------

    /** Cheap stand-in icon for apps we haven't icon-shaped yet - shaping is the slow part of loadApps(). */
    private val placeholderIcon: Drawable by lazy { ColorDrawable(Color.TRANSPARENT) }

    private fun loadApps() {
        thread(name = "search-load-apps") {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved = packageManager.queryIntentActivities(intent, 0)
                .map { info -> info to info.loadLabel(packageManager).toString() }
                .sortedBy { (_, label) -> label.lowercase() }
            val infoByKey = resolved.associate { (info, _) ->
                "${info.activityInfo.packageName}/${info.activityInfo.name}" to info
            }

            // Figure out which apps belong in Suggestions WITHOUT icon-shaping the whole app
            // list first - that's what used to make Suggestions take a visible moment to appear.
            val stubs = resolved.map { (info, label) ->
                LauncherAppEntry(label, placeholderIcon, info.activityInfo.packageName, info.activityInfo.name)
            }
            val suggestedStubs = AppLaunchTracker.mostLaunched(stubs, limit = SUGGESTION_COUNT).ifEmpty { defaultSuggestions(stubs) }
            val suggested = suggestedStubs.mapNotNull { stub -> infoByKey[stub.key]?.let { buildEntry(it, stub.label) } }

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                bindSuggestions(suggested)
            }

            val apps = resolved.map { (info, label) -> buildEntry(info, label) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                allApps = apps
                val query = currentQuery()
                if (query.isNotEmpty()) updateAppResults(query)
            }
        }
    }

    private fun buildEntry(info: ResolveInfo, label: String): LauncherAppEntry {
        val packageName = info.activityInfo.packageName
        val shaped = iconShaper.shape(iosIcons.iconFor(packageName) ?: info.loadIcon(packageManager))
        // Same themed icon as the home screen when a theme pack is active.
        val icon = activeTheme?.let { DuotoneIconFilter.apply(shaped, it.dockAccent, resources) } ?: shaped
        return LauncherAppEntry(
            label = label,
            icon = icon,
            packageName = packageName,
            activityName = info.activityInfo.name
        )
    }

    private fun bindSuggestions(suggested: List<LauncherAppEntry>) {
        binding.suggestedGrid.removeAllViews()
        val cellWidth = (SUGGESTION_CELL_DP * resources.displayMetrics.density).toInt()
        suggested.forEach { app ->
            val cell = layoutInflater.inflate(R.layout.item_home_app, binding.suggestedGrid, false)
            cell.findViewById<ImageView>(R.id.appIcon).setImageDrawable(app.icon)
            cell.findViewById<TextView>(R.id.appLabel).text = app.label
            cell.layoutParams = GridLayout.LayoutParams().apply { width = cellWidth; height = ViewGroup.LayoutParams.WRAP_CONTENT }
            cell.setOnClickListener { launchApp(app) }
            binding.suggestedGrid.addView(cell)
        }
    }

    /** Before this launcher has any usage history to learn from: a handful of common defaults. */
    private fun defaultSuggestions(source: List<LauncherAppEntry>): List<LauncherAppEntry> = listOf(
        Intent(Settings.ACTION_SETTINGS),
        category(Intent.CATEGORY_APP_GALLERY),
        category(Intent.CATEGORY_APP_MAPS),
        category(Intent.CATEGORY_APP_EMAIL),
        Intent(Intent.ACTION_DIAL),
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING),
        Intent(Intent.ACTION_VIEW, Uri.parse("http://")),
        Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
    )
        .mapNotNull { packageManager.defaultHandlerPackage(it) }
        .distinct()
        .mapNotNull { pkg -> source.firstOrNull { it.packageName == pkg } }
        .take(SUGGESTION_COUNT)

    private fun category(category: String) = Intent(Intent.ACTION_MAIN).addCategory(category)

    private fun updateAppResults(query: String) {
        val matches = allApps.filter { it.label.contains(query, ignoreCase = true) }
        binding.appResultsList.removeAllViews()
        matches.forEach { app ->
            val row = layoutInflater.inflate(R.layout.item_search_result, binding.appResultsList, false)
            row.findViewById<ImageView>(R.id.resultIcon).setImageDrawable(app.icon)
            row.findViewById<TextView>(R.id.resultLabel).text = app.label
            row.setOnClickListener { launchApp(app) }
            binding.appResultsList.addView(row)
        }
    }

    private fun launchApp(app: LauncherAppEntry) {
        runCatching {
            startActivity(
                Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    component = ComponentName(app.packageName, app.activityName)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            )
            AppLaunchTracker.recordLaunch(app.key)
        }
        finish()
    }

    // -----------------------------------------------------------------------------------------
    // Contacts
    // -----------------------------------------------------------------------------------------

    private fun hasContactsPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    private fun updateContactsSection(query: String) {
        val granted = hasContactsPermission()
        binding.contactsPermissionGroup.isVisible = !granted
        binding.contactResultsList.isVisible = granted
        if (!granted) return

        if (query.isEmpty()) {
            binding.contactResultsList.removeAllViews()
            return
        }
        thread(name = "search-contacts") {
            val matches = queryContacts(query)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                bindContactResults(matches)
            }
        }
    }

    private data class ContactResult(val id: Long, val name: String)

    private fun queryContacts(query: String): List<ContactResult> = runCatching {
        val results = ArrayList<ContactResult>()
        contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
            "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} LIKE ?",
            arrayOf("%$query%"),
            "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} ASC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
            while (cursor.moveToNext() && results.size < CONTACT_RESULT_LIMIT) {
                results += ContactResult(cursor.getLong(idIndex), cursor.getString(nameIndex) ?: continue)
            }
        }
        results
    }.getOrDefault(emptyList())

    private fun bindContactResults(contacts: List<ContactResult>) {
        binding.contactResultsList.removeAllViews()
        contacts.forEach { contact ->
            val row = layoutInflater.inflate(R.layout.item_search_result, binding.contactResultsList, false)
            row.findViewById<ImageView>(R.id.resultIcon).setImageDrawable(contactIcon())
            row.findViewById<TextView>(R.id.resultLabel).text = contact.name
            row.setOnClickListener {
                val uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_URI, contact.id.toString())
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                finish()
            }
            binding.contactResultsList.addView(row)
        }
    }

    private fun contactIcon() = ContextCompat.getDrawable(this, R.drawable.ic_contacts)!!

    // -----------------------------------------------------------------------------------------
    // Query state
    // -----------------------------------------------------------------------------------------

    private fun onQueryChanged(query: String) {
        val trimmed = query.trim()
        val empty = trimmed.isEmpty()
        binding.suggestionsGroup.isVisible = empty
        binding.appsGroup.isVisible = !empty
        if (!empty) updateAppResults(trimmed)
        updateContactsSection(trimmed)
    }

    private companion object {
        const val SUGGESTION_COUNT = 8
        const val SUGGESTION_CELL_DP = 76
        const val CONTACT_RESULT_LIMIT = 10
    }
}
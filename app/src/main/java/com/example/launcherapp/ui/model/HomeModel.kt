
package com.example.launcherapp.ui.model

/** Grid geometry shared by the model, the activity and the drag controller. */
object HomeGrid {
    const val COLUMNS = 4
    const val ROWS = 6
    const val WIDGET_SPAN = 2
    const val DOCK_CAPACITY = 4

    const val PAGE_CAPACITY = COLUMNS * ROWS                                   // 24
    const val FIRST_PAGE_CAPACITY = PAGE_CAPACITY - WIDGET_SPAN * WIDGET_SPAN  // 20 (clock widget on page 1)

    fun capacity(page: Int) = if (page == 0) FIRST_PAGE_CAPACITY else PAGE_CAPACITY
}

/** Where an app currently lives. */
sealed interface HomeLocation {
    data class Page(val page: Int, val index: Int) : HomeLocation
    data class Dock(val index: Int) : HomeLocation
}

/**
 * The user's home screen arrangement, iOS style:
 * - every page is an ordered list of app keys that fill the grid from the top-left
 *   (after the clock widget on page 1), so there are never random gaps;
 * - the dock is an ordered list of up to 4 app keys;
 * - every app appears exactly once - either on a page or in the dock.
 */
class HomeModel(
    val pages: MutableList<MutableList<String>>,
    val dock: MutableList<String>
) {

    init {
        if (pages.isEmpty()) pages.add(mutableListOf())
        normalize()
    }

    fun locate(key: String): HomeLocation? {
        val dockIndex = dock.indexOf(key)
        if (dockIndex >= 0) return HomeLocation.Dock(dockIndex)
        pages.forEachIndexed { page, keys ->
            val index = keys.indexOf(key)
            if (index >= 0) return HomeLocation.Page(page, index)
        }
        return null
    }

    /** Removes the app and returns where it was (so a cancelled drag can put it back). */
    fun remove(key: String): HomeLocation? {
        val location = locate(key) ?: return null
        when (location) {
            is HomeLocation.Page -> pages[location.page].removeAt(location.index)
            is HomeLocation.Dock -> dock.removeAt(location.index)
        }
        return location
    }

    /**
     * Inserts into [page] at [index]. If the page is now over capacity, its last app moves to
     * the start of the next page (creating pages as needed) - the same thing iOS does.
     */
    fun insertIntoPage(page: Int, index: Int, key: String) {
        while (pages.size <= page) pages.add(mutableListOf())
        val keys = pages[page]
        keys.add(index.coerceIn(0, keys.size), key)
        normalize()
    }

    fun canInsertIntoDock() = dock.size < HomeGrid.DOCK_CAPACITY

    fun insertIntoDock(index: Int, key: String): Boolean {
        if (!canInsertIntoDock()) return false
        dock.add(index.coerceIn(0, dock.size), key)
        return true
    }

    /** Puts an app back where it was before a drag started. */
    fun restore(location: HomeLocation, key: String) {
        when (location) {
            is HomeLocation.Page -> insertIntoPage(location.page, location.index, key)
            is HomeLocation.Dock -> if (!insertIntoDock(location.index, key)) append(key)
        }
    }

    /** Adds an app after the last app (new page if the last one is full). */
    fun append(key: String) {
        val last = pages.lastIndex
        if (pages[last].size < HomeGrid.capacity(last)) pages[last].add(key) else pages.add(mutableListOf(key))
    }

    /** Removes empty pages; page 1 always stays. Returns true if anything was removed. */
    fun trimEmptyPages(): Boolean {
        var removed = false
        for (page in pages.lastIndex downTo 1) {
            if (pages[page].isEmpty()) {
                pages.removeAt(page)
                removed = true
            }
        }
        return removed
    }

    /** Pushes overflow from any over-full page onto the following page. */
    private fun normalize() {
        var page = 0
        while (page < pages.size) {
            while (pages[page].size > HomeGrid.capacity(page)) {
                val overflow = pages[page].removeAt(pages[page].lastIndex)
                if (page + 1 >= pages.size) pages.add(mutableListOf())
                pages[page + 1].add(0, overflow)
            }
            page++
        }
    }
}
package com.example.launcherapp.ui.adapters

import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.example.launcherapp.R
import com.example.launcherapp.ui.model.ThemePack


class ThemePackAdapter(private val onSelect: (ThemePack?) -> Unit) :
    RecyclerView.Adapter<ThemePackAdapter.ViewHolder>() {

    /** The icons drawn on one card: up to 8 for the grid, up to 4 for the dock. */
    data class PreviewIcons(val grid: List<Drawable>, val dock: List<Drawable>)

    /** null = the "Default" card. */
    private var items: List<ThemePack?> = emptyList()
    private var selectedId: String? = null
    private var originalIcons: PreviewIcons? = null
    private var themedIcons: Map<String, PreviewIcons> = emptyMap()

    /** [selected] = the selected pack id, or null when "Default" is selected. */
    fun submit(packs: List<ThemePack>, selected: String?, showDefault: Boolean) {
        items = (if (showDefault) listOf<ThemePack?>(null) else emptyList()) + packs
        selectedId = selected
        notifyDataSetChanged()
    }

    /** [original] for the Default card; [themed] keyed by pack id. */
    fun setPreviewIcons(original: PreviewIcons, themed: Map<String, PreviewIcons>) {
        originalIcons = original
        themedIcons = themed
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cardRoot: View = view.findViewById(R.id.cardRoot)
        val iconGrid: GridLayout = view.findViewById(R.id.cardIconGrid)
        val dock: LinearLayout = view.findViewById(R.id.cardDock)
        val selectionRing: View = view.findViewById(R.id.selectionRing)
        val label: TextView = view.findViewById(R.id.cardLabel)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_theme_pack_card, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val pack = items[position]
        val density = holder.itemView.resources.displayMetrics.density

        val colors = if (pack == null) DEFAULT_GRADIENT else intArrayOf(pack.gradientStart, pack.gradientEnd)
        holder.cardRoot.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors)
            .apply { cornerRadius = 20f * density }

        // Dock stays light glass, same as on the real home screen.
        holder.dock.backgroundTintList = null
        holder.selectionRing.isVisible = pack?.id == selectedId
        holder.label.isVisible = pack == null
        holder.cardRoot.setOnClickListener { onSelect(pack) }

        ensureIconSlots(holder, density)
        val icons = if (pack == null) originalIcons else themedIcons[pack.id]
        bindSlots(holder.iconGrid, icons?.grid)
        bindSlots(holder.dock, icons?.dock)
    }

    /** Creates the 8 grid + 4 dock icon views once per recycled card. */
    private fun ensureIconSlots(holder: ViewHolder, density: Float) {
        if (holder.iconGrid.childCount == 0) {
            val iconSize = (22 * density).toInt()
            repeat(GRID_ICONS) {
                holder.iconGrid.addView(slotView(holder.itemView, GridLayout.LayoutParams().apply {
                    width = iconSize
                    height = iconSize
                    setMargins(4, 4, 4, 4)
                }))
            }
        }
        if (holder.dock.childCount == 0) {
            val dockIconSize = (18 * density).toInt()
            val margin = (4 * density).toInt()
            repeat(DOCK_ICONS) {
                holder.dock.addView(slotView(holder.itemView, LinearLayout.LayoutParams(dockIconSize, dockIconSize).apply {
                    marginStart = margin
                    marginEnd = margin
                }))
            }
        }
    }

    private fun slotView(parent: View, params: ViewGroup.LayoutParams) = ImageView(parent.context).apply {
        layoutParams = params
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    /** Real icon when available, otherwise the placeholder square. */
    private fun bindSlots(container: ViewGroup, icons: List<Drawable>?) {
        for (i in 0 until container.childCount) {
            val slot = container.getChildAt(i) as? ImageView ?: continue
            val icon = icons?.getOrNull(i)
            if (icon != null) {
                slot.setImageDrawable(icon)
                slot.background = null
                slot.isVisible = true
            } else if (icons != null) {
                // Icons loaded but the user has fewer apps on that page: leave the spot empty.
                slot.setImageDrawable(null)
                slot.background = null
                slot.isVisible = true
            } else {
                slot.setImageDrawable(null)
                slot.setBackgroundResource(R.drawable.bg_theme_card_icon_dot)
                slot.isVisible = true
            }
        }
    }

    companion object {
        const val GRID_ICONS = 8
        const val DOCK_ICONS = 4
        private val DEFAULT_GRADIENT = intArrayOf(0xFF3A3A3C.toInt(), 0xFF1C1C1E.toInt())
    }
}
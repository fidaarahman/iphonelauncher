package com.example.launcherapp.ui.adapters

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.launcherapp.LauncherAppEntry
import com.example.launcherapp.R
import com.example.launcherapp.ui.model.AppLibraryGroup

/**
 * App Library tiles, like iOS:
 * - up to 4 apps: one large icon per slot;
 * - more than 4: 3 large icons + a 2x2 cluster of mini icons (apps 4-7) in the 4th slot.
 *
 * Large icons launch their app. Mini icons: if [onOpenGroup] is set, tapping the cluster opens
 * the whole group (folder overlay); until that exists, each mini icon launches its own app.
 */
class AppLibraryAdapter(
    private val groups: List<AppLibraryGroup>,
    private val onOpenGroup: ((AppLibraryGroup) -> Unit)? = null,
    private val onAppClick: (LauncherAppEntry) -> Unit
) : RecyclerView.Adapter<AppLibraryAdapter.TileViewHolder>() {

    class TileViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val slots: List<FrameLayout> = listOf(
            view.findViewById(R.id.slot0),
            view.findViewById(R.id.slot1),
            view.findViewById(R.id.slot2),
            view.findViewById(R.id.slot3)
        )
        val label: TextView = view.findViewById(R.id.groupLabel)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TileViewHolder =
        TileViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_app_library_tile, parent, false)
        )

    override fun onBindViewHolder(holder: TileViewHolder, position: Int) {
        val group = groups[position]
        val apps = group.apps
        holder.label.text = group.title
        holder.slots.forEach { it.removeAllViews() }

        val largeCount = if (apps.size <= SLOTS) apps.size else SLOTS - 1
        for (i in 0 until largeCount) {
            holder.slots[i].addView(largeIcon(holder.slots[i], apps[i]))
        }
        if (apps.size > SLOTS) {
            val minis = apps.subList(SLOTS - 1, minOf(apps.size, SLOTS - 1 + MINI_COUNT))
            holder.slots[SLOTS - 1].addView(miniCluster(holder.slots[SLOTS - 1], group, minis))
        }
    }

    override fun getItemCount() = groups.size

    private fun largeIcon(parent: ViewGroup, app: LauncherAppEntry): View =
        iconView(parent, app).apply {
            setOnClickListener { onAppClick(app) }
        }

    /** 2x2 grid of mini icons filling the 4th slot; 2dp padding each = 4dp gaps. */
    private fun miniCluster(parent: ViewGroup, group: AppLibraryGroup, minis: List<LauncherAppEntry>): View {
        val context = parent.context
        val cluster = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        }
        for (row in 0 until 2) {
            val rowLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            }
            for (column in 0 until 2) {
                val cell = FrameLayout(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, MATCH, 1f)
                    val pad = (2 * resources.displayMetrics.density).toInt()
                    setPadding(pad, pad, pad, pad)
                }
                minis.getOrNull(row * 2 + column)?.let { app ->
                    cell.addView(iconView(cell, app).apply {
                        if (onOpenGroup == null) setOnClickListener { onAppClick(app) }
                    })
                }
                rowLayout.addView(cell)
            }
            cluster.addView(rowLayout)
        }
        onOpenGroup?.let { open -> cluster.setOnClickListener { open(group) } }
        return cluster
    }

    private fun iconView(parent: ViewGroup, app: LauncherAppEntry): ImageView =
        ImageView(parent.context).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            scaleType = ImageView.ScaleType.FIT_CENTER
            // Own copy: the same drawable is also shown on the home screen / in other tiles.
            setImageDrawable(copyOf(app.icon))
            contentDescription = app.label
        }

    private fun copyOf(icon: Drawable): Drawable = icon.constantState?.newDrawable()?.mutate() ?: icon

    private companion object {
        const val SLOTS = 4
        const val MINI_COUNT = 4
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    }
}
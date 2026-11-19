package com.xgimiru.player.ui

import android.content.Context
import android.graphics.Outline
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.xgimiru.player.R

/**
 * The left list of every Lumen Play screen: brand, Continue, History, every USB stick and the device storage
 * by name, network folders, Add a folder, and Settings at the bottom. The open place keeps a soft fill, the
 * focused row is filled blue. OK opens a place, focus alone never switches the screen.
 */
class Rail(ctx: Context, private val onOpen: (String) -> Unit) : FrameLayout(ctx) {
    class Item(val id: String, val label: String, val icon: Int, val sub: String? = null, val count: Int = 0)
    class Group(val title: String?, val items: List<Item>)

    private val p = { v: Number -> Ui.px(ctx, v) }
    private val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(ctx).apply {
        isVerticalScrollBarEnabled = false; isFocusable = false; setDefaultFocusHighlightEnabled(false)
        isVerticalFadingEdgeEnabled = true; setFadingEdgeLength(p(40))
        addView(list)
    }
    private val bottom = FrameLayout(ctx)
    private val rows = LinkedHashMap<String, Row>()
    var current: String? = null
        set(v) { field = v; rows.values.forEach { it.paint() } }

    init {
        setBackgroundColor(Ui.RAIL)
        addView(brand(ctx), Ui.frame(-1, p(52)).apply { leftMargin = p(36); rightMargin = p(28); topMargin = p(52) })
        addView(scroll, Ui.frame(-1, -1).apply { leftMargin = p(36); rightMargin = p(28); topMargin = p(104); bottomMargin = p(44 + 68 + 16) })
        addView(bottom, Ui.frame(-1, p(68), Gravity.BOTTOM).apply { leftMargin = p(36); rightMargin = p(28); bottomMargin = p(44) })
    }

    companion object {
        /** App icon and name at the top of the left list (also on the torrent screen). */
        fun brand(ctx: Context): View = LinearLayout(ctx).apply {
            val p = { v: Number -> Ui.px(ctx, v) }
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(p(12), 0, 0, 0)
            addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_app); scaleType = ImageView.ScaleType.CENTER_CROP
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, v.width * .24f)
                }
                clipToOutline = true
            }, Ui.linear(p(48), p(48)).apply { rightMargin = p(16) })
            addView(Ui.t(ctx, 30, 680, Ui.T1, spacing = -.4f).apply { text = "Lumen Play" })
        }
    }

    fun set(groups: List<Group>, last: Item) {
        val focused = rows.entries.firstOrNull { it.value.isFocused }?.key
        list.removeAllViews(); bottom.removeAllViews(); rows.clear()
        for (g in groups) {
            if (g.title != null) list.addView(Ui.t(context, 17, 650, Ui.T3, spacing = 2.4f).apply { text = g.title.uppercase() },
                Ui.linear(-2, -2).apply { leftMargin = p(16); topMargin = p(34); bottomMargin = p(12) })
            for (i in g.items) list.addView(row(i), Ui.linear(-1, p(if (i.sub != null) 84 else 68)).apply { bottomMargin = p(6) })
        }
        bottom.addView(row(last), Ui.frame(-1, -1))
        focused?.let { rows[it]?.requestFocus() }
    }

    private fun row(i: Item) = Row(i).also { rows[i.id] = it }

    fun focusCurrent(): Boolean = (rows[current] ?: rows.values.firstOrNull())?.requestFocus() == true

    fun has(v: View?): Boolean {
        var x: View? = v
        while (x != null) { if (x === this) return true; x = x.parent as? View }
        return false
    }

    fun ids(): Set<String> = rows.keys

    inner class Row(val item: Item) : LinearLayout(context) {
        private val icon = ImageView(context)
        private val label = Ui.t(context, 26, 520, Ui.T1, spacing = 0f)
        private val sub = Ui.t(context, 19, 450, Ui.T3)
        private val count = Ui.t(context, 19, 600, Ui.T1).apply { gravity = Gravity.CENTER; setPadding(p(8), 0, p(8), 0); minWidth = p(30) }

        init {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(p(18), 0, p(18), 0)
            isFocusable = true; isClickable = true
            addView(icon, Ui.linear(p(30), p(30)).apply { rightMargin = p(18) })
            val col = LinearLayout(context).apply { orientation = VERTICAL }
            label.text = item.label
            col.addView(label)
            if (item.sub != null) { sub.text = item.sub; col.addView(sub, Ui.linear(-2, -2).apply { topMargin = p(5) }) }
            addView(col, Ui.linear(0, -2, 1f))
            if (item.count > 0) { count.text = item.count.toString(); addView(count, Ui.linear(-2, p(30)).apply { leftMargin = p(12) }) }
            setOnClickListener { onOpen(item.id) }
            setOnFocusChangeListener { _, _ -> paint() }
            paint()
        }

        fun paint() {
            val f = isFocused; val cur = item.id == current
            background = if (f || cur) Ui.rounded(if (f) Ui.ACC else Ui.S1, p(16).toFloat()) else null
            val ink = if (f) Ui.WHITE else Ui.T1
            label.setTextColor(ink)
            Ui.weight(label, if (cur) 650 else 520, 26)
            sub.setTextColor(if (f) Ui.WHITE else Ui.T3)
            icon.setImageDrawable(Ui.icon(context, item.icon, if (f) Ui.WHITE else Ui.T2))
            count.setTextColor(ink)
            count.background = Ui.rounded(if (f) 0x40FFFFFF else Ui.S2, p(15).toFloat())
        }
    }
}

/** A small framed label: "1080p", "HDR", the OK key in the status line. */
fun chip(ctx: Context, text: String, size: Int = 15, weight: Int = 650, height: Int = 28, pad: Int = 8, radius: Int = 6,
         ink: Int = Ui.T2, line: Int = Ui.LINE2): TextView = Ui.t(ctx, size, weight, ink).apply {
    this.text = text
    gravity = Gravity.CENTER
    letterSpacing = 0.02f
    setPadding(Ui.px(ctx, pad), 0, Ui.px(ctx, pad), 0)
    height.let { minHeight = Ui.px(ctx, it) }
    background = Ui.rounded(0, Ui.px(ctx, radius).toFloat(), Ui.px(ctx, 1.5f).coerceAtLeast(2), line)
}

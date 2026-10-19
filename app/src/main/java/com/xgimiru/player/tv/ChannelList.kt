package com.xgimiru.player.tv

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.xgimiru.player.ui.Img
import com.xgimiru.player.ui.Ui
import com.xgimiru.player.ui.tr

/** Строка канала: номер, логотип, имя, что идёт сейчас с полоской, звёздочка избранного. */
class ChannelRow(ctx: Context) : LinearLayout(ctx) {
    private val d = { v: Number -> Ui.dp(ctx, v) }
    val num: TextView = Ui.text(ctx, 16f, Ui.INK3, Ui.medium()).apply { gravity = Gravity.END }
    val logo = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    val name: TextView = Ui.text(ctx, 17f, Ui.INK, Ui.medium())
    val now: TextView = Ui.text(ctx, 13f, Ui.INK3)
    val star: TextView = Ui.text(ctx, 18f, Ui.ACCENT).apply { text = "★" }
    private val bar = View(ctx)
    private val barBox = FrameLayout(ctx)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(d(10), d(9), d(14), d(9))
        addView(num, Ui.linear(d(34), -2).apply { rightMargin = d(12) })
        addView(logo, Ui.linear(d(56), d(34)).apply { rightMargin = d(14) })
        val col = LinearLayout(ctx).apply { orientation = VERTICAL }
        col.addView(name)
        col.addView(now, Ui.linear(-1, -2).apply { topMargin = d(4) })
        barBox.background = Ui.rounded(0x26F4EFE6, d(2).toFloat())
        bar.background = Ui.rounded(Ui.ACCENT, d(2).toFloat())
        barBox.addView(bar, Ui.frame(0, -1))
        col.addView(barBox, Ui.linear(-1, d(3)).apply { topMargin = d(7) })
        addView(col, Ui.linear(0, -2, 1f))
        addView(star, Ui.linear(-2, -2).apply { leftMargin = d(12) })
    }

    fun bind(c: Channel, fav: Boolean, nowMs: Long) {
        num.text = c.number.toString()
        name.text = c.name
        Img.load(logo, c.logo, 160)
        val p = Epg.now(context, c, nowMs)
        now.text = p?.title ?: (c.group ?: "")
        now.visibility = if (now.text.isNullOrEmpty()) GONE else VISIBLE
        barBox.visibility = if (p != null) VISIBLE else GONE
        val frac = p?.progress(nowMs) ?: 0f
        barBox.post { bar.layoutParams = (bar.layoutParams as FrameLayout.LayoutParams).apply { width = (barBox.width * frac).toInt() }; bar.requestLayout() }
        star.visibility = if (fav) VISIBLE else GONE
    }
}

class ChannelAdapter(private val ctx: Context) : BaseAdapter() {
    var items: List<Channel> = emptyList()
        set(v) { field = v; favs = Iptv.favorites(ctx).toHashSet(); notifyDataSetChanged() }
    private var favs: Set<String> = emptySet()
    fun refreshFavs() { favs = Iptv.favorites(ctx).toHashSet(); notifyDataSetChanged() }
    override fun getCount() = items.size
    override fun getItem(p: Int) = items[p]
    override fun getItemId(p: Int) = p.toLong()
    override fun getView(p: Int, convert: View?, parent: ViewGroup?): View {
        val row = (convert as? ChannelRow) ?: ChannelRow(ctx).apply { layoutParams = AbsListView.LayoutParams(-1, -2) }
        row.bind(items[p], items[p].key in favs, System.currentTimeMillis())
        return row
    }
}

/** Список каналов, который выезжает слева поверх эфира. Меню (или долгий OK) — в избранное. */
class ChannelList(ctx: Context, private val onPick: (Channel) -> Unit) : FrameLayout(ctx) {
    private val adapter = ChannelAdapter(ctx)
    private val title: TextView
    private val list: ListView
    private val panel: LinearLayout

    init {
        visibility = GONE
        val d = { v: Number -> Ui.dp(ctx, v) }
        panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(24), d(36), d(16), d(24))
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xF50A0908.toInt(), 0xE60A0908.toInt(), 0x000A0908))
        }
        title = Ui.text(ctx, 26f, Ui.INK, Ui.serif())
        panel.addView(title, Ui.linear(-1, -2).apply { leftMargin = d(16); bottomMargin = d(16) })
        list = ListView(ctx).apply {
            divider = null
            selector = Ui.rounded(0x26F4EFE6, d(12).toFloat())
            setDrawSelectorOnTop(false)
            isVerticalScrollBarEnabled = false
            this.adapter = this@ChannelList.adapter
            setOnItemClickListener { _, _, p, _ -> onPick(this@ChannelList.adapter.getItem(p)) }
            setOnItemLongClickListener { _, _, p, _ -> Iptv.toggleFavorite(ctx, this@ChannelList.adapter.getItem(p).key); this@ChannelList.adapter.refreshFavs(); true }
            setOnKeyListener { _, code, ev ->
                if (ev.action == android.view.KeyEvent.ACTION_DOWN && code == android.view.KeyEvent.KEYCODE_MENU) {
                    val p = selectedItemPosition
                    if (p >= 0) { Iptv.toggleFavorite(ctx, this@ChannelList.adapter.getItem(p).key); this@ChannelList.adapter.refreshFavs() }
                    true
                } else false
            }
        }
        panel.addView(list, Ui.linear(-1, 0, 1f))
        addView(panel, Ui.frame(d(620), -1))
    }

    fun isOpen() = visibility == VISIBLE

    fun show(channels: List<Channel>, current: Int, listName: String) {
        title.text = when (listName) { "fav" -> tr("Избранное", "Favorites"); "all" -> tr("Все каналы", "All channels"); else -> listName }
        adapter.items = channels
        visibility = VISIBLE
        panel.translationX = -Ui.dp(context, 80).toFloat(); panel.alpha = 0f
        panel.animate().translationX(0f).alpha(1f).setDuration(180).start()
        list.requestFocus()
        list.setSelection(current)
    }

    fun hide() { visibility = GONE }
}

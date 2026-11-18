package com.xgimiru.player.home

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.xgimiru.player.History
import com.xgimiru.player.Name
import com.xgimiru.player.PlayerActivity
import com.xgimiru.player.R
import com.xgimiru.player.WatchNext
import com.xgimiru.player.files.Places
import com.xgimiru.player.media.Arts
import com.xgimiru.player.media.Fmt
import com.xgimiru.player.ui.Img
import com.xgimiru.player.ui.Nav
import com.xgimiru.player.ui.Pane
import com.xgimiru.player.ui.Ui
import com.xgimiru.player.ui.emptyState
import com.xgimiru.player.ui.round
import com.xgimiru.player.ui.tr

/**
 * Start screen: "Продолжить просмотр" as two big pictures, then "Недавно смотрели" (finished and older
 * started videos). With [historyOnly] the same rows list the whole history.
 */
class StartPane(private val act: Activity, private val nav: Nav, private val historyOnly: Boolean) : Pane {
    private val root = FrameLayout(act).apply { clipChildren = false; clipToPadding = false }
    override val view: View = root
    private val p = { v: Number -> Ui.px(act, v) }
    private val ru get() = tr("ru", "en") == "ru"
    private var first: View? = null
    private var lastFocus: View? = null

    override fun onShow() = build()

    private fun build() {
        val hadFocus = root.hasFocus()
        if (hadFocus) root.post { focusFirst() }
        root.removeAllViews()
        first = null
        lastFocus = null
        val cont = History.all(act).filter { !it.finished }
        for (e in cont) WatchNext.publish(act, e)
        val shown = if (historyOnly) emptySet() else cont.take(CARDS).map { it.key }.toSet()
        val recent = History.recent(act).filter { it.key !in shown }
        if (cont.isEmpty() && recent.isEmpty()) {
            root.addView(emptyState(act, if (historyOnly) tr("История", "History") else "Lumen Play",
                tr("Здесь появится то, что вы смотрели. Выберите слева флешку, память устройства или папку в сети.",
                    "What you watch shows up here. Pick a USB stick, the device storage or a network folder on the left."),
                null, null), Ui.at(act, 56, 56, p(1300)))
            return
        }
        if (historyOnly) { history(recent); return }
        var top = 56
        if (cont.isNotEmpty()) {
            root.addView(Ui.t(act, 44, 680, Ui.T1, spacing = -.8f).apply { text = tr("Продолжить просмотр", "Continue watching") }, Ui.at(act, 56, 56))
            val strip = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; setPadding(p(50), p(6), p(50), p(20)); clipChildren = false; clipToPadding = false }
            cont.take(CARDS).forEachIndexed { i, e -> strip.addView(card(e), Ui.linear(p(684), -2).apply { if (i > 0) leftMargin = p(20) }) }
            val hs = HorizontalScrollView(act).apply {
                isHorizontalScrollBarEnabled = false; isFocusable = false; clipChildren = false; clipToPadding = false
                addView(strip)
            }
            root.addView(hs, Ui.at(act, 0, 130, -1))
            top = 690
        }
        if (recent.isEmpty()) return
        root.addView(Ui.t(act, 28, 650, Ui.T1, spacing = -.3f).apply { text = tr("Недавно смотрели", "Recently watched") }, Ui.at(act, 56, top))
        recent.take(if (cont.isEmpty()) 12 else 4).forEachIndexed { i, e ->
            root.addView(row(e), Ui.at(act, 40 + (i % 2) * 704, top + 46 + (i / 2) * 112, p(672), p(104)))
        }
    }

    private fun history(list: List<History.Entry>) {
        root.addView(Ui.t(act, 44, 680, Ui.T1, spacing = -.8f).apply { text = tr("История", "History") }, Ui.at(act, 56, 56))
        val grid = FrameLayout(act).apply { clipChildren = false; clipToPadding = false }
        list.forEachIndexed { i, e -> grid.addView(row(e), Ui.at(act, 40 + (i % 2) * 704, (i / 2) * 112, p(672), p(104))) }
        val sv = ScrollView(act).apply {
            isVerticalScrollBarEnabled = false; isFocusable = false; setDefaultFocusHighlightEnabled(false); clipChildren = false
            addView(grid, FrameLayout.LayoutParams(-1, p(((list.size + 1) / 2) * 112 + 40)))
        }
        root.addView(sv, Ui.at(act, 0, 130, -1, -1))
    }

    private fun play(e: History.Entry) {
        act.startActivity(Intent(act, PlayerActivity::class.java).setData(Uri.parse(e.uri)).putExtra("title", e.title))
    }

    private fun parts(e: History.Entry): Triple<String, String?, String> {
        val n = Name.parse(e.title)
        val ep = if (n.episode != null) tr("Сезон ${n.season ?: 1} · Серия ${n.episode}", "Season ${n.season ?: 1} · Episode ${n.episode}") else null
        val left = tr("осталось ", "") + Fmt.duration(e.duration - e.position, ru) + tr("", " left")
        return Triple(n.title, ep ?: n.year, left)
    }

    /** A big Continue picture with progress, title, episode or year, time left and where the file lies. */
    private fun card(e: History.Entry): View {
        val (title, what, left) = parts(e)
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        val ring = FrameLayout(act).apply { setPadding(p(6), p(6), p(6), p(6)); isFocusable = true; isClickable = true }
        val box = FrameLayout(act).apply { setBackgroundColor(Ui.S1); round(p(18).toFloat()) }
        val img = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        box.addView(img, Ui.frame(-1, -1))
        box.addView(progress(e.progress, 7), Ui.frame(-1, p(7), Gravity.BOTTOM))
        ring.addView(box, Ui.frame(p(672), p(378)))
        col.addView(ring, Ui.linear(p(684), p(390)))
        col.addView(Ui.t(act, 30, 640, Ui.T1, spacing = -.3f).apply { text = title }, Ui.linear(-1, -2).apply { leftMargin = p(6); topMargin = p(14) })
        col.addView(Ui.t(act, 23, 450, Ui.T2).apply { text = listOfNotNull(what, left).joinToString(" · ") }, Ui.linear(-1, -2).apply { leftMargin = p(6); topMargin = p(8) })
        Places.where(act, e.uri)?.let { w ->
            col.addView(LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                val icon = when { !e.uri.startsWith("file:") -> R.drawable.ic_dns; "/storage/emulated/" in e.uri -> R.drawable.ic_hard_drive; else -> R.drawable.ic_usb }
                addView(ImageView(act).apply { setImageDrawable(Ui.icon(act, icon, Ui.T3)) },
                    Ui.linear(p(22), p(22)).apply { rightMargin = p(8) })
                addView(Ui.t(act, 20, 450, Ui.T3).apply { text = w })
            }, Ui.linear(-1, -2).apply { leftMargin = p(6); topMargin = p(12) })
        }
        Arts.find(act, e.uri, e.title) { a -> Img.load(img, a.wide ?: a.poster, 900) }
        ring.setOnFocusChangeListener { v, f ->
            v.background = if (f) LayerDrawable(arrayOf(Ui.rounded(Ui.ACC, p(24).toFloat()), Ui.rounded(Ui.BG, p(21).toFloat()))).apply {
                setLayerInset(1, p(3), p(3), p(3), p(3))
            } else null
            v.animate().scaleX(if (f) 1.02f else 1f).scaleY(if (f) 1.02f else 1f).setDuration(200).start()
            if (f) lastFocus = v
        }
        ring.setOnClickListener { play(e) }
        ring.setOnKeyListener { _, code, ev ->
            if (ev.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_MENU) { History.remove(act, e.key); build(); nav.refreshRail(); focusFirst(); true } else false
        }
        if (first == null) first = ring
        return col
    }

    /** One line of "Недавно смотрели": still, title, episode or "Досмотрен", when. */
    private fun row(e: History.Entry): View {
        val (title, what, left) = parts(e)
        val r = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(p(16), 0, p(16), 0); isFocusable = true; isClickable = true
        }
        val img = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Ui.S1); round(p(10).toFloat()) }
        r.addView(img, Ui.linear(p(160), p(90)).apply { rightMargin = p(22) })
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val t = Ui.t(act, 25, 560, Ui.T1).apply { text = title }
        val s = Ui.t(act, 20, 450, Ui.T3).apply {
            text = when { what != null && Name.parse(e.title).episode != null -> what; e.finished -> tr("Досмотрен", "Watched"); else -> left }
        }
        col.addView(t); col.addView(s, Ui.linear(-2, -2).apply { topMargin = p(6) })
        r.addView(col, Ui.linear(0, -2, 1f))
        val w = Ui.t(act, 20, 450, Ui.T3).apply { text = Fmt.whenWatched(e.updated, System.currentTimeMillis(), ru) }
        r.addView(w, Ui.linear(-2, -2).apply { leftMargin = p(16) })
        Arts.find(act, e.uri, e.title) { a -> Img.load(img, a.wide ?: a.poster, 360) }
        r.setOnFocusChangeListener { _, f ->
            r.background = if (f) Ui.rounded(Ui.ACC, p(16).toFloat()) else null
            t.setTextColor(if (f) Ui.WHITE else Ui.T1)
            s.setTextColor(if (f) 0xD9FFFFFF.toInt() else Ui.T3); w.setTextColor(if (f) 0xD9FFFFFF.toInt() else Ui.T3)
            if (f) lastFocus = r
        }
        r.setOnClickListener { play(e) }
        if (first == null) first = r
        return r
    }

    private fun progress(f: Float, h: Int): View = FrameLayout(act).apply {
        setBackgroundColor(0x47FFFFFF)
        val fill = View(act).apply { setBackgroundColor(Ui.ACC) }
        addView(fill, Ui.frame(0, -1))
        post { fill.layoutParams = fill.layoutParams.apply { width = (this@apply.width * f.coerceIn(0f, 1f)).toInt() }; fill.requestLayout() }
    }

    override fun focusFirst(): Boolean {
        val v = lastFocus?.takeIf { it.isAttachedToWindow } ?: first ?: return false
        return v.requestFocus()
    }

    private companion object { const val CARDS = 6 }
}

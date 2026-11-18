package com.xgimiru.player.home

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.LinearLayout
import com.xgimiru.player.Art
import com.xgimiru.player.History
import com.xgimiru.player.Name
import com.xgimiru.player.PlayerActivity
import com.xgimiru.player.Time
import com.xgimiru.player.ui.Card
import com.xgimiru.player.ui.Host
import com.xgimiru.player.ui.Img
import com.xgimiru.player.ui.Pane
import com.xgimiru.player.ui.Row
import com.xgimiru.player.ui.Ui
import com.xgimiru.player.ui.emptyState
import com.xgimiru.player.ui.onKey
import com.xgimiru.player.ui.tr

/**
 * Главная: только то, что вы смотрите. Ряд «Продолжить просмотр» (начатые фильмы и серии).
 * Пусто — два первых шага: файлы и папки в сети.
 */
class HomePane(private val act: Activity, private val host: Host, private val go: (String) -> Unit) : Pane {
    private val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
    private val frame = android.widget.FrameLayout(act).apply { clipChildren = false; clipToPadding = false }
    override val view: View = frame
    private val rows = ArrayList<Row>()
    private val io = java.util.concurrent.Executors.newFixedThreadPool(2)

    // высота с запасом: ряды не сжимаются, лишнее уходит за край и выезжает, когда на ряду фокус
    init { frame.addView(root, Ui.frame(-1, Ui.dp(act, 1400)).apply { topMargin = Ui.dp(act, 250) }) }

    /** Ряд с фокусом встаёт на одну и ту же высоту, ряды выше плавно уходят. */
    private fun follow(r: Row) {
        val i = rows.indexOf(r)
        if (i < 0) return
        root.animate().translationY(-r.top.toFloat() + rows[0].top).setDuration(280).start()
        rows.forEachIndexed { j, x -> x.animate().alpha(if (j < i) 0f else 1f).setDuration(220).start() }
    }

    override fun onShow() = build()

    private fun build() {
        root.removeAllViews()
        rows.clear()
        root.translationY = 0f
        val d = { v: Number -> Ui.dp(act, v) }
        val history = History.all(act).filter { !it.finished }
        for (e in history) com.xgimiru.player.WatchNext.publish(act, e)   // «Продолжить просмотр» лаунчера (записи до 0.12)
        if (history.isEmpty()) {
            host.backdrop(null)
            host.info(null, null, null, null)
            (root.layoutParams as? android.widget.FrameLayout.LayoutParams)?.topMargin = d(110)
            root.addView(LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                addView(emptyState(act, tr("Добро пожаловать", "Welcome"),
                    tr("Lumen Play играет видео с флешки, из памяти проектора и из папок в сети. Другие приложения тоже могут открывать в нём видео.",
                        "Lumen Play plays videos from a USB stick, the device storage and network folders. Other apps can open videos in it too."), null, null))
                val r = Row(act, "")
                r.add(step(tr("Открыть файлы", "Open files"), tr("флешка и память", "USB stick and storage")) { go("files") })
                r.add(step(tr("Папки в сети", "Network folders"), tr("компьютер, NAS, DLNA", "computer, NAS, DLNA")) { go("network") })
                addView(r, Ui.linear(-1, -2).apply { topMargin = d(18) })
            }, Ui.linear(-1, -1))
            return
        }
        (root.layoutParams as? android.widget.FrameLayout.LayoutParams)?.topMargin = d(250)
        if (history.isNotEmpty()) {
            val r = Row(act, tr("Продолжить просмотр", "Continue watching"))
            for (e in history.take(20)) r.add(historyCard(e).also { c -> c.onRow = { follow(r) } })
            root.addView(r); rows += r
        }
    }

    private fun historyCard(e: History.Entry): Card {
        val n = Name.parse(e.title)
        val c = Card(act, 256, 144)
        c.label.text = n.title
        c.sub.text = listOfNotNull(
            if (n.episode != null) "S${n.season ?: 1} E${n.episode}" else n.year,
            tr("осталось ", "") + Time.human(e.duration - e.position) + tr("", " left"),
        ).joinToString(" · ")
        c.progress(e.progress)
        val frame = Art.file(act, e.key).takeIf { it.exists() }?.let { "file://" + it.path }
        var art = frame
        var meta: com.xgimiru.player.net.Meta? = null
        Img.load(c.image, art, 600)
        // обложка и описание из TMDB вместо случайного кадра; пришли — тихо подменяем
        io.execute {
            val m = com.xgimiru.player.net.Tmdb.find(act, n.title, n.year, if (n.episode != null) true else null) ?: return@execute
            act.runOnUiThread {
                meta = m
                m.backdrop("w780")?.let { art = m.backdrop("w1280"); Img.load(c.image, it, 600) }
                if (c.isFocused) c.onFocus?.invoke(true)
            }
        }
        c.onFocus = { f ->
            if (f) {
                host.backdrop(art)
                host.info(tr("Продолжить", "Continue"), meta?.title ?: n.title,
                    listOfNotNull(if (n.episode != null) tr("Сезон ${n.season ?: 1} · Серия ${n.episode}", "Season ${n.season ?: 1} · Episode ${n.episode}") else n.year,
                        tr("осталось ", "") + Time.human(e.duration - e.position) + tr("", " left")).joinToString(" · "),
                    meta?.overview, e.progress)
            }
        }
        c.setOnClickListener {
            act.startActivity(Intent(act, PlayerActivity::class.java).setData(Uri.parse(e.uri)).putExtra("title", e.title))
        }
        c.onKey(android.view.KeyEvent.KEYCODE_MENU) { History.remove(act, e.key); build(); root.post { focusFirst() }; true }
        return c
    }

    private fun step(title: String, sub: String, onClick: () -> Unit): Card = Card(act, 260, 146).apply {
        label.text = title
        label.textSize = 20f
        this.sub.text = sub
        onFocus = { if (it) host.backdrop(null) }
        setOnClickListener { onClick() }
    }

    override fun focusFirst(): Boolean {
        for (i in 0 until root.childCount) {
            val r = root.getChildAt(i)
            if (r is Row && r.size > 0) { r.first()?.requestFocus(); return true }
            if (r is LinearLayout) for (j in 0 until r.childCount) {
                val rr = r.getChildAt(j)
                if (rr is Row && rr.size > 0) { rr.first()?.requestFocus(); return true }
            }
        }
        return false
    }
}

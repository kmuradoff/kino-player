package com.xgimiru.player.torrents

import android.app.Activity
import com.xgimiru.player.R
import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.xgimiru.player.ui.Host
import com.xgimiru.player.ui.Pane
import com.xgimiru.player.ui.Ui
import com.xgimiru.player.ui.emptyState
import com.xgimiru.player.ui.tr
import java.util.concurrent.Executors

/**
 * Торренты: строка поиска сверху, под ней результаты (лучшие по сидам), у каждого значки качества,
 * размер и сиды. OK — список файлов и воспроизведение. До поиска — недавние запросы.
 * Поиск идёт через Jackett / Prowlarr пользователя; без него — подсказка, как подключить.
 */
class TorrentsPane(private val act: Activity, private val host: Host) : Pane {
    private val io = Executors.newSingleThreadExecutor()
    private val root = FrameLayout(act)
    override val view: View = root
    private val d = { v: Number -> Ui.dp(act, v) }
    private val input = EditText(act)
    private val list = ListView(act)
    private val status = Ui.text(act, 16f, Ui.INK3, Ui.sans(), 3)
    private var results: List<Release> = emptyList()
    private val films = com.xgimiru.player.ui.Row(act, "")
    private var lastQuery = ""
    private var showingRecent = true
    private var seq = 0

    override fun onShow() {
        host.backdrop(null)
        host.info(null, null, null, null)
        build()
    }

    private fun build() {
        root.removeAllViews()
        if (!Torznab.configured(act)) {
            root.addView(emptyState(act, tr("Поиск торрентов", "Torrent search"),
                tr("Подключите свой Jackett или Prowlarr: Lumen Play будет искать по трекерам, которые вы в нём настроили. Magnet-ссылки и .torrent-файлы открываются и без этого.",
                    "Connect your Jackett or Prowlarr: Lumen Play will search the trackers you set up there. Magnet links and .torrent files open without it too."),
                tr("Подключить поиск", "Connect search")) { setup(act) { build() } }, Ui.frame(-1, -1))
            return
        }
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(d(56), d(8), d(56), d(20)) }
        input.apply {
            hint = tr("Название фильма или сериала", "Movie or series title")
            setHintTextColor(Ui.INK3)
            setTextColor(Ui.INK)
            textSize = 20f
            typeface = Ui.sans()
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(d(22), d(14), d(22), d(14))
            background = Ui.rounded(0x1FF4EFE6, d(30).toFloat())
            setOnFocusChangeListener { _, f -> background = Ui.rounded(if (f) 0x33F4EFE6 else 0x1FF4EFE6, d(30).toFloat(), if (f) d(2) else 0, Ui.INK) }
            setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_SEARCH) { search(text.toString()); true } else false }
            setOnKeyListener { _, code, ev ->
                if (ev.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_DOWN) {
                    when {
                        films.visibility == View.VISIBLE && films.size > 0 -> { films.first()?.requestFocus(); true }
                        list.count > 0 -> { list.requestFocus(); list.setSelection(0); true }
                        else -> false
                    }
                } else false
            }
        }
        col.addView(input, Ui.linear(d(640), -2))
        col.addView(status, Ui.linear(-1, -2).apply { topMargin = d(16); leftMargin = d(8) })
        films.strip.setPadding(0, d(8), d(56), d(8))
        (films.heading.layoutParams as LinearLayout.LayoutParams).leftMargin = d(8)
        films.visibility = View.GONE
        (films.parent as? android.view.ViewGroup)?.removeView(films)
        col.addView(films, Ui.linear(-1, -2).apply { topMargin = d(4) })
        list.apply {
            divider = null
            selector = Ui.rounded(0x2EF4EFE6, d(12).toFloat())
            isVerticalScrollBarEnabled = false
            adapter = Adapter()
            setOnItemClickListener { _, _, p, _ ->
                if (showingRecent) { val q = recent(act)[p]; input.setText(q); search(q) } else Torznab.open(act, results[p])
            }
            setOnKeyListener { _, code, ev ->
                if (ev.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_UP && selectedItemPosition <= 0) {
                    if (films.visibility == View.VISIBLE && films.size > 0) films.first()?.requestFocus() else input.requestFocus(); true
                } else false
            }
        }
        col.addView(list, Ui.linear(-1, 0, 1f).apply { topMargin = d(10) })
        root.addView(col, Ui.frame(-1, -1))
        showRecent()
    }

    private fun showRecent() {
        showingRecent = true
        films.visibility = View.GONE
        val r = recent(act)
        status.text = if (r.isEmpty()) tr("Нажмите OK на строке поиска и введите название.", "Press OK on the search line and type a title.")
        else tr("Недавние запросы", "Recent searches")
        (list.adapter as BaseAdapter).notifyDataSetChanged()
    }

    private fun search(q: String) {
        val query = q.trim()
        if (query.isEmpty()) return
        (act.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
        remember(act, query)
        lastQuery = query
        val my = ++seq
        status.text = tr("Ищу «$query»…", "Searching for “$query”…")
        io.execute {
            val metas = runCatching { com.xgimiru.player.net.Tmdb.search(query) }.getOrDefault(emptyList())
            act.runOnUiThread { if (my == seq) showFilms(metas) }
            val r = runCatching { Torznab.search(act, query) }
            act.runOnUiThread {
                if (my != seq) return@runOnUiThread
                r.onSuccess {
                    results = it
                    showingRecent = false
                    status.text = if (it.isEmpty()) tr("Ничего не нашлось. Попробуйте название на другом языке.", "Nothing found. Try the title in another language.")
                    else tr("Найдено: ${it.size}. Лучшие раздачи сверху.", "Found ${it.size}. The best seeded first.")
                    (list.adapter as BaseAdapter).notifyDataSetChanged()
                    if (it.isNotEmpty()) { list.requestFocus(); list.setSelection(0) }
                }.onFailure { status.text = it.message }
            }
        }
    }

    /** Фильмы и сериалы по запросу (TMDB): постеры над раздачами. OK — раздачи именно этого фильма. */
    private fun showFilms(metas: List<com.xgimiru.player.net.Meta>) {
        films.clear()
        films.heading.text = tr("Фильмы и сериалы", "Movies and series")
        for (m in metas) {
            val c = com.xgimiru.player.ui.Card(act, 104, 156)
            c.label.visibility = View.GONE; c.sub.visibility = View.GONE
            com.xgimiru.player.ui.Img.load(c.image, m.poster("w342"), 360)
            c.onFocus = { f -> if (f) host.backdrop(m.backdrop()) }
            c.setOnClickListener { searchFilm(m) }
            films.add(c)
        }
        films.visibility = if (metas.isEmpty()) View.GONE else View.VISIBLE
    }

    /** Раздачи выбранного фильма: поиск по названию и по оригинальному названию с годом, без повторов. */
    private fun searchFilm(m: com.xgimiru.player.net.Meta) {
        val my = ++seq
        status.text = tr("Ищу раздачи «${m.title}»…", "Looking for releases of “${m.title}”…")
        io.execute {
            val qs = listOfNotNull(m.title + (m.year?.let { " $it" } ?: ""), m.original?.takeIf { it != m.title }?.let { it + (m.year?.let { y -> " $y" } ?: "") })
            val all = qs.flatMap { q -> runCatching { Torznab.search(act, q) }.getOrDefault(emptyList()) }
            val seen = HashSet<String>()
            val found = all.filter { seen.add(it.hash?.lowercase() ?: it.title.lowercase()) }.sortedByDescending { it.seeders }
            act.runOnUiThread {
                if (my != seq) return@runOnUiThread
                results = found
                showingRecent = false
                status.text = if (found.isEmpty()) tr("У «${m.title}» раздач не нашлось.", "No releases found for “${m.title}”.")
                else tr("«${m.title}»: ${found.size} раздач, лучшие сверху.", "“${m.title}”: ${found.size} releases, the best seeded first.")
                (list.adapter as BaseAdapter).notifyDataSetChanged()
                if (found.isNotEmpty()) { list.requestFocus(); list.setSelection(0) }
            }
        }
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = if (showingRecent) recent(act).size else results.size
        override fun getItem(p: Int): Any = if (showingRecent) recent(act)[p] else results[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, c: View?, parent: android.view.ViewGroup?): View {
            val row = (c as? ReleaseRow) ?: ReleaseRow(act).apply { layoutParams = AbsListView.LayoutParams(-1, -2) }
            if (showingRecent) row.bindRecent(recent(act)[p]) else row.bind(results[p])
            return row
        }
    }

    override fun focusFirst(): Boolean {
        if (!Torznab.configured(act)) return root.getChildAt(0)?.let { v -> findFocusable(v)?.requestFocus() } == true
        input.requestFocus(); return true
    }

    private fun findFocusable(v: View): View? {
        if (v.isFocusable) return v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) findFocusable(v.getChildAt(i))?.let { return it }
        return null
    }

    override fun onBack(): Boolean {
        if (!showingRecent) { showRecent(); input.requestFocus(); return true }
        return false
    }

    companion object {
        private fun recent(ctx: Context): List<String> =
            ctx.getSharedPreferences("torznab", Context.MODE_PRIVATE).getString("recent", "")!!.split('\n').filter { it.isNotEmpty() }

        private fun remember(ctx: Context, q: String) {
            val l = (listOf(q) + recent(ctx).filter { !it.equals(q, true) }).take(8)
            ctx.getSharedPreferences("torznab", Context.MODE_PRIVATE).edit().putString("recent", l.joinToString("\n")).apply()
        }

        /** Подключить Jackett / Prowlarr: адрес Torznab и ключ API. */
        fun setup(act: Activity, done: () -> Unit) {
            val d = { v: Number -> Ui.dp(act, v) }
            val url = EditText(act).apply {
                hint = "http://192.168.1.10:9117/api/v2.0/indexers/all/results/torznab"
                setText(Torznab.url(act) ?: ""); setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            }
            val key = EditText(act).apply { hint = tr("Ключ API", "API key"); setText(Torznab.key(act) ?: ""); setSingleLine() }
            val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(d(24), d(8), d(24), 0); addView(url); addView(key) }
            AlertDialog.Builder(act, R.style.Lumen_Dialog)
                .setTitle(tr("Поиск через Jackett или Prowlarr", "Search with Jackett or Prowlarr"))
                .setMessage(tr("Адрес Torznab из Jackett («Copy Torznab Feed», индексатор all) или Prowlarr, и ключ API оттуда же.",
                    "The Torznab address from Jackett (“Copy Torznab Feed”, the all indexer) or Prowlarr, and the API key from there."))
                .setView(box)
                .setPositiveButton(tr("Сохранить", "Save")) { _, _ -> Torznab.save(act, url.text.toString(), key.text.toString()); done() }
                .setNeutralButton(tr("Отключить", "Disconnect")) { _, _ -> Torznab.save(act, null, null); done() }
                .setNegativeButton(tr("Отмена", "Cancel"), null)
                .show()
        }
    }
}

/** Строка раздачи: название, значки качества, размер, сиды, трекер. */
class ReleaseRow(ctx: Context) : LinearLayout(ctx) {
    private val d = { v: Number -> Ui.dp(ctx, v) }
    private val title = Ui.text(ctx, 18f, Ui.INK, Ui.medium(), 2)
    private val badges = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val meta = Ui.text(ctx, 14f, Ui.INK3)
    private val seeds = Ui.text(ctx, 18f, Ui.ACCENT, Ui.bold()).apply { gravity = Gravity.END }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(d(20), d(12), d(22), d(12))
        val col = LinearLayout(ctx).apply { orientation = VERTICAL }
        col.addView(title)
        val line = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        line.addView(badges)
        line.addView(meta)
        col.addView(line, Ui.linear(-1, -2).apply { topMargin = d(6) })
        addView(col, Ui.linear(0, -2, 1f))
        addView(seeds, Ui.linear(d(90), -2))
    }

    fun bind(r: Release) {
        title.text = r.title
        title.maxLines = 2
        badges.removeAllViews()
        for (b in r.badges) badges.addView(Ui.text(context, 12f, Ui.GROUND, Ui.bold()).apply {
            text = b
            setPadding(d(8), d(2), d(8), d(2))
            background = Ui.rounded(if (b == "4K" || b == "DV" || b.startsWith("HDR")) Ui.ACCENT else Ui.INK2, d(6).toFloat())
        }, Ui.linear(-2, -2).apply { rightMargin = d(8) })
        meta.text = listOfNotNull(Torznab.human(r.size).ifEmpty { null }, r.tracker).joinToString("  ·  ")
        seeds.text = "▲ ${r.seeders}"
        seeds.visibility = VISIBLE
    }

    fun bindRecent(q: String) {
        title.text = q
        title.maxLines = 1
        badges.removeAllViews()
        meta.text = ""
        seeds.visibility = GONE
    }
}

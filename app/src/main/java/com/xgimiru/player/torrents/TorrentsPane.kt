package com.xgimiru.player.torrents

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.xgimiru.player.History
import com.xgimiru.player.R
import com.xgimiru.player.TorrApi
import com.xgimiru.player.TorrEngine
import com.xgimiru.player.TorrService
import com.xgimiru.player.TorrSettings
import com.xgimiru.player.TorrentActivity
import com.xgimiru.player.net.Meta
import com.xgimiru.player.net.Tmdb
import com.xgimiru.player.ui.Card
import com.xgimiru.player.ui.Host
import com.xgimiru.player.ui.Img
import com.xgimiru.player.ui.Pane
import com.xgimiru.player.ui.Row
import com.xgimiru.player.ui.Ui
import com.xgimiru.player.ui.emptyState
import com.xgimiru.player.ui.onKey
import com.xgimiru.player.ui.tr
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Торренты, как в Лампе: строка поиска, под ней недавние запросы и «Мои торренты» (то, что уже
 * в движке). Поиск показывает фильмы и сериалы из TMDB постерами; OK на постере: страница фильма
 * с раздачами ([FilmActivity]). «Все раздачи»: простой список по запросу (без TMDB только он).
 * Источники поиска добавляет пользователь ([SourcesActivity]); своих в Lumen Play нет.
 */
class TorrentsPane(private val act: Activity, private val host: Host) : Pane {
    private val io = Executors.newSingleThreadExecutor()
    private val mineIo = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val root = FrameLayout(act)
    // невидимый «якорь» для фокуса на время перестройки раздела (см. parkFocus)
    private val anchor = View(act)
    override val view: View = FrameLayout(act).apply {
        addView(anchor, Ui.frame(1, 1))
        addView(root, Ui.frame(-1, -1))
    }
    private val d = { v: Number -> Ui.dp(act, v) }

    private val input = EditText(act)
    private lateinit var allPill: View
    private val detail = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private val dTitle = Ui.text(act, 26f, Ui.INK, Ui.serif())
    private val dMeta = Ui.text(act, 15f, Ui.INK2)
    private val dDesc = Ui.text(act, 14f, Ui.INK2, Ui.sans(), 2).apply { setLineSpacing(0f, 1.3f) }
    private val status = Ui.text(act, 15f, Ui.INK3, Ui.sans(), 2)
    private val rowsBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
    private val recentRow = Row(act, tr("Недавние запросы", "Recent searches"))
    private val filmsRow = Row(act, tr("Фильмы и сериалы", "Movies and series"))
    private val mineRow = Row(act, tr("Мои торренты", "My torrents"))

    private var builtFor: Boolean? = null          // для какого состояния (есть источники или нет) построен экран
    private var results: List<Meta>? = null        // null: экран до поиска
    private var lastQuery = ""
    private var seq = 0
    private var searching = false
    private var searchFocus: View? = null      // где был фокус, когда начали искать
    private val found = LinkedHashMap<String, List<Meta>>()   // недавние ответы TMDB: повторный поиск мгновенный
    private var backdropUrl: String? = null
    private var holding = false
    @Volatile private var mineBase: String? = null

    override fun onShow() {
        host.info(null, null, null, null)
        val conf = Sources.configured(act)
        if (builtFor != conf) build(conf) else if (results == null) showIdle(keepFocus = true)
        host.backdrop(if (results != null) backdropUrl else null)
        // «Мои торренты»: движок не трогаем, пока раздел просто пролистывают вкладками
        main.removeCallbacks(mineTask)
        main.postDelayed(mineTask, 900)
    }

    override fun onHide() {
        main.removeCallbacks(mineTask)
        if (holding) { TorrService.release(act, WHO); holding = false }
    }

    private fun build(conf: Boolean) {
        val hadFocus = root.hasFocus()
        if (hadFocus) { anchor.isFocusable = true; anchor.requestFocus() }
        buildViews(conf)
        if (hadFocus) { focusFirst(); anchor.isFocusable = false }
    }

    private fun buildViews(conf: Boolean) {
        builtFor = conf
        root.removeAllViews()
        results = null
        for (r in listOf(recentRow, filmsRow, mineRow)) (r.parent as? android.view.ViewGroup)?.removeView(r)
        rowsBox.removeAllViews()
        (detail.parent as? android.view.ViewGroup)?.removeView(detail)
        (status.parent as? android.view.ViewGroup)?.removeView(status)
        (input.parent as? android.view.ViewGroup)?.removeView(input)

        val scroll = ScrollView(act).apply { isVerticalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; isFillViewport = true }
        if (!conf) {
            // без источников: подсказка и кнопка; «Мои торренты» всё равно ниже
            val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
            box.addView(emptyState(act, tr("Поиск торрентов", "Torrent search"),
                tr("Добавьте источник поиска: свой Jackett, Prowlarr или совместимый сервис. Magnet-ссылки и .torrent-файлы открываются и без него.",
                    "Add a search source: your own Jackett, Prowlarr or a compatible service. Magnet links and .torrent files open without one too."),
                tr("Добавить источник", "Add a source")) { openSources() }, Ui.linear(-1, -2).apply { topMargin = d(36) })
            box.addView(mineRow, Ui.linear(-1, -2).apply { topMargin = d(28) })
            mineRow.visibility = if (mineRow.size > 0) View.VISIBLE else View.GONE
            scroll.addView(box)
            root.addView(scroll, Ui.frame(-1, -1))
            return
        }

        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(0, d(6), 0, 0); clipChildren = false; clipToPadding = false }
        val top = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        input.apply {
            hint = tr("Название фильма или сериала", "Movie or series title")
            setHintTextColor(Ui.INK3)
            setTextColor(Ui.INK)
            textSize = 20f
            typeface = Ui.sans()
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            inputType = InputType.TYPE_CLASS_TEXT
            // вверх и вниз с пульта: сразу к вкладкам или рядам, а не курсор в начало строки
            setOnKeyListener { v, code, ev ->
                val dir = when (code) { KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP; KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN; else -> 0 }
                dir != 0 && ev.action == KeyEvent.ACTION_DOWN && v.focusSearch(dir)?.requestFocus() == true
            }
            setPadding(d(22), d(13), d(22), d(13))
            background = Ui.rounded(0x1FF4EFE6, d(30).toFloat())
            setOnFocusChangeListener { _, f -> background = Ui.rounded(if (f) 0x33F4EFE6 else 0x1FF4EFE6, d(30).toFloat(), if (f) d(2) else 0, Ui.INK) }
            setOnEditorActionListener { _, a, ev ->
                if (a == EditorInfo.IME_ACTION_SEARCH || a == EditorInfo.IME_ACTION_DONE || ev?.keyCode == KeyEvent.KEYCODE_ENTER) { search(text.toString()); true } else false
            }
        }
        top.addView(input, Ui.linear(d(460), -2).apply { rightMargin = d(14) })
        allPill = Ui.pill(act, tr("Все раздачи", "All releases")) { if (lastQuery.isNotEmpty()) FilmActivity.openQuery(act, lastQuery) }
        top.addView(allPill, Ui.linear(-2, -2).apply { rightMargin = d(10) })
        top.addView(Ui.pill(act, tr("Источники", "Sources")) { openSources() }, Ui.linear(-2, -2))
        col.addView(top, Ui.linear(-1, -2).apply { leftMargin = d(56); rightMargin = d(56) })

        detail.removeAllViews()
        detail.addView(dTitle)
        detail.addView(dMeta, Ui.linear(-1, -2).apply { topMargin = d(6) })
        detail.addView(dDesc, Ui.linear(d(620), -2).apply { topMargin = d(6) })
        col.addView(detail, Ui.linear(-1, -2).apply { leftMargin = d(60); topMargin = d(16) })
        col.addView(status, Ui.linear(-1, -2).apply { leftMargin = d(60); topMargin = d(12) })

        rowsBox.addView(recentRow, Ui.linear(-1, -2))
        rowsBox.addView(filmsRow, Ui.linear(-1, -2))
        rowsBox.addView(mineRow, Ui.linear(-1, -2).apply { topMargin = d(6) })
        scroll.addView(rowsBox)
        col.addView(scroll, Ui.linear(-1, 0, 1f).apply { topMargin = d(12) })
        root.addView(col, Ui.frame(-1, -1))
        showIdle(keepFocus = false)
    }

    /** До поиска: недавние запросы и «Мои торренты». */
    private fun showIdle(keepFocus: Boolean) {
        results = null
        backdropUrl = null
        if (builtFor != true) return
        allPill.visibility = View.GONE
        detail.visibility = View.GONE
        filmsRow.visibility = View.GONE
        val hadFocus = keepFocus && recentRow.hasFocus()
        parkFocus()
        recentRow.clear()
        val rec = recent(act)
        for (q in rec) {
            val p = Ui.pill(act, q) { input.setText(q); input.setSelection(q.length); search(q) }
            p.onKey(KeyEvent.KEYCODE_MENU) { forget(act, q); showIdle(keepFocus = true); true }
            recentRow.add(p.apply { layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = d(12) } })
        }
        recentRow.visibility = if (rec.isEmpty()) View.GONE else View.VISIBLE
        if (hadFocus) recentRow.first()?.requestFocus() ?: input.requestFocus()
        mineRow.visibility = if (mineRow.size > 0) View.VISIBLE else View.GONE
        setStatus(if (rec.isEmpty()) tr("Нажмите OK на строке поиска и введите название.", "Press OK on the search line and type a title.") else null)
    }

    /**
     * Фокус нельзя терять: если спрятать вью с фокусом, Android отдаёт его первой вкладке, и та
     * переключает раздел. Поэтому перед перестройкой рядов фокус переносим на строку поиска.
     */
    private fun parkFocus() {
        if (builtFor == true && rowsBox.hasFocus()) input.requestFocus()
        else if (builtFor == false && mineRow.hasFocus()) findFocusable(root)?.requestFocus()   // кнопка «Добавить источник»
    }

    private fun setStatus(t: String?) {
        status.text = t ?: ""
        status.visibility = if (t.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun search(q: String) {
        val query = q.trim()
        if (query.isEmpty()) return
        (act.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
        remember(act, query)
        lastQuery = query
        val my = ++seq
        // без TMDB: сразу простой список раздач
        if (!Tmdb.enabled) { showIdle(keepFocus = false); FilmActivity.openQuery(act, query); return }
        found[query.lowercase()]?.let { showResults(it); return }
        searchFocus = act.currentFocus
        setStatus(tr("Ищу «$query»…", "Searching for “$query”…"))
        searching = true
        io.execute {
            val metas = runCatching { Tmdb.search(query) }
            main.post {
                if (my != seq) return@post
                searching = false
                val list = metas.getOrDefault(emptyList())
                if (list.isNotEmpty()) {
                    found[query.lowercase()] = list
                    if (found.size > 12) found.remove(found.keys.first())
                }
                if (list.isEmpty()) {
                    showIdle(keepFocus = false)
                    setStatus(tr("Фильмов с таким названием не нашлось, показываю все раздачи по запросу.",
                        "No movies with this title, showing all releases for the query."))
                    FilmActivity.openQuery(act, query)
                } else showResults(list)
            }
        }
    }

    /** Найденные фильмы и сериалы: ряд постеров, над ним описание того, на чём фокус. */
    private fun showResults(metas: List<Meta>) {
        results = metas
        // на постеры, если фокус там же, где был при поиске, или в рядах, которые сейчас спрячутся;
        // если его увели с пульта (например, на строку поиска), не трогаем
        val f = act.currentFocus
        val toPosters = rowsBox.hasFocus() || searchFocus == null || f === searchFocus
        searchFocus = null
        parkFocus()
        allPill.visibility = View.VISIBLE
        recentRow.visibility = View.GONE
        mineRow.visibility = View.GONE
        detail.visibility = View.VISIBLE
        setStatus(null)
        filmsRow.clear()
        filmsRow.heading.text = tr("Фильмы и сериалы по запросу «$lastQuery»", "Movies and series for “$lastQuery”")
        for (m in metas) {
            val c = Card(act, 120, 180)
            c.label.text = m.title
            c.label.textSize = 14f
            c.sub.text = listOfNotNull(m.year, if (m.tv) tr("сериал", "series") else null).joinToString(" · ")
            Img.load(c.image, m.poster("w342"), 360)
            c.onFocus = { f -> if (f) focusFilm(m) }
            c.setOnClickListener { FilmActivity.open(act, m) }
            filmsRow.add(c)
        }
        filmsRow.visibility = View.VISIBLE
        focusFilm(metas[0])
        if (toPosters) filmsRow.first()?.requestFocus()
    }

    private fun focusFilm(m: Meta) {
        backdropUrl = m.backdrop("w1280")
        host.backdrop(backdropUrl)
        dTitle.text = m.title
        dMeta.text = listOfNotNull(m.original?.takeIf { !it.equals(m.title, true) }, m.year,
            if (m.tv) tr("Сериал", "Series") else tr("Фильм", "Movie"),
            m.rating.takeIf { it > 0 }?.let { "★ " + String.format(Locale.US, "%.1f", it) }).joinToString("  ·  ")
        dDesc.text = m.overview ?: ""
        dDesc.visibility = if (m.overview.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    // ── Мои торренты ──
    private val mineTask = Runnable {
        val builtin = TorrSettings.builtin(act)
        // встроенный движок при запуске оставляет только торренты из истории: если её нет, база пуста
        if (builtin && !TorrEngine.isRunning && History.all(act).none { it.key.startsWith("ts:") }) { showMine(emptyList()); return@Runnable }
        if (builtin && !holding) { TorrService.hold(act, WHO); holding = true }
        mineIo.execute {
            val items = runCatching {
                val base = if (builtin) TorrEngine.start(act) else TorrSettings.external(act)?.takeIf { TorrApi.alive(it) }
                mineBase = base
                if (base == null) emptyList() else TorrApi.list(base)
            }.getOrDefault(emptyList())
            main.post { if (root.isAttachedToWindow) showMine(items) }
        }
    }

    private fun showMine(items: List<TorrApi.Item>) {
        val focusAt = (0 until mineRow.size).firstOrNull { mineRow.strip.getChildAt(it).isFocused }
        if (focusAt != null) parkFocus()
        mineRow.clear()
        mineRow.heading.text = tr("Мои торренты", "My torrents") + if (items.isNotEmpty()) tr("   ·   Меню: убрать", "   ·   Menu: remove") else ""
        for (t in items) {
            val c = Card(act, 120, 180)
            c.label.text = t.title
            c.label.textSize = 14f
            c.label.maxLines = if (t.poster == null) 4 else 2
            c.sub.text = humanSize(t.size)
            Img.load(c.image, t.poster, 360)
            c.onFocus = { f -> if (f && results == null) host.backdrop(null) }
            c.setOnClickListener {
                val i = Intent(Intent.ACTION_VIEW, Uri.parse("magnet:?xt=urn:btih:${t.hash}")).setClass(act, TorrentActivity::class.java)
                    .putExtra(TorrentActivity.EXTRA_TITLE, t.title)
                t.poster?.let { i.putExtra(TorrentActivity.EXTRA_POSTER, it) }
                act.startActivity(i)
            }
            c.onKey(KeyEvent.KEYCODE_MENU) { askRemove(t); true }
            mineRow.add(c)
        }
        mineRow.visibility = if (items.isNotEmpty() && results == null) View.VISIBLE else View.GONE
        if (focusAt != null) (mineRow.strip.getChildAt(focusAt.coerceAtMost(mineRow.size - 1)) ?: recentRow.first() ?: input).requestFocus()
    }

    private fun askRemove(t: TorrApi.Item) {
        AlertDialog.Builder(act, R.style.Lumen_Dialog)
            .setTitle(tr("Убрать торрент?", "Remove the torrent?"))
            .setMessage(tr("«${t.title}» уйдёт из движка вместе с кэшем. История просмотра останется.",
                "“${t.title}” leaves the engine with its cache. The watch history stays."))
            .setPositiveButton(tr("Убрать", "Remove")) { _, _ ->
                mineIo.execute {
                    val base = mineBase ?: return@execute
                    runCatching { TorrApi.remove(base, t.hash) }
                    val items = runCatching { TorrApi.list(base) }.getOrDefault(emptyList())
                    main.post { if (root.isAttachedToWindow) showMine(items) }
                }
            }
            .setNegativeButton(tr("Отмена", "Cancel"), null)
            .show()
    }

    private fun openSources() = act.startActivity(Intent(act, SourcesActivity::class.java))

    override fun focusFirst(): Boolean {
        if (builtFor != true) return findFocusable(root)?.requestFocus() == true
        if (results != null) filmsRow.first()?.let { return it.requestFocus() }
        return input.requestFocus()
    }

    private fun findFocusable(v: View): View? {
        if (v.isFocusable && v.visibility == View.VISIBLE) return v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) findFocusable(v.getChildAt(i))?.let { return it }
        return null
    }

    override fun onBack(): Boolean {
        if (searching) {   // «Назад» во время поиска: отменить его
            seq++; searching = false
            setStatus(null)
            return true
        }
        if (results != null) {
            seq++
            input.requestFocus()
            showIdle(keepFocus = false)
            host.backdrop(null)
            return true
        }
        return false
    }

    companion object {
        private const val WHO = "torrents-pane"

        private fun recent(ctx: Context): List<String> =
            ctx.getSharedPreferences("torznab", Context.MODE_PRIVATE).getString("recent", "")!!.split('\n').filter { it.isNotEmpty() }

        private fun remember(ctx: Context, q: String) {
            val l = (listOf(q) + recent(ctx).filter { !it.equals(q, true) }).take(10)
            ctx.getSharedPreferences("torznab", Context.MODE_PRIVATE).edit().putString("recent", l.joinToString("\n")).apply()
        }

        private fun forget(ctx: Context, q: String) {
            val l = recent(ctx).filter { !it.equals(q, true) }
            ctx.getSharedPreferences("torznab", Context.MODE_PRIVATE).edit().putString("recent", l.joinToString("\n")).apply()
        }
    }
}

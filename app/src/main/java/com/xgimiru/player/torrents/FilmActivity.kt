package com.xgimiru.player.torrents

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.xgimiru.player.R
import com.xgimiru.player.TorrentActivity
import com.xgimiru.player.net.Meta
import com.xgimiru.player.net.Tmdb
import com.xgimiru.player.ui.Backdrop
import com.xgimiru.player.ui.Ui
import com.xgimiru.player.ui.tr
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Страница фильма или сериала, как в Лампе: фон во весь экран, название, год, рейтинг, жанры,
 * описание; у сериала выбор сезона. Ниже раздачи этого фильма из всех источников пользователя
 * (по названию, оригинальному названию и году), без повторов, лучшие сверху, с быстрыми фильтрами.
 * Раздачу, которую уже выбирали, предлагаем первой. Без TMDB (или из «Все раздачи»): тот же экран
 * для простого запроса, без описания.
 */
class FilmActivity : Activity() {

    companion object {
        fun open(ctx: Context, m: Meta) =
            ctx.startActivity(Intent(ctx, FilmActivity::class.java).putExtra("meta", m.toJson().toString()))

        fun openQuery(ctx: Context, q: String) =
            ctx.startActivity(Intent(ctx, FilmActivity::class.java).putExtra("query", q))

        /** «1 сезон», «2 сезона», «5 сезонов». */
        fun plural(n: Int, one: String, few: String, many: String): String {
            val m10 = n % 10; val m100 = n % 100
            return "$n " + when {
                m10 == 1 && m100 != 11 -> one
                m10 in 2..4 && m100 !in 12..14 -> few
                else -> many
            }
        }
    }

    private val MENU_HINT = tr("   Меню на раздаче: подробнее.", "   Menu on a release: details.")
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val d = { v: Number -> Ui.dp(this, v) }
    private lateinit var backdrop: Backdrop
    private lateinit var metaLine: TextView
    private lateinit var overview: TextView
    private lateinit var status: TextView
    private lateinit var chips: LinearLayout
    private lateinit var list: ListView
    private val adapter = Adapter()

    private var meta: Meta? = null
    private var query: String? = null
    private var details: Tmdb.Details? = null
    private lateinit var pickKey: String

    private var all: List<Release> = emptyList()          // всё найденное
    private var relevant: List<Release> = emptyList()     // то, что относится к этому фильму
    private var scores: Map<String, Double> = emptyMap()
    private var rows: List<Release> = emptyList()         // после фильтров, без выбранной раздачи
    private var pick: Release? = null                     // «Продолжить с этой раздачей»
    private var errors: List<String> = emptyList()
    private var noSources = false
    private var searching = false
    private var searchedOnce = false
    private val seasonSearched = HashSet<Int>()

    // фильтры
    private var season = 0            // 0: все сезоны
    private var quality = -1          // -1 любое; 2160, 1080, 720, 480 (SD)
    private var hdr = 0               // 0 любое, 1 HDR или DV, 2 только DV, 3 без HDR
    private var voice: String? = null
    private var sort = 0              // 0 лучшие, 1 сиды, 2 размер больше, 3 размер меньше, 4 новые

    private var seasonChip: TextView? = null
    private lateinit var qualityChip: TextView
    private lateinit var hdrChip: TextView
    private lateinit var voiceChip: TextView
    private lateinit var sortChip: TextView

    private var lastChip: View? = null
    private var userMoved = false
    private var focusPickOnReturn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this)
        meta = intent.getStringExtra("meta")?.let { runCatching { Meta.fromJson(JSONObject(it)) }.getOrNull() }
        query = intent.getStringExtra("query")?.trim()
        val m = meta
        if (m == null && query.isNullOrEmpty()) { finish(); return }
        pickKey = m?.let { (if (it.tv) "tv" else "m") + it.id } ?: ("q:" + Tags.norm(query!!))

        val root = FrameLayout(this)
        backdrop = Backdrop(this)
        root.addView(backdrop, Ui.frame(-1, -1))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(d(56), d(40), d(56), d(10)); clipChildren = false; clipToPadding = false }
        val eyebrow = Ui.text(this, 13f, Ui.INK2, Ui.bold()).apply { letterSpacing = 0.16f }
        val title = Ui.text(this, 36f, Ui.INK, Ui.serif(), 2)
        metaLine = Ui.text(this, 15f, Ui.INK2, Ui.sans(), 2)
        overview = Ui.text(this, 15f, Ui.INK2, Ui.sans(), 3).apply { setLineSpacing(0f, 1.3f) }
        col.addView(eyebrow)
        col.addView(title, Ui.linear(d(760), -2).apply { topMargin = d(8); bottomMargin = d(8) })
        col.addView(metaLine, Ui.linear(d(760), -2))
        col.addView(overview, Ui.linear(d(620), -2).apply { topMargin = d(10) })

        chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false; setPadding(d(4), d(6), d(40), d(6)) }
        col.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false
            addView(chips)
        }, Ui.linear(-1, -2).apply { topMargin = d(14); leftMargin = -d(4) })
        status = Ui.text(this, 14f, Ui.INK3, Ui.sans(), 2)
        col.addView(status, Ui.linear(-1, -2).apply { topMargin = d(6); leftMargin = d(4) })

        list = ListView(this).apply {
            divider = null
            // выбранная раздача заметна издалека: светлая рамка
            selector = Ui.rounded(0x2EF4EFE6, d(12).toFloat(), d(2), Ui.INK)
            isVerticalScrollBarEnabled = false
            itemsCanFocus = false
            adapter = this@FilmActivity.adapter
            setOnItemClickListener { _, _, p, _ -> item(p)?.let { open(it) } }
            // долгое нажатие OK: всё о раздаче (название целиком, трекеры, озвучки)
            setOnItemLongClickListener { _, _, p, _ -> item(p)?.let { about(it) }; true }
            // вверх с первой раздачи: на тот фильтр, где были в последний раз
            setOnKeyListener { _, code, ev ->
                if (ev.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_UP && selectedItemPosition <= 0) {
                    (lastChip ?: chips.getChildAt(0))?.requestFocus(); true
                } else false
            }
            // в списке описание прячем: раздачам больше места
            setOnFocusChangeListener { _, f -> overview.visibility = if (f || overview.text.isNullOrEmpty()) View.GONE else View.VISIBLE }
        }
        col.addView(list, Ui.linear(-1, 0, 1f).apply { topMargin = d(6) })
        root.addView(col, Ui.frame(-1, -1))
        setContentView(root)

        if (m != null) {
            eyebrow.text = if (m.tv) tr("СЕРИАЛ", "SERIES") else tr("ФИЛЬМ", "MOVIE")
            title.text = m.title
            overview.text = m.overview ?: ""
            overview.visibility = if (m.overview.isNullOrEmpty()) View.GONE else View.VISIBLE
            backdrop.show(m.backdrop("w1280"))
        } else {
            eyebrow.text = tr("РАЗДАЧИ ПО ЗАПРОСУ", "RELEASES FOR")
            title.text = query
            overview.visibility = View.GONE
        }
        fillMeta()

        Picks.get(this, pickKey)?.let { p ->
            pick = p.release
            if (m?.tv == true && p.season > 0) season = p.season
        }
        buildChips()
        apply()
        // первый фокус: выбранная раньше раздача, иначе первый фильтр; результаты придут: фокус на лучшую
        list.post { if (pick != null) { list.requestFocus(); list.setSelection(0) } else chips.getChildAt(0)?.requestFocus() }

        if (m != null) Thread {
            val det = Tmdb.details(this, m)
            main.post { if (!isDestroyed && det != null) { details = det; fillMeta() } }
        }.start()
        search()
    }

    override fun onResume() {
        super.onResume()
        backdrop.resume()
        if (focusPickOnReturn) { focusPickOnReturn = false; list.requestFocus(); list.setSelection(0) }
    }

    override fun onPause() { backdrop.pause(); super.onPause() }

    override fun onDestroy() { io.shutdownNow(); main.removeCallbacksAndMessages(null); super.onDestroy() }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU && list.hasFocus()) { item(list.selectedItemPosition)?.let { about(it) }; return true }
        return super.onKeyDown(keyCode, event)
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_DOWN && e.keyCode in setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)) userMoved = true
        return super.dispatchKeyEvent(e)
    }

    /** Строка под названием: оригинальное название, год, рейтинг, жанры, длительность или сезоны. */
    private fun fillMeta() {
        val m = meta
        if (m == null) { metaLine.visibility = View.GONE; return }
        val det = details
        val parts = ArrayList<String>()
        m.original?.takeIf { !it.equals(m.title, true) }?.let { parts += it }
        m.year?.let { parts += it }
        if (m.rating > 0) parts += "★ " + String.format(Locale.US, "%.1f", m.rating)
        det?.genres?.take(3)?.takeIf { it.isNotEmpty() }?.let { parts += it.joinToString(", ") { g -> g.lowercase() } }
        if (det != null) {
            if (m.tv && det.seasons.isNotEmpty()) parts += tr(plural(det.seasons.size, "сезон", "сезона", "сезонов"),
                if (det.seasons.size == 1) "1 season" else "${det.seasons.size} seasons")
            else if (!m.tv && det.runtime > 0) parts += tr("${det.runtime / 60} ч ${det.runtime % 60} мин", "${det.runtime / 60} h ${det.runtime % 60} min")
        }
        metaLine.text = parts.joinToString("  ·  ")
    }

    // ── Фильтры ──
    private fun chip(onClick: () -> Unit): TextView = Ui.text(this, 15f, Ui.INK, Ui.medium()).apply {
        gravity = Gravity.CENTER
        setPadding(d(18), d(8), d(18), d(8))
        isFocusable = true; isClickable = true
        background = Ui.rounded(0x24F4EFE6, d(30).toFloat())
        setOnFocusChangeListener { v, f -> paintChip(v as TextView, f); if (f) lastChip = v }
        setOnClickListener { onClick() }
    }.also { chips.addView(it, Ui.linear(-2, -2).apply { rightMargin = d(10) }) }

    /** Фильтр не «любой»: подпись тёплым цветом, чтобы было видно, что список сужен. */
    private fun paintChip(v: TextView, f: Boolean) {
        val active = v.tag == true
        v.background = Ui.rounded(if (f) Ui.INK else 0x24F4EFE6, d(30).toFloat())
        v.setTextColor(if (f) Ui.GROUND else if (active) Ui.ACCENT else Ui.INK)
    }

    private fun label(v: TextView, text: String, active: Boolean) {
        v.text = text; v.tag = active; paintChip(v, v.isFocused)
    }

    private fun buildChips() {
        chips.removeAllViews()
        if (meta?.tv == true) seasonChip = chip { chooseSeason() }
        qualityChip = chip {
            val opts = listOf(-1) + listOf(2160, 1080, 720, 480).filter { q -> relevant.any { qualityOf(it) == q } }
            quality = opts[(opts.indexOf(quality) + 1) % opts.size]
            refreshChips(); apply()
        }
        hdrChip = chip {
            val any = relevant.any { it.tags.anyHdr }
            val opts = listOf(0) + (if (any) listOf(1) else emptyList()) + (if (relevant.any { it.tags.dv }) listOf(2) else emptyList()) + (if (any) listOf(3) else emptyList())
            hdr = opts[(opts.indexOf(hdr).coerceAtLeast(0) + 1) % opts.size]
            refreshChips(); apply()
        }
        voiceChip = chip { chooseVoice() }
        sortChip = chip { sort = (sort + 1) % 5; refreshChips(); apply() }
        chip { startActivity(Intent(this, SourcesActivity::class.java)) }.apply { text = tr("Источники", "Sources") }
        refreshChips()
    }

    private fun refreshChips() {
        seasonChip?.let { label(it, if (season == 0) tr("Все сезоны", "All seasons") else tr("Сезон $season", "Season $season"), false) }
        label(qualityChip, when (quality) { 2160 -> "4K"; 1080 -> "1080p"; 720 -> "720p"; 480 -> "SD"; else -> tr("Любое качество", "Any quality") }, quality != -1)
        label(hdrChip, when (hdr) { 1 -> tr("HDR или DV", "HDR or DV"); 2 -> "Dolby Vision"; 3 -> tr("Без HDR", "No HDR"); else -> tr("HDR: любое", "HDR: any") }, hdr != 0)
        label(voiceChip, voice ?: tr("Любая озвучка", "Any voice-over"), voice != null)
        label(sortChip, when (sort) {
            1 -> tr("Больше сидов", "Most seeders"); 2 -> tr("Сначала большие", "Largest first")
            3 -> tr("Сначала небольшие", "Smallest first"); 4 -> tr("Сначала новые", "Newest first")
            else -> tr("Сначала лучшие", "Best first")
        }, false)
    }

    private fun qualityOf(r: Release) = r.tags.quality.let { if (it in 1..719) 480 else it }

    private fun chooseSeason() {
        val fromTmdb = details?.seasons.orEmpty()
        // сезоны из TMDB, а без него те, что нашлись в названиях раздач
        val nums = (if (fromTmdb.isNotEmpty()) fromTmdb.map { it.number } else relevant.flatMap { it.tags.seasons })
            .filter { it in 1..80 }.distinct().sorted()
        val labels = listOf(tr("Все сезоны", "All seasons")) + nums.map { n ->
            val s = fromTmdb.firstOrNull { it.number == n }
            listOfNotNull(tr("Сезон $n", "Season $n"),
                s?.episodes?.takeIf { it > 0 }?.let { tr(plural(it, "серия", "серии", "серий"), "$it episodes") }, s?.year).joinToString("  ·  ")
        }
        val values = listOf(0) + nums
        dialog(tr("Сезон", "Season"), labels, values.indexOf(season).coerceAtLeast(0)) { i ->
            season = values[i]
            refreshChips(); apply()
            // по общему запросу мало раздач этого сезона: ищем ещё и сезон отдельно
            if (season > 0 && season !in seasonSearched && relevant.count { season in it.tags.seasons } < 3) {
                seasonSearched += season
                search(season)
            }
        }
    }

    private fun chooseVoice() {
        val counts = HashMap<String, Int>()
        for (r in relevant) for (v in r.tags.voices) counts[v] = (counts[v] ?: 0) + 1
        if (counts.isEmpty()) { status.text = tr("У раздач не указаны озвучки.", "The releases name no voice-overs."); return }
        val voices = counts.entries.sortedByDescending { it.value }.map { it.key }
        val labels = listOf(tr("Любая озвучка", "Any voice-over")) + voices.map { "$it  ·  ${counts[it]}" }
        dialog(tr("Озвучка", "Voice-over"), labels, (voices.indexOf(voice) + 1).coerceAtLeast(0)) { i ->
            voice = if (i == 0) null else voices[i - 1]
            refreshChips(); apply()
        }
    }

    private fun dialog(title: String, items: List<String>, checked: Int, done: (Int) -> Unit) {
        AlertDialog.Builder(this, R.style.Lumen_Dialog)
            .setTitle(title)
            .setSingleChoiceItems(items.toTypedArray(), checked) { dlg, i -> dlg.dismiss(); done(i) }
            .setNegativeButton(tr("Отмена", "Cancel"), null)
            .show()
    }

    // ── Поиск ──
    private fun search(seasonQuery: Int = 0) {
        val m = meta
        val queries: List<String>
        val hints: Hints?
        if (m != null) {
            val names = listOfNotNull(m.title, m.original).distinctBy { Tags.norm(it) }
            queries = when {
                seasonQuery > 0 -> listOf(String.format(Locale.US, "%s S%02d", m.original ?: m.title, seasonQuery))
                !m.tv && m.year != null -> names.map { "$it ${m.year}" }
                else -> names
            }
            hints = Hints(m.title, m.original, m.year, m.tv)
        } else {
            queries = listOf(query!!)
            hints = null
        }
        searching = true
        updateStatus()
        io.execute {
            val out = Sources.searchAll(this, queries, hints)
            val merged = LinkedHashMap<String, Release>()
            for (r in all + out.releases) { val o = merged[r.key]; if (o == null || r.seeders > o.seeders) merged[r.key] = r }
            val found = merged.values.toList()
            val rel = relevantOf(found)
            // разбор названий и оценка: здесь, в фоне: в главном потоке только фильтры по готовому
            val sc = HashMap<String, Double>(rel.size * 2)
            for (r in rel) sc[r.key] = Tags.score(r)
            main.post {
                if (isDestroyed) return@post
                all = found; relevant = rel; scores = sc
                errors = out.errors; noSources = out.sources == 0
                searching = false; searchedOnce = true
                pick?.let { p -> found.firstOrNull { it.key == p.key }?.let { pick = it } }
                apply()
                if (!userMoved && rows.isNotEmpty() && !list.hasFocus()) { list.requestFocus(); list.setSelection(0) }
            }
        }
    }

    /** Раздачи именно этого фильма: в названии есть название или оригинал; у фильма ещё и год ±1. */
    private fun relevantOf(found: List<Release>): List<Release> {
        val m = meta ?: return found
        val names = (listOfNotNull(m.title, m.original) +
            (if (!m.tv) listOfNotNull(m.title, m.original).map { it.substringBefore(':') }.filter { it.trim().length >= 4 } else emptyList()))
            .map { " " + Tags.norm(it) + " " }.filter { it.isNotBlank() }.distinct()
        val year = m.year?.toIntOrNull()
        val byYear = if (m.tv) found else found.filter { Tags.yearFits(it.tags, year) }
        val rel = byYear.filter { r -> val t = " " + Tags.norm(r.title) + " "; names.any { t.contains(it) } }
        return rel.ifEmpty { byYear }
    }

    private fun apply() {
        var l = relevant.asSequence()
        if (season > 0) l = l.filter { it.tags.seasons.isEmpty() || season in it.tags.seasons }
        if (quality != -1) l = l.filter { qualityOf(it) == quality }
        when (hdr) {
            1 -> l = l.filter { it.tags.anyHdr }
            2 -> l = l.filter { it.tags.dv }
            3 -> l = l.filter { !it.tags.anyHdr }
        }
        voice?.let { v -> l = l.filter { v in it.tags.voices } }
        val p = pick
        if (p != null) l = l.filter { it.key != p.key }
        val sorted = when (sort) {
            1 -> l.sortedByDescending { it.seeders }
            2 -> l.sortedByDescending { it.size }
            3 -> l.sortedBy { it.size }
            4 -> l.sortedByDescending { it.time }
            // раздача ровно этого сезона выше сборников, сборники выше раздач без пометки сезона
            else -> l.sortedByDescending { r ->
                val ss = r.tags.seasons
                (scores[r.key] ?: 0.0) + when { season == 0 -> 0; ss.size == 1 && season in ss -> 15; season in ss -> 6; else -> 0 }
            }
        }
        rows = sorted.toList()
        adapter.notifyDataSetChanged()
        updateStatus()
    }

    private fun updateStatus() {
        val filtered = rows.size + (if (pick != null) 1 else 0)
        val total = relevant.size
        status.text = when {
            noSources && !searching -> tr("Источники поиска не добавлены: откройте «Источники».", "No search sources yet: open Sources.")
            searching && total == 0 -> tr("Ищу раздачи…", "Looking for releases…")
            total == 0 && searchedOnce -> (errors.firstOrNull() ?: tr("Раздач не нашлось.", "No releases found."))
            searching -> tr("Раздач: $total. Ищу ещё…", "Releases: $total. Looking for more…")
            rows.isEmpty() && total > 0 -> tr("С этими фильтрами раздач нет: всего $total.", "No releases with these filters, $total in total.")
            filtered < total -> tr("Раздач: $total, показано $filtered.", "Releases: $total, shown $filtered.") + MENU_HINT
            else -> tr("Раздач: $total.", "Releases: $total.") + MENU_HINT
        } + (if (errors.isNotEmpty() && total > 0) "  " + errors.joinToString("; ") else "")
    }

    private fun item(p: Int): Release? {
        val off = if (pick != null) 1 else 0
        return if (p < off) pick else rows.getOrNull(p - off)
    }

    private fun open(r: Release) {
        val s = if (season > 0) season else r.tags.seasons.singleOrNull() ?: 0
        Picks.save(this, pickKey, r, s)
        if (pick?.key != r.key) { pick = r; apply(); focusPickOnReturn = true }
        val m = meta
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.link)).setClass(this, TorrentActivity::class.java)
            .putExtra(TorrentActivity.EXTRA_TITLE, m?.let { it.title + (it.year?.let { y -> " ($y)" } ?: "") })
            .putExtra(TorrentActivity.EXTRA_POSTER, m?.poster("w342"))
            .putExtra(TorrentActivity.EXTRA_BACKDROP, m?.backdrop("w1280"))
            .putExtra(TorrentActivity.EXTRA_SEASON, s))
    }

    /** Подробно о раздаче: название не обрезано, все трекеры и озвучки. Меню или долгое OK. */
    private fun about(r: Release) {
        val g = r.tags
        val lines = listOfNotNull(
            (g.badges + listOfNotNull(g.seasons.takeIf { it.isNotEmpty() }?.let { ReleaseRow.seasonsLabel(it) })).joinToString("  ·  ").ifEmpty { null },
            humanSize(r.size).ifEmpty { null }?.let { tr("Размер: $it", "Size: $it") },
            tr("Сиды: ${r.seeders}, пиры: ${r.peers}", "Seeders: ${r.seeders}, peers: ${r.peers}"),
            g.voices.takeIf { it.isNotEmpty() }?.let { tr("Озвучка: ", "Voice-over: ") + it.joinToString(", ") },
            r.tracker?.let { tr("Трекеры: $it", "Trackers: $it") },
        )
        AlertDialog.Builder(this, R.style.Lumen_Dialog)
            .setTitle(r.title)
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton(tr("Смотреть", "Watch")) { _, _ -> open(r) }
            .setNegativeButton(tr("Закрыть", "Close"), null)
            .show()
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = rows.size + (if (pick != null) 1 else 0)
        override fun getItem(p: Int): Any? = item(p)
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, c: View?, parent: ViewGroup?): View {
            val row = (c as? ReleaseRow) ?: ReleaseRow(this@FilmActivity).apply { layoutParams = AbsListView.LayoutParams(-1, -2) }
            item(p)?.let { row.bind(it, pick = pick != null && p == 0) }
            return row
        }
    }
}

package com.xgimiru.player

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.xgimiru.player.torrents.humanSize
import com.xgimiru.player.ui.Backdrop
import com.xgimiru.player.ui.Ui
import com.xgimiru.player.ui.tr
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Открывает magnet-ссылку или .torrent-файл: отдаёт торрент движку (встроенному или внешнему
 * TorrServe, см. [TorrSettings]), ждёт список файлов и показывает видео из него. Одно видео
 * запускается сразу; из нескольких выбираем, остальные идут в плеер списком серий.
 */
class TorrentActivity : Activity() {

    private lateinit var backdrop: Backdrop
    private lateinit var heading: TextView
    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var alive = true
    private var builtin = false
    private val who get() = "torrent@" + System.identityHashCode(this)
    private fun d(v: Number) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this)
        val frame = FrameLayout(this)
        backdrop = Backdrop(this)
        frame.addView(backdrop, Ui.frame(-1, -1))
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(56), d(44), d(56), d(24))
        }
        root.addView(Ui.text(this, 13f, Ui.INK2, Ui.bold()).apply { letterSpacing = 0.16f; text = tr("ТОРРЕНТ", "TORRENT") })
        heading = Ui.text(this, 32f, Ui.INK, Ui.serif(), 2).apply { text = intent.getStringExtra(EXTRA_TITLE) ?: tr("Торрент", "Torrent") }
        root.addView(heading, Ui.linear(d(780), -2).apply { topMargin = d(8) })
        status = Ui.text(this, 16f, Ui.INK2, Ui.sans(), 3).apply { setLineSpacing(0f, 1.25f) }
        root.addView(status, Ui.linear(d(780), -2).apply { topMargin = d(10); bottomMargin = d(16) })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            addView(list)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        frame.addView(root, Ui.frame(-1, -1))
        setContentView(frame)
        backdrop.show(intent.getStringExtra(EXTRA_BACKDROP))

        val uri = intent.data ?: run { finish(); return }
        builtin = TorrSettings.builtin(this)
        io.execute { load(uri) }
    }

    override fun onStart() {
        super.onStart()
        if (builtin) TorrService.hold(this, who)
    }

    override fun onResume() { super.onResume(); backdrop.resume() }
    override fun onPause() { backdrop.pause(); super.onPause() }

    override fun onStop() {
        super.onStop()
        if (builtin) TorrService.release(this, who)
    }

    override fun onDestroy() {
        alive = false
        io.shutdownNow()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun say(text: String) {
        handler.post { status.text = text }
    }

    private fun fail(text: String) {
        handler.post {
            status.text = text
            status.setTextColor(Ui.ACCENT)
        }
    }

    // ── В фоне ───────────────────────────────────────────────────────────────
    private fun load(uri: Uri) {
        try {
            val base = if (builtin) {
                say(tr("Запускаю торрент-движок…", "Starting the torrent engine…"))
                TorrEngine.start(this)
            } else {
                val addr = TorrSettings.external(this)
                    ?: return fail(tr("Не задан адрес TorrServe. Укажите его в настройках или включите встроенный движок.",
                        "No TorrServe address. Set it in Settings or turn on the built-in engine."))
                say(tr("Подключаюсь к TorrServe $addr…", "Connecting to TorrServe $addr…"))
                if (!TorrApi.alive(addr)) return fail(tr("TorrServe не отвечает по адресу $addr. Проверьте, что он запущен.",
                    "TorrServe does not answer at $addr. Check that it is running."))
                addr
            }
            say(tr("Добавляю торрент…", "Adding the torrent…"))
            val added = when (uri.scheme?.lowercase()) {
                "magnet", "http", "https" -> TorrApi.add(base, uri.toString(), intent.getStringExtra(EXTRA_TITLE), intent.getStringExtra(EXTRA_POSTER))
                else -> TorrApi.upload(base, readTorrent(uri), displayName(uri))
            }
            val hash = added.optString("hash").ifEmpty { return fail(tr("Движок не принял торрент.", "The engine did not accept the torrent.")) }
            var st: JSONObject = added
            val started = System.currentTimeMillis()
            while (alive && TorrApi.files(st).isEmpty()) {
                if (st.optInt("stat") == 4) return fail(tr("Не удалось получить данные торрента: никто не раздаёт или нет сети.",
                    "Could not get the torrent data: nobody is seeding or there is no network."))
                if (System.currentTimeMillis() - started > 180_000) return fail(tr("Данные торрента не пришли за 3 минуты. Попробуйте позже.",
                    "The torrent data did not arrive in 3 minutes. Try later."))
                val peers = st.optInt("active_peers").takeIf { it > 0 } ?: st.optInt("total_peers")
                say(tr("Получаю данные торрента…", "Getting the torrent data…") + if (peers > 0) tr("   ·   пиров: $peers", "   ·   peers: $peers") else "")
                Thread.sleep(1000)
                st = TorrApi.status(base, hash)
            }
            if (!alive) return
            val title = st.optString("title").ifBlank { st.optString("name") }.ifBlank { tr("Торрент", "Torrent") }
            val files = TorrApi.files(st)
            val videos = files.filter { isVideo(it.path) }.sortedWith { a, b -> natural(a.path, b.path) }
            val subs = files.filter { isSubtitle(it.path) }
            handler.post { show(base, hash, title, videos, subs) }
        } catch (e: InterruptedException) {
            // экран закрыли
        } catch (t: Throwable) {
            fail(tr("Не получилось открыть торрент: ", "Could not open the torrent: ") + (t.message ?: t.toString()))
        }
    }

    private fun readTorrent(uri: Uri): ByteArray {
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("файл не читается")
        if (bytes.size > 16 * 1024 * 1024 || bytes.isEmpty() || bytes[0] != 'd'.code.toByte()) {
            throw IllegalStateException("это не .torrent-файл")
        }
        return bytes
    }

    private fun displayName(uri: Uri): String {
        val name = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull()
        return name ?: uri.lastPathSegment ?: "file.torrent"
    }

    // ── Список файлов ────────────────────────────────────────────────────────
    /** Что показано в списке: после возврата из плеера список строится заново (новые отметки и фокус). */
    private var shown: (() -> Unit)? = null

    override fun onRestart() {
        super.onRestart()
        shown?.invoke()
    }

    private fun show(base: String, hash: String, title: String, videos: List<TorrApi.FileInfo>, subs: List<TorrApi.FileInfo>) {
        shown = { afterPlayer(hash, videos.map { TorrApi.streamUri(base, hash, it, builtin) }); show(base, hash, title, videos, subs) }
        heading.text = title
        if (videos.isEmpty()) {
            fail(tr("В этом торренте нет видео.", "There is no video in this torrent."))
            return
        }
        val uris = videos.map { TorrApi.streamUri(base, hash, it, builtin) }
        val names = videos.map { Formats.titleFromPath(it.name) }
        // субтитры рядом с видео: «Фильм.ru.srt», «Subs/Фильм.srt» (но не «Фильм1…» для «Фильм»); если видео одно, то все
        fun subsFor(i: Int): List<Uri> {
            val stem = videos[i].name.substringBeforeLast('.').lowercase() + "."
            return subs.filter { videos.size == 1 || it.name.lowercase().startsWith(stem) }
                .map { TorrApi.streamUri(base, hash, it, builtin) }
        }
        fun play(i: Int) { markOpened(hash, i); play(title, uris, names, i, videos.indices.map(::subsFor)) }
        if (videos.size == 1) {
            play(0)
            finish()
            return
        }
        status.text = tr("Видео в торренте: ${videos.size}. Выберите, с чего начать.", "Videos in the torrent: ${videos.size}. Choose where to start.")
        list.removeAllViews()
        val history = History.all(this).associateBy { it.key }
        val seasons = videos.map { Name.parse(it.name).season?.toIntOrNull() ?: Name.parse(it.path.replace('/', ' ')).season?.toIntOrNull() }
        val severalSeasons = seasons.filterNotNull().distinct().size > 1
        val rows = arrayOfNulls<View>(videos.size)
        var lastSeason: Int? = null
        videos.forEachIndexed { i, f ->
            // в торренте несколько сезонов: заголовок перед первой серией каждого
            if (severalSeasons && seasons[i] != null && seasons[i] != lastSeason) {
                lastSeason = seasons[i]
                list.addView(Ui.text(this, 13f, Ui.ACCENT, Ui.bold()).apply {
                    letterSpacing = 0.14f
                    text = tr("СЕЗОН ${seasons[i]}", "SEASON ${seasons[i]}")
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d(if (list.childCount == 0) 0 else 14); bottomMargin = d(6); leftMargin = d(20) })
            }
            val row = row(names[i], f, history[History.key(uris[i])]) { play(i) }
            rows[i] = row
            list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = d(4) })
        }
        val focus = resumeIndex(hash, uris, history, seasons, intent.getIntExtra(EXTRA_SEASON, 0))
        list.post { rows[focus]?.requestFocus() }
    }

    /**
     * С какой серии продолжить. Плеер убирает досмотренную серию из истории, поэтому кроме истории
     * помним, какую серию открывали отсюда ([opened]). Последнее по времени и решает: недосмотренная
     * серия из истории или открытая отсюда (а если её досмотрели, то следующая). Ничего не смотрели,
     * а на странице сериала выбран сезон: первая серия этого сезона.
     */
    private fun resumeIndex(hash: String, uris: List<Uri>, history: Map<String, History.Entry>, seasons: List<Int?>, season: Int): Int {
        var focus = -1
        var latest = 0L
        uris.forEachIndexed { i, u ->
            val e = history[History.key(u)]
            if (e != null && e.updated > latest) { latest = e.updated; focus = i }
        }
        val rec = opened(hash)
        if (rec != null && rec.second > latest && rec.first in uris.indices) return rec.first
        if (focus >= 0) return focus
        if (season > 0) seasons.indexOfFirst { it == season }.takeIf { it >= 0 }?.let { return it }
        return 0
    }

    private fun prefs() = getSharedPreferences("torrent_opened", MODE_PRIVATE)

    /** Какую серию торрента открывали отсюда последней и когда. */
    private fun opened(hash: String): Pair<Int, Long>? = prefs().getString(hash, null)?.split('|')?.let {
        val i = it.getOrNull(0)?.toIntOrNull(); val t = it.getOrNull(1)?.toLongOrNull()
        if (i != null && t != null) i to t else null
    }

    private fun markOpened(hash: String, i: Int) = prefs().edit().putString(hash, "$i|${System.currentTimeMillis()}").apply()

    /**
     * Вернулись из плеера. Серии, открытой отсюда, в истории нет, а смотрели дольше 10 минут: её
     * досмотрели (плеер убрал её из истории), продолжать со следующей.
     */
    private fun afterPlayer(hash: String, uris: List<Uri>) {
        val rec = opened(hash) ?: return
        val history = History.all(this).associateBy { it.key }
        val newer = uris.any { (history[History.key(it)]?.updated ?: 0L) >= rec.second }
        if (!newer && System.currentTimeMillis() - rec.second > 10 * 60_000L && rec.first + 1 < uris.size) markOpened(hash, rec.first + 1)
    }

    private fun row(name: String, f: TorrApi.FileInfo, e: History.Entry?, onClick: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(d(20), d(13), d(20), d(13))
            isFocusable = true
            isClickable = true
            val r = d(12).toFloat()
            val nameView = Ui.text(this@TorrentActivity, 18f, Ui.INK, Ui.medium()).apply { text = name }
            val side = Ui.text(this@TorrentActivity, 15f, Ui.INK3).apply {
                text = when {
                    e == null -> size(f.length)
                    e.finished -> tr("просмотрено", "watched")
                    else -> tr("осталось ${Time.human(e.duration - e.position)}", "${Time.human(e.duration - e.position)} left")
                }
                setPadding(d(16), 0, 0, 0)
                if (e != null && !e.finished) setTextColor(Ui.ACCENT)
            }
            addView(nameView, LinearLayout.LayoutParams(0, -2, 1f))
            addView(side)
            setOnFocusChangeListener { _, has ->
                background = if (has) Ui.rounded(Ui.INK, r) else null
                nameView.setTextColor(if (has) Ui.GROUND else Ui.INK)
                side.setTextColor(if (has) Ui.GROUND else if (e != null && !e.finished) Ui.ACCENT else Ui.INK3)
            }
            setOnClickListener { onClick() }
        }

    /** subs: субтитры каждой серии, по списку на серию. */
    private fun play(title: String, uris: List<Uri>, names: List<String>, i: Int, subs: List<List<Uri>>) {
        val intent = Intent(this, PlayerActivity::class.java)
            .setData(uris[i])
            .putExtra("title", if (uris.size == 1) names[0] else title)
        if (subs[i].isNotEmpty()) intent.putExtra("subs", subs[i].toTypedArray<Parcelable>())
        if (uris.size > 1) {
            intent.putExtra("video_list", uris.toTypedArray<Parcelable>())
            intent.putExtra("video_list.name", names.toTypedArray())
            intent.putExtra("video_list.subs", subs.map { it.joinToString("\n") }.toTypedArray())
        }
        startActivity(intent)
    }

    private fun size(bytes: Long): String = humanSize(bytes).ifEmpty { tr("${(bytes shr 10).coerceAtLeast(1)} КБ", "${(bytes shr 10).coerceAtLeast(1)} KB") }

    companion object {
        /** От страницы фильма: название и постер для движка, фон, выбранный сезон. */
        const val EXTRA_TITLE = "lumen.title"
        const val EXTRA_POSTER = "lumen.poster"
        const val EXTRA_BACKDROP = "lumen.backdrop"
        const val EXTRA_SEASON = "lumen.season"

        private val VIDEO = setOf(
            "mkv", "mp4", "m4v", "avi", "ts", "m2ts", "mts", "mov", "wmv", "asf", "webm", "mpg", "mpeg",
            "vob", "flv", "divx", "3gp", "ogm", "rm", "rmvb", "m2v",
        )

        private val SUBTITLES = setOf("srt", "ass", "ssa", "vtt")

        fun isVideo(path: String) = path.substringAfterLast('.', "").lowercase() in VIDEO

        fun isSubtitle(path: String) = path.substringAfterLast('.', "").lowercase() in SUBTITLES

        /** «Серия 2» раньше «Серия 10». */
        fun natural(a: String, b: String): Int {
            val re = Regex("\\d+|\\D+")
            val x = re.findAll(a.lowercase()).map { it.value }.toList()
            val y = re.findAll(b.lowercase()).map { it.value }.toList()
            for (k in 0 until minOf(x.size, y.size)) {
                val p = x[k]; val q = y[k]
                val c = if (p[0].isDigit() && q[0].isDigit()) {
                    p.trimStart('0').length.compareTo(q.trimStart('0').length).takeIf { it != 0 }
                        ?: p.trimStart('0').compareTo(q.trimStart('0'))
                } else p.compareTo(q)
                if (c != 0) return c
            }
            return x.size.compareTo(y.size)
        }
    }
}

package com.xgimiru.player

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Открывает magnet-ссылку или .torrent-файл: отдаёт торрент движку (встроенному или внешнему
 * TorrServe, см. [TorrSettings]), ждёт список файлов и показывает видео из него. Одно видео
 * запускается сразу; из нескольких выбираем, остальные идут в плеер списком серий.
 */
class TorrentActivity : Activity() {

    private lateinit var heading: TextView
    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var alive = true
    private var builtin = false
    private val who get() = "torrent@" + System.identityHashCode(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(80), dp(56), dp(80), dp(40))
        }
        heading = TextView(this).apply {
            text = "Торрент"
            textSize = 30f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.WHITE)
        }
        root.addView(heading)
        status = TextView(this).apply {
            textSize = 18f
            setLineSpacing(0f, 1.2f)
            setTextColor(getColor(R.color.text_dim))
            setPadding(0, dp(12), 0, dp(20))
        }
        root.addView(status)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            addView(list)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        val uri = intent.data ?: run { finish(); return }
        builtin = TorrSettings.builtin(this)
        io.execute { load(uri) }
    }

    override fun onStart() {
        super.onStart()
        if (builtin) TorrService.hold(this, who)
    }

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
            status.setTextColor(getColor(R.color.accent_warm))
        }
    }

    // ── В фоне ───────────────────────────────────────────────────────────────
    private fun load(uri: Uri) {
        try {
            val base = if (builtin) {
                say("Запускаю торрент-движок…")
                TorrEngine.start(this)
            } else {
                val addr = TorrSettings.external(this)
                    ?: return fail("Не задан адрес TorrServe. Укажите его в настройках «Кино» или включите встроенный движок.")
                say("Подключаюсь к TorrServe $addr…")
                if (!TorrApi.alive(addr)) return fail("TorrServe не отвечает по адресу $addr. Проверьте, что он запущен.")
                addr
            }
            say("Добавляю торрент…")
            val added = when (uri.scheme?.lowercase()) {
                "magnet", "http", "https" -> TorrApi.add(base, uri.toString())
                else -> TorrApi.upload(base, readTorrent(uri), displayName(uri))
            }
            val hash = added.optString("hash").ifEmpty { return fail("Движок не принял торрент.") }
            var st: JSONObject = added
            val started = System.currentTimeMillis()
            while (alive && TorrApi.files(st).isEmpty()) {
                if (st.optInt("stat") == 4) return fail("Не удалось получить данные торрента: никто не раздаёт или нет сети.")
                if (System.currentTimeMillis() - started > 180_000) return fail("Данные торрента не пришли за 3 минуты. Попробуйте позже.")
                val peers = st.optInt("active_peers").takeIf { it > 0 } ?: st.optInt("total_peers")
                say("Получаю данные торрента…" + if (peers > 0) "   ·   пиров: $peers" else "")
                Thread.sleep(1000)
                st = TorrApi.status(base, hash)
            }
            if (!alive) return
            val title = st.optString("title").ifBlank { st.optString("name") }.ifBlank { "Торрент" }
            val files = TorrApi.files(st)
            val videos = files.filter { isVideo(it.path) }.sortedWith { a, b -> natural(a.path, b.path) }
            val subs = files.filter { isSubtitle(it.path) }
            handler.post { show(base, hash, title, videos, subs) }
        } catch (e: InterruptedException) {
            // экран закрыли
        } catch (t: Throwable) {
            fail("Не получилось открыть торрент: " + (t.message ?: t.toString()))
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
    private fun show(base: String, hash: String, title: String, videos: List<TorrApi.FileInfo>, subs: List<TorrApi.FileInfo>) {
        heading.text = title
        if (videos.isEmpty()) {
            fail("В этом торренте нет видео.")
            return
        }
        val uris = videos.map { TorrApi.streamUri(base, hash, it, builtin) }
        val names = videos.map { Formats.titleFromPath(it.name) }
        // субтитры рядом с видео: «Фильм.ru.srt», «Subs/Фильм.srt»; если видео одно, то все
        fun subsFor(i: Int): List<Uri> {
            val stem = videos[i].name.substringBeforeLast('.').lowercase()
            return subs.filter { videos.size == 1 || it.name.lowercase().startsWith(stem) }
                .map { TorrApi.streamUri(base, hash, it, builtin) }
        }
        fun play(i: Int) = play(title, uris, names, i, subsFor(i))
        if (videos.size == 1) {
            play(0)
            finish()
            return
        }
        status.text = "Видео в торренте: ${videos.size}. Выберите, с чего начать."
        list.removeAllViews()
        val history = History.all(this).associateBy { it.key }
        var focus = 0
        var latest = 0L
        videos.forEachIndexed { i, f ->
            val e = history[History.key(uris[i])]
            if (e != null && e.updated > latest) { latest = e.updated; focus = i }
            list.addView(row(names[i], f, e) { play(i) },
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        list.getChildAt(focus)?.requestFocus()
    }

    private fun row(name: String, f: TorrApi.FileInfo, e: History.Entry?, onClick: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = getDrawable(R.drawable.history_card)
            setPadding(dp(24), dp(14), dp(24), dp(14))
            isFocusable = true
            isClickable = true
            setOnClickListener { onClick() }
            addView(TextView(this@TorrentActivity).apply {
                text = name
                textSize = 20f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(Color.WHITE)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(TextView(this@TorrentActivity).apply {
                text = when {
                    e == null -> size(f.length)
                    e.finished -> "просмотрено"
                    else -> "осталось ${Time.human(e.duration - e.position)}"
                }
                textSize = 15f
                setTextColor(getColor(R.color.text_dim))
                setPadding(dp(16), 0, 0, 0)
            })
        }

    private fun play(title: String, uris: List<Uri>, names: List<String>, i: Int, subs: List<Uri>) {
        val intent = Intent(this, PlayerActivity::class.java)
            .setData(uris[i])
            .putExtra("title", if (uris.size == 1) names[0] else title)
        if (subs.isNotEmpty()) intent.putExtra("subs", subs.toTypedArray<Parcelable>())
        if (uris.size > 1) {
            intent.putExtra("video_list", uris.toTypedArray<Parcelable>())
            intent.putExtra("video_list.name", names.toTypedArray())
        }
        startActivity(intent)
    }

    private fun size(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f ГБ".format(bytes / (1L shl 30).toDouble())
        else -> "${bytes shr 20} МБ"
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
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

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
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.media3.common.util.UnstableApi
import com.xgimiru.player.files.FolderPane
import com.xgimiru.player.media.Fmt
import com.xgimiru.player.settings.playerSettings
import com.xgimiru.player.torrent.TorrentFiles
import com.xgimiru.player.torrent.TorrentSource
import com.xgimiru.player.ui.Nav
import com.xgimiru.player.ui.Rail
import com.xgimiru.player.ui.Ui
import com.xgimiru.player.ui.emptyState
import com.xgimiru.player.ui.tr
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Opens a magnet link or a .torrent file in the built-in TorrServer. The left column tells what the torrent is
 * and how it loads, the right side is the same table as a folder of a stick: the videos of the torrent with
 * their folders. One video plays at once.
 */
@UnstableApi
class TorrentActivity : Activity(), Nav {
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var alive = true
    private val who get() = "torrent@" + System.identityHashCode(this)
    private val p = { v: Number -> Ui.px(this, v) }
    private val ru get() = tr("ru", "en") == "ru"

    private lateinit var content: FrameLayout
    private lateinit var heading: android.widget.TextView
    private lateinit var facts: LinearLayout
    private lateinit var note: android.widget.TextView
    private var pane: FolderPane? = null
    private var base: String? = null
    private var hash: String? = null
    private var files: TorrentFiles? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this)
        window.setBackgroundDrawable(Ui.field())
        val root = FrameLayout(this)
        content = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        root.addView(content, Ui.frame(-1, -1).apply { leftMargin = p(432) })
        root.addView(side(), Ui.frame(p(432), -1))
        setContentView(root)

        val uri = intent.data ?: run { finish(); return }
        heading.text = TorrentFiles.magnetName(uri.toString()) ?: displayName(uri)?.removeSuffix(".torrent") ?: tr("Торрент", "Torrent")
        loading(tr("Запускаю TorrServer…", "Starting TorrServer…"))
        if (!TorrEngine.isAvailable(this)) {
            fail(tr("В этой сборке Lumen Play нет встроенного TorrServer.", "This Lumen Play build has no built-in TorrServer."))
            return
        }
        io.execute { load(uri) }
    }

    /** The left column: brand, what the torrent is, how it loads. */
    private fun side(): View = FrameLayout(this).apply {
        setBackgroundColor(Ui.RAIL)
        addView(Rail.brand(this@TorrentActivity), Ui.frame(-1, p(52)).apply { leftMargin = p(36); rightMargin = p(28); topMargin = p(52) })
        val col = LinearLayout(this@TorrentActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(p(16), 0, 0, 0) }
        col.addView(Ui.t(this@TorrentActivity, 17, 650, Ui.T3, spacing = 2.4f).apply { text = tr("ТОРРЕНТ", "TORRENT") })
        heading = Ui.t(this@TorrentActivity, 34, 680, Ui.T1, 4, spacing = -.5f).apply { setLineSpacing(0f, 1.12f) }
        col.addView(heading, Ui.linear(-1, -2).apply { topMargin = p(16) })
        facts = LinearLayout(this@TorrentActivity).apply { orientation = LinearLayout.VERTICAL }
        col.addView(facts, Ui.linear(-1, -2).apply { topMargin = p(30) })
        addView(col, Ui.frame(-1, -2).apply { leftMargin = p(36); rightMargin = p(28); topMargin = p(150) })
        note = Ui.t(this@TorrentActivity, 19, 450, Ui.T3, 3).apply { setLineSpacing(0f, 1.3f) }
        addView(note, Ui.frame(-1, -2, Gravity.BOTTOM).apply { leftMargin = p(52); rightMargin = p(28); bottomMargin = p(48) })
    }

    private fun fact(k: String, v: String) {
        facts.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Ui.t(this@TorrentActivity, 21, 450, Ui.T3).apply { text = k }, Ui.linear(p(130), -2))
            addView(Ui.t(this@TorrentActivity, 21, 450, Ui.T1, 2).apply { text = v }, Ui.linear(0, -2, 1f))
        }, Ui.linear(-1, -2).apply { if (facts.childCount > 0) topMargin = p(12) })
    }

    /** Videos, size, peers and speed of the torrent. */
    private fun showFacts(st: JSONObject?) {
        facts.removeAllViews()
        files?.let { f ->
            fact(tr("Видео", "Videos"), f.videos.size.toString())
            f.videos.sumOf { it.length }.takeIf { it > 0 }?.let { fact(tr("Размер", "Size"), Fmt.size(it, ru)) }
        }
        if (st != null) {
            val peers = st.optInt("active_peers"); val seeds = st.optInt("connected_seeders")
            fact(tr("Пиры", "Peers"), if (seeds > 0) tr("$peers, раздают $seeds", "$peers, $seeds seeding") else "$peers")
            val speed = st.optDouble("download_speed", 0.0)
            if (speed > 0) fact(tr("Скорость", "Speed"), tr("%.1f МБ/с", "%.1f MB/s").format(speed / 1e6))
        }
    }

    private fun loading(text: String) {
        if (pane != null) return
        content.removeAllViews()
        content.addView(emptyState(this, tr("Открываю торрент", "Opening the torrent"), text, null, null), Ui.at(this, 56, 150))
    }

    private fun fail(text: String) {
        pane = null
        content.removeAllViews()
        val v = emptyState(this, tr("Торрент не открылся", "The torrent did not open"), text, tr("Назад", "Back")) { finish() }
        content.addView(v, Ui.at(this, 56, 150))
        v.post { v.getChildAt(v.childCount - 1)?.requestFocus() }
    }

    override fun onStart() {
        super.onStart()
        TorrService.hold(this, who)
        handler.post(tick)
    }

    override fun onRestart() {
        super.onRestart()
        pane?.onShow()      // back from the player: watched marks and progress
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(tick)
        TorrService.release(this, who)
    }

    override fun onDestroy() {
        alive = false
        io.shutdownNow()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** Peers and speed every 3 seconds while the screen is open. */
    private val tick = object : Runnable {
        override fun run() {
            val b = base; val h = hash
            if (b != null && h != null && files != null) io.execute {
                val st = runCatching { TorrApi.status(b, h) }.getOrNull()
                handler.post { if (alive && files != null) showFacts(st) }
            }
            handler.postDelayed(this, 3000)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && pane?.onBack() == true) return true
        return super.onKeyDown(keyCode, event)
    }

    // Nav: the torrent screen has no left list to open
    override fun open(place: String) {}
    override fun refreshRail() {}
    override fun focusRail() {}

    // ── In the background ──
    private fun say(text: String) = handler.post { if (alive) loading(text) }
    private fun failLater(text: String) { handler.post { if (alive) fail(text) } }

    private fun load(uri: Uri) {
        try {
            val b = TorrEngine.start(this)
            handler.post { note.text = tr("Встроенный TorrServer", "Built-in TorrServer") }
            say(tr("Добавляю торрент…", "Adding the torrent…"))
            val added = when (uri.scheme?.lowercase()) {
                "magnet", "http", "https" -> TorrApi.add(b, uri.toString(), TorrentFiles.magnetName(uri.toString()))
                else -> TorrApi.upload(b, readTorrent(uri), displayName(uri) ?: "file.torrent")
            }
            val h = added.optString("hash").ifEmpty { return failLater(tr("TorrServer не принял этот торрент.", "TorrServer did not take this torrent.")) }
            var st = added
            val started = System.currentTimeMillis()
            while (alive && TorrApi.files(st).isEmpty()) {
                if (st.optInt("stat") == 4) return failLater(tr("Не удалось получить список файлов: никто не раздаёт или нет сети.",
                    "Could not get the file list: nobody is seeding or there is no network."))
                if (System.currentTimeMillis() - started > 180_000) return failLater(tr("Список файлов не пришёл за 3 минуты. Попробуйте позже.",
                    "The file list did not arrive in 3 minutes. Try again later."))
                val peers = st.optInt("active_peers").takeIf { it > 0 } ?: st.optInt("total_peers")
                say(tr("Получаю список файлов…", "Getting the file list…") + if (peers > 0) tr("\nПиров: $peers", "\nPeers: $peers") else "")
                Thread.sleep(1000)
                st = TorrApi.status(b, h)
            }
            if (!alive) return
            val title = st.optString("title").ifBlank { st.optString("name") }.ifBlank { null }
            val tree = TorrentFiles(TorrApi.files(st))
            handler.post { if (alive) show(b, h, title, tree, st) }
        } catch (e: InterruptedException) {
            // the screen was closed
        } catch (t: Throwable) {
            failLater(tr("Не получилось открыть торрент: ", "Could not open the torrent: ") + (t.message ?: t.toString()))
        }
    }

    private fun readTorrent(uri: Uri): ByteArray {
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException(tr("файл не читается", "the file cannot be read"))
        if (!TorrentFiles.looksLikeTorrent(bytes)) throw IllegalStateException(tr("это не файл .torrent", "this is not a .torrent file"))
        return bytes
    }

    private fun displayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.takeIf { uri.scheme != "magnet" }

    // ── The videos ──
    private fun show(b: String, h: String, title: String?, tree: TorrentFiles, st: JSONObject) {
        base = b; hash = h; files = tree
        title?.let { heading.text = it }
        showFacts(st)
        if (tree.videos.isEmpty()) { fail(tr("В этом торренте нет видео.", "There is no video in this torrent.")); return }
        val source = TorrentSource(this, heading.text.toString(), b, h, tree) { tr("Встроенный TorrServer", "Built-in TorrServer") }
        if (tree.videos.size == 1) {
            val v = tree.videos[0]
            val subs = tree.subsFor(v, SubPrep.langs(playerSettings())).map { Uri.parse(source.stream(it)) }
            val i = Intent(this, PlayerActivity::class.java).setData(Uri.parse(source.stream(v))).putExtra("title", heading.text.toString())
            if (subs.isNotEmpty()) i.putExtra("subs", subs.toTypedArray<Parcelable>())
            startActivity(i)
            finish()
            return
        }
        val fp = FolderPane(this, this, source)
        pane = fp
        content.removeAllViews()
        content.addView(fp.view, Ui.frame(-1, -1))
        fp.onShow()
        content.post { fp.focusFirst() }
    }
}

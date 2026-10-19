package com.xgimiru.player

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.xgimiru.player.files.FilesPane
import com.xgimiru.player.home.HomePane
import com.xgimiru.player.torrents.TorrentsPane
import com.xgimiru.player.tv.TvPane
import com.xgimiru.player.ui.Backdrop
import com.xgimiru.player.ui.Host
import com.xgimiru.player.ui.Img
import com.xgimiru.player.ui.Info
import com.xgimiru.player.ui.Pane
import com.xgimiru.player.ui.Ui
import com.xgimiru.player.ui.tr
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lumen Play. Сверху логотип, разделы (Главная, ТВ, Торренты, Файлы), настройки и часы; под ними
 * раздел. Раздел открывается, когда на нём фокус, как вкладки Lumen Home. Фон и блок описания
 * общие: их меняет раздел, когда двигается фокус.
 */
class MainActivity : Activity(), Host {

    private lateinit var backdrop: Backdrop
    private lateinit var info: Info
    private lateinit var content: FrameLayout
    private lateinit var clock: TextView
    private lateinit var update: TextView
    private val tabs = LinkedHashMap<String, TextView>()
    private val panes = HashMap<String, Pane>()
    private var current = "home"
    private val handler = Handler(Looper.getMainLooper())
    private var updateInfo: Updater.Info? = null
    private var updating = false
    private val tick = object : Runnable {
        override fun run() { clock.text = SimpleDateFormat("H:mm", Locale.getDefault()).format(Date()); handler.postDelayed(this, 10_000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this)
        val d = { v: Number -> Ui.dp(this, v) }
        val root = FrameLayout(this)
        backdrop = Backdrop(this)
        root.addView(backdrop, Ui.frame(-1, -1))

        info = Info(this).apply { visibility = View.INVISIBLE }
        root.addView(info, Ui.frame(d(600), -2).apply { leftMargin = d(56); topMargin = d(96) })

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        col.addView(topBar(), Ui.linear(-1, -2))
        update = Ui.text(this, 15f, Ui.GROUND, Ui.medium(), 2).apply {
            setPadding(d(20), d(10), d(20), d(10))
            background = Ui.rounded(Ui.ACCENT, d(24).toFloat())
            isFocusable = true; isClickable = true
            visibility = View.GONE
            setOnClickListener { startUpdate() }
        }
        col.addView(update, Ui.linear(-2, -2).apply { leftMargin = d(56); topMargin = d(4) })
        content = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        col.addView(content, Ui.linear(-1, 0, 1f))
        root.addView(col, Ui.frame(-1, -1))
        setContentView(root)

        panes["home"] = HomePane(this, this) { tab -> select(tab, focusContent = true) }
        panes["tv"] = TvPane(this, this)
        panes["torrents"] = TorrentsPane(this, this)
        panes["files"] = FilesPane(this, this)
        current = savedInstanceState?.getString("tab") ?: "home"
        select(current, focusContent = true, force = true)
        Img.trim(this)
    }

    private fun topBar(): View {
        val d = { v: Number -> Ui.dp(this, v) }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(d(52), d(28), d(52), d(10))
        }
        bar.addView(ImageView(this).apply { setImageResource(R.drawable.ic_app) }, Ui.linear(d(30), d(30)).apply { rightMargin = d(10) })
        bar.addView(Ui.text(this, 19f, Ui.INK, Ui.bold()).apply { text = "Lumen Play" }, Ui.linear(-2, -2).apply { rightMargin = d(28) })
        for ((key, label) in listOf("home" to tr("Главная", "Home"), "tv" to tr("ТВ", "TV"),
                "torrents" to tr("Торренты", "Torrents"), "files" to tr("Файлы", "Files"))) {
            val t = Ui.text(this, 16f, Ui.INK2, Ui.medium()).apply {
                text = label
                setPadding(d(16), d(7), d(16), d(7))
                isFocusable = true; isClickable = true
                setOnFocusChangeListener { _, f -> paintTab(this, key, f); if (f) select(key) }
                setOnClickListener { focusContent() }
                setOnKeyListener { _, code, ev -> if (ev.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_DOWN) focusContent() else false }
            }
            tabs[key] = t
            bar.addView(t, Ui.linear(-2, -2).apply { rightMargin = d(4) })
        }
        bar.addView(View(this), Ui.linear(0, 1, 1f))
        bar.addView(Ui.text(this, 18f, Ui.INK2, Ui.medium()).apply {
            text = "⚙"
            gravity = Gravity.CENTER
            isFocusable = true; isClickable = true
            background = Ui.rounded(0, d(20).toFloat())
            setOnFocusChangeListener { v, f -> v.background = Ui.rounded(if (f) Ui.INK else 0, d(20).toFloat()); setTextColor(if (f) Ui.GROUND else Ui.INK2) }
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }, Ui.linear(d(40), d(40)).apply { rightMargin = d(22) })
        clock = Ui.text(this, 28f, Ui.INK, Ui.serif())
        bar.addView(clock)
        return bar
    }

    private fun paintTab(t: TextView, key: String, focused: Boolean) {
        val d = Ui.dp(this, 22).toFloat()
        t.background = when {
            focused -> Ui.rounded(Ui.INK, d)
            key == current -> Ui.rounded(0x26F4EFE6, d)
            else -> null
        }
        t.setTextColor(if (focused) Ui.GROUND else if (key == current) Ui.INK else Ui.INK2)
    }

    private fun select(key: String, focusContent: Boolean = false, force: Boolean = false) {
        if (key != current || force) {
            panes[current]?.onHide()
            current = key
            content.removeAllViews()
            info.visibility = View.INVISIBLE
            val p = panes[key]!!
            content.addView(p.view, Ui.frame(-1, -1))
            p.onShow()
            for ((k, t) in tabs) paintTab(t, k, t.isFocused)
        }
        if (focusContent) content.post { if (!focusContent()) tabs[key]?.requestFocus() }
    }

    private fun focusContent(): Boolean = panes[current]?.focusFirst() == true

    // ── Host ──
    override fun backdrop(url: String?) = backdrop.show(url)
    override fun info(eyebrow: String?, title: String?, meta: String?, desc: String?, progress: Float) =
        info.set(eyebrow, title, meta, desc, progress)

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (panes[current]?.onBack() == true) return true
            val tab = tabs[current]
            if (tab != null && !tab.isFocused) { tab.requestFocus(); return true }
            if (current != "home") { tabs["home"]?.requestFocus(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
        backdrop.resume()
        panes[current]?.onShow()
        // для проверки на столе: am start ... --es update_url http://127.0.0.1:PORT/kino-update.json
        val testUrl = intent.getStringExtra("update_url")
        if (testUrl != null) Updater.setTestUrl(this, testUrl)
        if (!updating) Updater.check(this, testUrl != null) { showUpdate(it) }
        // встроенный движок для Лампы: поднять, если ещё не работает (после обновления и т. п.)
        if (TorrSettings.shared(this)) TorrService.hold(this, TorrService.SHARED)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        backdrop.pause()
        panes[current]?.onHide()
        super.onPause()
    }

    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); out.putString("tab", current) }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        if (code == FilesPane.REQ) (panes["files"] as? FilesPane)?.onPermission()
    }

    private fun showUpdate(i: Updater.Info?) {
        updateInfo = i
        if (i == null) { update.visibility = View.GONE; return }
        update.text = tr("Доступна версия ${i.versionName}. OK — обновить", "Version ${i.versionName} is available. OK to update")
        update.visibility = View.VISIBLE
    }

    private fun startUpdate() {
        val i = updateInfo ?: return
        if (updating) return
        updating = true
        update.text = tr("Скачиваю версию ${i.versionName}…", "Downloading version ${i.versionName}…")
        Updater.install(this, i,
            progress = { update.text = tr("Скачиваю версию ${i.versionName}: $it%", "Downloading version ${i.versionName}: $it%") },
            fail = {
                updating = false
                update.text = tr("Не получилось обновить: $it. OK — ещё раз", "Update failed: $it. OK to retry")
            })
    }
}

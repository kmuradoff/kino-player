package com.xgimiru.player

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.util.UnstableApi
import com.xgimiru.player.files.FolderPane
import com.xgimiru.player.files.LocalSource
import com.xgimiru.player.files.Places
import com.xgimiru.player.files.ShareSource
import com.xgimiru.player.home.StartPane
import com.xgimiru.player.net.NetShares
import com.xgimiru.player.net.Share
import com.xgimiru.player.net.ShareUri
import com.xgimiru.player.settings.SettingsPane
import com.xgimiru.player.ui.Img
import com.xgimiru.player.ui.Nav
import com.xgimiru.player.ui.Pane
import com.xgimiru.player.ui.Rail
import com.xgimiru.player.ui.Ui
import com.xgimiru.player.ui.tr
import org.videolan.libvlc.Dialog
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.MediaBrowser

/**
 * Lumen Play: the left list of places (Continue, History, USB sticks and the device storage by name,
 * network folders, Add a folder, Settings) and the open place to its right. OK on a place opens it, Right
 * goes into it, Left from its left edge comes back to the list.
 */
@UnstableApi
class MainActivity : Activity(), Nav {
    private lateinit var rail: Rail
    private lateinit var content: FrameLayout
    private lateinit var update: TextView
    private val panes = HashMap<String, Pane>()
    private var current = "cont"
    private val handler = Handler(Looper.getMainLooper())
    private var vlc: LibVLC? = null
    private var discovery: MediaBrowser? = null
    private val found = LinkedHashMap<String, String>()   // server link -> name, found on the network
    private var updateInfo: Updater.Info? = null
    private var updating = false

    private val mounts = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            refreshRail()
            if (current.startsWith("vol:") && current !in rail.ids()) open("cont")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // network share passwords of older builds were stored in plain text: seal them with the key store key
        Thread { runCatching { NetShares.migrate(applicationContext) } }.start()
        Ui.init(this)
        window.setBackgroundDrawable(Ui.field())
        val root = FrameLayout(this)
        content = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        root.addView(content, Ui.frame(-1, -1).apply { leftMargin = Ui.px(this@MainActivity, 432) })
        rail = Rail(this) { open(it) }
        root.addView(rail, Ui.frame(Ui.px(this, 432), -1))
        update = Ui.pill(this, "") { startUpdate() }.apply { visibility = View.GONE }
        root.addView(update, Ui.frame(-2, -2, Gravity.END).apply { topMargin = Ui.px(this@MainActivity, 40); rightMargin = Ui.px(this@MainActivity, 56) })
        setContentView(root)
        intent.getStringExtra("section")?.let { SettingsPane.pending = it to intent.getStringExtra("focus") }
        current = savedInstanceState?.getString("place") ?: intent.getStringExtra("open") ?: "cont"
        refreshRail()
        open(current)
        askStorage()
        Img.trim(this)
        registerReceiver(mounts, IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED); addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED); addAction(Intent.ACTION_MEDIA_EJECT); addDataScheme("file")
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("section")?.let { SettingsPane.pending = it to intent.getStringExtra("focus"); open("set") }
    }

    // ── Places ──
    override fun refreshRail() {
        val cont = History.all(this).count { !it.finished }
        val vols = Places.volumes(this).map { Rail.Item(it.id, it.name, if (it.primary) R.drawable.ic_hard_drive else R.drawable.ic_usb, Places.free(it.dir)) }
        val saved = NetShares.saved(this)
        val shares = saved.map { Rail.Item("share:" + it.uri, it.name, ShareSource.icon(it.uri), Places.shareSub(it.uri)) } +
            found.filter { (u, _) -> saved.none { s -> s.uri.equals(u, true) } }.entries.take(4)
                .map { (u, n) -> Rail.Item("found:$u", n, ShareSource.icon(u), Places.shareSub(u)) }
        rail.set(listOf(
            Rail.Group(tr("Смотреть", "Watch"), listOf(
                Rail.Item("cont", tr("Продолжить", "Continue"), R.drawable.ic_play_arrow, count = cont),
                Rail.Item("hist", tr("История", "History"), R.drawable.ic_history))),
            Rail.Group(tr("Файлы", "Files"), vols),
            Rail.Group(tr("Сеть", "Network"), shares + Rail.Item("add", tr("Добавить папку", "Add a folder"), R.drawable.ic_add)),
        ), Rail.Item("set", tr("Настройки", "Settings"), R.drawable.ic_settings))
        rail.current = current
    }

    override fun open(place: String) = open(place, focus = true)

    private fun open(place: String, focus: Boolean) {
        if (place == "add") { addDialog(); return }
        val pane = panes[place] ?: create(place)?.also { panes[place] = it } ?: run { if (place != "cont") open("cont", focus); return }
        if (place != current || content.childCount == 0) {
            panes[current]?.takeIf { content.childCount > 0 }?.onHide()
            content.removeAllViews()
            content.addView(pane.view, Ui.frame(-1, -1))
        }
        current = place
        rail.current = place
        pane.onShow()
        if (focus) content.post { if (!pane.focusFirst()) rail.focusCurrent() }
    }

    private fun create(id: String): Pane? = when {
        id == "cont" -> StartPane(this, this, historyOnly = false)
        id == "hist" -> StartPane(this, this, historyOnly = true)
        id == "set" -> SettingsPane(this)
        id.startsWith("vol:") -> Places.volumes(this).firstOrNull { it.id == id }?.let { FolderPane(this, this, LocalSource(this, it)) }
        id.startsWith("share:") -> NetShares.saved(this).firstOrNull { "share:" + it.uri == id }?.let {
            FolderPane(this, this, ShareSource(this, id, it.name, it.uri) { libVlc() })
        }
        id.startsWith("found:") -> id.removePrefix("found:").let { u -> FolderPane(this, this, ShareSource(this, id, found[u] ?: u, u) { libVlc() }) }
        else -> null
    }

    override fun focusRail() { rail.focusCurrent() }

    // ── Keys ──
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if ((panes[current] as? SettingsPane)?.onKey(e) == true) return true
        if (e.action == KeyEvent.ACTION_DOWN) {
            val f = currentFocus
            when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> if (f != null && !rail.has(f)) {
                    val next = f.focusSearch(View.FOCUS_LEFT)
                    if (next == null || rail.has(next)) { rail.focusCurrent(); return true }
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (f != null && rail.has(f)) {
                    panes[current]?.focusFirst()
                    return true
                }
                KeyEvent.KEYCODE_MENU -> if (f != null && rail.has(f)) {
                    val id = (f as? Rail.Row)?.item?.id
                    if (id != null && id.startsWith("share:")) { forget(id.removePrefix("share:")); return true }
                }
            }
        }
        return super.dispatchKeyEvent(e)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (panes[current]?.onBack() == true) return true
            if (!rail.has(currentFocus)) { rail.focusCurrent(); return true }
            if (current != "cont") { open("cont", focus = false); rail.focusCurrent(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ── Network ──
    private fun libVlc(): LibVLC? {
        vlc?.let { return it }
        return runCatching { LibVLC(this, arrayListOf("--no-stats")) }.getOrNull()?.also {
            vlc = it
            Dialog.setCallbacks(it, dialogs)
        }
    }

    private fun startDiscovery() {
        if (discovery != null) return
        val v = libVlc() ?: return
        discovery = MediaBrowser(v, object : MediaBrowser.EventListener {
            override fun onMediaAdded(index: Int, media: IMedia) {
                val uri = media.uri?.toString() ?: return
                if (found.containsKey(uri)) return
                found[uri] = media.getMeta(IMedia.Meta.Title) ?: uri
                refreshRail()
            }
            override fun onMediaRemoved(index: Int, media: IMedia) {
                if (found.remove(media.uri?.toString()) != null) refreshRail()
            }
            override fun onBrowseEnd() {}
        }, handler).also { it.discoverNetworkShares() }
    }

    private fun stopDiscovery() { discovery?.release(); discovery = null }

    private fun field(hint: String, password: Boolean = false) = EditText(this).apply {
        this.hint = hint
        setSingleLine()
        inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
    }

    private fun form(vararg fields: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(Ui.dp(this@MainActivity, 24), Ui.dp(this@MainActivity, 8), Ui.dp(this@MainActivity, 24), 0)
        fields.forEach { addView(it) }
    }

    private fun addDialog() {
        val addr = field(tr("Адрес: 192.168.1.10/films или nfs://nas/video", "Address: 192.168.1.10/films or nfs://nas/video"))
        val name = field(tr("Название, например NAS", "Name, for example NAS")).apply { inputType = InputType.TYPE_CLASS_TEXT }
        val user = field(tr("Имя пользователя (если нужно)", "User name (if needed)"))
        val pass = field(tr("Пароль", "Password"), password = true)
        AlertDialog.Builder(this, Ui.dialogTheme())
            .setTitle(tr("Папка в сети", "Network folder"))
            .setView(form(addr, name, user, pass))
            .setPositiveButton(tr("Сохранить", "Save")) { _, _ ->
                val uri = ShareUri.normalize(addr.text.toString()) ?: return@setPositiveButton
                val title = name.text.toString().trim().ifEmpty { uri.substringAfter("://").trimEnd('/') }
                NetShares.add(this, Share(title, uri, user.text.toString().ifBlank { null }, pass.text.toString().ifBlank { null }))
                panes.remove("share:$uri")
                refreshRail()
                open("share:$uri")
            }
            .setNegativeButton(tr("Отмена", "Cancel"), null)
            .show()
    }

    private fun forget(uri: String) {
        val s = NetShares.saved(this).firstOrNull { it.uri == uri } ?: return
        AlertDialog.Builder(this, Ui.dialogTheme())
            .setTitle(s.name)
            .setMessage(tr("Убрать эту папку из списка?", "Remove this folder from the list?"))
            .setPositiveButton(tr("Убрать", "Remove")) { _, _ ->
                NetShares.remove(this, uri)
                panes.remove("share:$uri")
                if (current == "share:$uri") open("cont", focus = false)
                refreshRail()
                rail.focusCurrent()
            }
            .setNegativeButton(tr("Отмена", "Cancel"), null)
            .show()
    }

    /** libVLC windows: a login for a server (kept for the player), errors in short. */
    private val dialogs = object : Dialog.Callbacks {
        override fun onDisplay(dialog: Dialog.ErrorMessage) {
            Toast.makeText(this@MainActivity, dialog.text ?: dialog.title, Toast.LENGTH_LONG).show()
        }
        override fun onDisplay(dialog: Dialog.LoginDialog) {
            val user = field(tr("Имя пользователя", "User name")).apply { setText(dialog.defaultUsername ?: "") }
            val pass = field(tr("Пароль", "Password"), password = true)
            val target = (panes[current] as? FolderPane)?.source?.root
            AlertDialog.Builder(this@MainActivity, Ui.dialogTheme())
                .setTitle(dialog.title ?: tr("Вход", "Sign in"))
                .setMessage(dialog.text)
                .setView(form(user, pass))
                .setPositiveButton(tr("Войти", "Sign in")) { _, _ ->
                    val u = user.text.toString(); val p = pass.text.toString()
                    if (target != null) NetShares.rememberLogin(this@MainActivity, target, u, p)
                    dialog.postLogin(u, p, false)
                }
                .setNegativeButton(tr("Отмена", "Cancel")) { _, _ -> dialog.dismiss() }
                .setOnCancelListener { dialog.dismiss() }
                .show()
        }
        override fun onDisplay(dialog: Dialog.QuestionDialog) = dialog.dismiss()
        override fun onDisplay(dialog: Dialog.ProgressDialog) {}
        override fun onCanceled(dialog: Dialog?) {}
        override fun onProgressUpdate(dialog: Dialog.ProgressDialog) {}
    }

    // ── Life cycle ──
    override fun onResume() {
        super.onResume()
        refreshRail()
        if (content.childCount > 0) panes[current]?.onShow()
        startDiscovery()
        // for a desk test: am start ... --es update_url http://127.0.0.1:PORT/kino-update.json
        val testUrl = intent.getStringExtra("update_url")
        if (testUrl != null) Updater.setTestUrl(this, testUrl)
        if (!updating) Updater.check(this, testUrl != null) { showUpdate(it) }
        // TorrServer for Lampa (127.0.0.1:8090) runs all the time when that mode is on
        if (TorrSettings.shared(this)) TorrService.hold(this, TorrService.SHARED)
        // DLNA and browser send: Lumen Play is visible to phones as a TV (unless switched off in the settings)
        com.xgimiru.player.dlna.DlnaService.sync(this)
    }

    override fun onRestart() {
        super.onRestart()
        (panes[current] as? SettingsPane)?.onRestart()
    }

    override fun onPause() {
        stopDiscovery()
        panes[current]?.onHide()
        super.onPause()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(mounts) }
        stopDiscovery()
        panes.values.forEach { (it as? FolderPane)?.source?.close() }
        vlc?.let { Dialog.setCallbacks(it, null); it.release() }
        vlc = null
        super.onDestroy()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putString("place", current)
        (panes[current] as? SettingsPane)?.let { if (SettingsPane.pending == null) SettingsPane.pending = it.section to null }
    }

    private fun askStorage() {
        val perms = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
            else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        if (perms.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) requestPermissions(perms, REQ)
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        if (code == REQ) { refreshRail(); panes[current]?.onShow() }
    }

    private fun showUpdate(i: Updater.Info?) {
        updateInfo = i
        if (i == null) { update.visibility = View.GONE; return }
        update.text = tr("Есть версия ${i.versionName}. OK: обновить", "Version ${i.versionName} is available. OK to update")
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
                update.text = tr("Не получилось обновить: $it. OK: ещё раз", "Update failed: $it. OK to retry")
            })
    }

    private companion object { const val REQ = 41 }
}

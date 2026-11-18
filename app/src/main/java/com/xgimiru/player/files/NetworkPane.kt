package com.xgimiru.player.files

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.xgimiru.player.PlayerActivity
import com.xgimiru.player.R
import com.xgimiru.player.net.NetShares
import com.xgimiru.player.net.Share
import com.xgimiru.player.net.ShareUri
import com.xgimiru.player.ui.Host
import com.xgimiru.player.ui.Pane
import com.xgimiru.player.ui.Ui
import com.xgimiru.player.ui.tr
import org.videolan.libvlc.Dialog
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.MediaBrowser
import java.util.Locale

/**
 * Папки в сети: слева свои папки и найденные серверы (SMB, NFS, DLNA, FTP), справа содержимое.
 * Список и вход по паролю делает libVLC (MediaBrowser и его окна входа), играет тоже VLC (SMB, NFS, FTP)
 * или Media3 (DLNA отдаёт обычные http-ссылки). Пароль хранится только здесь, в ссылках его нет.
 */
class NetworkPane(private val act: Activity, private val host: Host) : Pane {
    private class Place(val name: String, val uri: String, val saved: Boolean, val add: Boolean = false)
    private class Entry(val name: String, val uri: String, val dir: Boolean, val subs: List<String>)

    private val root = FrameLayout(act)
    override val view: View = root
    private val d = { v: Number -> Ui.dp(act, v) }
    private val main = Handler(Looper.getMainLooper())
    private val places = ArrayList<Place>()
    private val found = ArrayList<Place>()
    private val entries = ArrayList<Entry>()
    private val placeList = ListView(act)
    private val fileList = ListView(act)
    private val path = Ui.text(act, 15f, Ui.INK3)
    private val empty = Ui.text(act, 16f, Ui.INK2, Ui.sans(), 4)
    private val stack = ArrayList<Pair<String, String>>()   // (имя, ссылка) открытых папок
    private var vlc: LibVLC? = null
    private var discovery: MediaBrowser? = null
    private var browser: MediaBrowser? = null
    private var built = false

    override fun onShow() {
        host.backdrop(null)
        host.info(null, null, null, null)
        if (!built) build()
        start()
        refreshPlaces()
    }

    override fun onHide() = stop()

    private fun start() {
        if (vlc != null) return
        val v = runCatching { LibVLC(act, arrayListOf("--no-stats")) }.getOrNull() ?: return
        vlc = v
        Dialog.setCallbacks(v, dialogs)
        discovery = MediaBrowser(v, object : MediaBrowser.EventListener {
            override fun onMediaAdded(index: Int, media: IMedia) {
                val uri = media.uri?.toString() ?: return
                if (found.none { it.uri == uri }) found += Place(media.getMeta(IMedia.Meta.Title) ?: uri, uri, saved = false)
                refreshPlaces()
            }
            override fun onMediaRemoved(index: Int, media: IMedia) {
                found.removeAll { it.uri == media.uri?.toString() }
                refreshPlaces()
            }
            override fun onBrowseEnd() {}
        }, main).also { it.discoverNetworkShares() }
    }

    private fun stop() {
        browser?.release(); browser = null
        discovery?.release(); discovery = null
        vlc?.let { Dialog.setCallbacks(it, null); it.release() }
        vlc = null
        found.clear()
    }

    private fun build() {
        built = true
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; setPadding(d(40), d(8), d(56), d(24)) }
        placeList.apply {
            divider = null
            selector = Ui.rounded(Ui.withAlpha(Ui.INK, 0x24), d(12).toFloat())
            adapter = PlaceAdapter()
            setOnItemClickListener { _, _, p, _ -> pick(places[p]) }
            setOnItemLongClickListener { _, _, p, _ -> forget(places[p]); true }
            setOnKeyListener { _, code, ev ->
                if (ev.action != KeyEvent.ACTION_DOWN) false
                else if (code == KeyEvent.KEYCODE_DPAD_RIGHT && entries.isNotEmpty()) { fileList.requestFocus(); true }
                else if (code == KeyEvent.KEYCODE_MENU) { places.getOrNull(selectedItemPosition)?.let { forget(it) }; true }
                else false
            }
        }
        row.addView(placeList, Ui.linear(d(280), -1).apply { rightMargin = d(24) })
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        col.addView(path, Ui.linear(-1, -2).apply { leftMargin = d(18); bottomMargin = d(8) })
        val box = FrameLayout(act)
        fileList.apply {
            divider = null
            selector = Ui.rounded(Ui.withAlpha(Ui.INK, 0x24), d(12).toFloat())
            isVerticalScrollBarEnabled = false
            adapter = EntryAdapter()
            setOnItemClickListener { _, _, p, _ -> click(entries[p]) }
            setOnKeyListener { _, code, ev ->
                if (ev.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_LEFT) { placeList.requestFocus(); true } else false
            }
        }
        box.addView(fileList, Ui.frame(-1, -1))
        empty.setPadding(d(18), d(12), d(18), 0)
        empty.setLineSpacing(0f, 1.3f)
        empty.text = tr("Выберите папку слева или добавьте свою: компьютер или NAS в той же сети (SMB, NFS, FTP). " +
            "Серверы DLNA и общие папки Windows появятся в списке сами.",
            "Pick a folder on the left or add your own: a computer or NAS on the same network (SMB, NFS, FTP). " +
            "DLNA servers and Windows shares show up in the list by themselves.")
        box.addView(empty, Ui.frame(-1, -2))
        col.addView(box, Ui.linear(-1, 0, 1f))
        row.addView(col, Ui.linear(0, -1, 1f))
        root.addView(row, Ui.frame(-1, -1))
    }

    private fun refreshPlaces() {
        places.clear()
        places += Place(tr("Добавить папку", "Add a folder"), "", saved = false, add = true)
        for (s in NetShares.saved(act)) places += Place(s.name, s.uri, saved = true)
        places += found.filter { f -> places.none { it.uri.equals(f.uri, true) } }
        (placeList.adapter as BaseAdapter).notifyDataSetChanged()
    }

    private fun pick(p: Place) {
        if (p.add) { addDialog(); return }
        stack.clear()
        open(p.name, p.uri)
    }

    private fun open(name: String, uri: String) {
        val v = vlc ?: return
        stack += name to uri
        entries.clear()
        (fileList.adapter as BaseAdapter).notifyDataSetChanged()
        path.text = stack.joinToString("  /  ") { it.first }
        empty.text = tr("Открываю…", "Opening…")
        empty.visibility = View.VISIBLE
        browser?.release()
        val got = ArrayList<Entry>()
        browser = MediaBrowser(v, object : MediaBrowser.EventListener {
            override fun onMediaAdded(index: Int, media: IMedia) { entry(media)?.let { got += it } }
            override fun onMediaRemoved(index: Int, media: IMedia) {}
            override fun onBrowseEnd() {
                entries.clear()
                entries += got.sortedWith(compareBy<Entry>({ !it.dir }, { it.name.lowercase(Locale.ROOT) }))
                (fileList.adapter as BaseAdapter).notifyDataSetChanged()
                empty.text = tr("Здесь нет видео.", "No videos here.")
                empty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
                if (entries.isNotEmpty() && (placeList.hasFocus() || fileList.hasFocus())) { fileList.requestFocus(); fileList.setSelection(0) }
            }
        }, main).also { it.browse(NetShares.authorize(act, Uri.parse(uri)), MediaBrowser.Flag.Interact) }
    }

    /** Папка или видео; остальное (музыка, картинки, документы) не показываем. */
    private fun entry(m: IMedia): Entry? {
        val uri = m.uri?.toString() ?: return null
        val name = m.getMeta(IMedia.Meta.Title)?.takeIf { it.isNotBlank() } ?: Uri.parse(uri).lastPathSegment ?: uri
        if (name.startsWith(".")) return null
        if (m.type == IMedia.Type.Directory) return Entry(name, ShareUri.strip(uri), true, emptyList())
        val ext = Uri.parse(uri).lastPathSegment?.substringAfterLast('.', "")?.lowercase(Locale.ROOT).orEmpty()
        if (ext !in FilesPane.VIDEO && !(ext.isEmpty() && uri.startsWith("http"))) return null
        val subs = runCatching { m.slaves?.filter { it.type == IMedia.Slave.Type.Subtitle }?.map { ShareUri.strip(it.uri) } }.getOrNull().orEmpty()
        return Entry(name, ShareUri.strip(uri), false, subs)
    }

    private fun click(e: Entry) {
        if (e.dir) { open(e.name, e.uri); return }
        // вся папка — список: следующая серия включится сама
        val videos = entries.filter { !it.dir }
        act.startActivity(Intent(act, PlayerActivity::class.java)
            .setData(Uri.parse(e.uri))
            .putExtra("title", e.name.substringBeforeLast('.'))
            .putExtra("video_list", videos.map { Uri.parse(it.uri) }.toTypedArray<android.os.Parcelable>())
            .putExtra("video_list.name", videos.map { it.name.substringBeforeLast('.') }.toTypedArray())
            .putExtra("video_list.subs", videos.map { it.subs.joinToString("\n") }.toTypedArray()))
    }

    private fun forget(p: Place) {
        if (!p.saved) return
        AlertDialog.Builder(act, R.style.Lumen_Dialog)
            .setTitle(p.name)
            .setMessage(tr("Убрать эту папку из списка?", "Remove this folder from the list?"))
            .setPositiveButton(tr("Убрать", "Remove")) { _, _ -> NetShares.remove(act, p.uri); refreshPlaces() }
            .setNegativeButton(tr("Отмена", "Cancel"), null)
            .show()
    }

    private fun field(hint: String, password: Boolean = false) = EditText(act).apply {
        this.hint = hint
        setSingleLine()
        inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
    }

    private fun form(vararg fields: View) = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(d(24), d(8), d(24), 0)
        fields.forEach { addView(it) }
    }

    private fun addDialog() {
        val addr = field(tr("Адрес: 192.168.1.10/films или nfs://nas/video", "Address: 192.168.1.10/films or nfs://nas/video"))
        val user = field(tr("Имя пользователя (если нужно)", "User name (if needed)"))
        val pass = field(tr("Пароль", "Password"), password = true)
        AlertDialog.Builder(act, R.style.Lumen_Dialog)
            .setTitle(tr("Папка в сети", "Network folder"))
            .setView(form(addr, user, pass))
            .setPositiveButton(tr("Сохранить", "Save")) { _, _ ->
                val uri = ShareUri.normalize(addr.text.toString()) ?: return@setPositiveButton
                val name = uri.substringAfter("://").trimEnd('/')
                NetShares.add(act, Share(name, uri, user.text.toString().ifBlank { null }, pass.text.toString().ifBlank { null }))
                refreshPlaces()
                stack.clear()
                open(name, uri)
            }
            .setNegativeButton(tr("Отмена", "Cancel"), null)
            .show()
    }

    /** Окна libVLC: вход на сервер (логин запоминаем для плеера), ошибки коротко. */
    private val dialogs = object : Dialog.Callbacks {
        override fun onDisplay(dialog: Dialog.ErrorMessage) {
            Toast.makeText(act, dialog.text ?: dialog.title, Toast.LENGTH_LONG).show()
        }
        override fun onDisplay(dialog: Dialog.LoginDialog) {
            val user = field(tr("Имя пользователя", "User name")).apply { setText(dialog.defaultUsername ?: "") }
            val pass = field(tr("Пароль", "Password"), password = true)
            val target = stack.lastOrNull()?.second
            AlertDialog.Builder(act, R.style.Lumen_Dialog)
                .setTitle(dialog.title ?: tr("Вход", "Sign in"))
                .setMessage(dialog.text)
                .setView(form(user, pass))
                .setPositiveButton(tr("Войти", "Sign in")) { _, _ ->
                    val u = user.text.toString(); val p = pass.text.toString()
                    if (target != null) NetShares.rememberLogin(act, target, u, p)
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

    private fun iconRow(): LinearLayout = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(d(16), d(12), d(18), d(12)); layoutParams = AbsListView.LayoutParams(-1, -2)
        addView(ImageView(act), Ui.linear(d(24), d(24)).apply { rightMargin = d(14) })
        addView(Ui.text(act, 17f, Ui.INK, Ui.medium()), Ui.linear(0, -2, 1f))
    }

    private inner class PlaceAdapter : BaseAdapter() {
        override fun getCount() = places.size
        override fun getItem(p: Int) = places[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, c: View?, parent: android.view.ViewGroup?): View {
            val row = (c as? LinearLayout) ?: iconRow()
            val pl = places[p]
            val icon = when {
                pl.add -> R.drawable.ic_add
                pl.uri.startsWith("upnp") -> R.drawable.ic_dns
                pl.saved -> R.drawable.ic_folder
                else -> R.drawable.ic_computer
            }
            (row.getChildAt(0) as ImageView).setImageDrawable(Ui.icon(act, icon, if (pl.add) Ui.ACCENT else Ui.INK2))
            (row.getChildAt(1) as TextView).apply { text = pl.name; setTextColor(if (stack.firstOrNull()?.second == pl.uri) Ui.INK else Ui.INK2) }
            return row
        }
    }

    private inner class EntryAdapter : BaseAdapter() {
        override fun getCount() = entries.size
        override fun getItem(p: Int) = entries[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, c: View?, parent: android.view.ViewGroup?): View {
            val row = (c as? LinearLayout) ?: iconRow()
            val e = entries[p]
            (row.getChildAt(0) as ImageView).setImageDrawable(Ui.icon(act, if (e.dir) R.drawable.ic_folder else R.drawable.ic_movie, Ui.INK3))
            (row.getChildAt(1) as TextView).text = e.name
            return row
        }
    }

    override fun focusFirst(): Boolean {
        if (entries.isNotEmpty()) fileList.requestFocus() else placeList.requestFocus()
        return true
    }

    override fun onBack(): Boolean {
        if (stack.size > 1) {
            stack.removeAt(stack.size - 1)
            val (name, uri) = stack.removeAt(stack.size - 1)
            open(name, uri)
            return true
        }
        if (fileList.hasFocus()) { placeList.requestFocus(); return true }
        return false
    }
}

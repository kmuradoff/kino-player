package com.xgimiru.player.files

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.xgimiru.player.History
import com.xgimiru.player.PlayerActivity
import com.xgimiru.player.Time
import com.xgimiru.player.TorrentActivity
import com.xgimiru.player.ui.Host
import com.xgimiru.player.ui.Pane
import com.xgimiru.player.ui.Ui
import com.xgimiru.player.ui.emptyState
import com.xgimiru.player.ui.tr
import java.io.File
import java.util.Locale

/**
 * Файлы: слева места (флешки, память), справа содержимое папки. Только то, что можно открыть:
 * папки, видео, музыка, .torrent. Видео открывается в плеере с того места, где остановились,
 * а остальные серии папки идут следом.
 */
class FilesPane(private val act: Activity, private val host: Host) : Pane {
    private val root = FrameLayout(act)
    override val view: View = root
    private val d = { v: Number -> Ui.dp(act, v) }
    private val places = ArrayList<Pair<String, File>>()
    private val placeList = ListView(act)
    private val fileList = ListView(act)
    private val path = Ui.text(act, 15f, Ui.INK3)
    private var dir: File? = null
    private var base: File? = null
    private var entries: List<File> = emptyList()

    companion object {
        val VIDEO = setOf("mkv", "mp4", "m4v", "avi", "mov", "ts", "m2ts", "mts", "webm", "wmv", "mpg", "mpeg", "vob", "flv", "3gp", "divx", "iso")
        val AUDIO = setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "ape", "alac", "dsf")
        const val REQ = 41
    }

    override fun onShow() {
        host.backdrop(null)
        host.info(null, null, null, null)
        if (act.checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= 33) {
            act.requestPermissions(arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO), REQ)
        }
        build()
    }

    private fun build() {
        root.removeAllViews()
        places.clear()
        val sm = act.getSystemService(StorageManager::class.java)
        for (v in sm.storageVolumes) {
            val f = v.directory ?: continue
            if (!f.canRead()) continue
            places += (if (v.isPrimary) tr("Память устройства", "Device storage") else v.getDescription(act)) to f
        }
        if (places.isEmpty()) {
            root.addView(emptyState(act, tr("Файлы", "Files"), tr("Вставьте флешку: здесь появятся её видео и музыка.",
                "Insert a USB stick: its videos and music will appear here."), null, null), Ui.frame(-1, -1))
            return
        }
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; setPadding(d(40), d(8), d(56), d(24)) }
        placeList.apply {
            divider = null
            selector = Ui.rounded(0x26F4EFE6, d(12).toFloat())
            adapter = simple({ places.size }, { p -> places[p].first }, { p -> if (places[p].second == base) Ui.INK else Ui.INK3 })
            setOnItemClickListener { _, _, p, _ -> open(places[p].second, isBase = true); fileList.requestFocus() }
            setOnKeyListener { _, code, ev -> if (ev.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_RIGHT) { fileList.requestFocus(); true } else false }
        }
        row.addView(placeList, Ui.linear(d(260), -1).apply { rightMargin = d(24) })
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        col.addView(path, Ui.linear(-1, -2).apply { leftMargin = d(18); bottomMargin = d(8) })
        fileList.apply {
            divider = null
            selector = Ui.rounded(0x2EF4EFE6, d(12).toFloat())
            isVerticalScrollBarEnabled = false
            adapter = FileAdapter()
            setOnItemClickListener { _, _, p, _ -> click(entries[p]) }
            setOnKeyListener { _, code, ev ->
                if (ev.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_LEFT) { placeList.requestFocus(); true } else false
            }
        }
        col.addView(fileList, Ui.linear(-1, 0, 1f))
        row.addView(col, Ui.linear(0, -1, 1f))
        root.addView(row, Ui.frame(-1, -1))
        open(base?.takeIf { b -> places.any { it.second == b } } ?: places.first().second, isBase = true, keep = true)
    }

    private fun open(f: File, isBase: Boolean = false, keep: Boolean = false) {
        if (isBase) base = f
        dir = if (keep && dir != null && dir!!.path.startsWith(f.path)) dir else f
        val list = dir!!.listFiles()?.filter { !it.name.startsWith(".") && (it.isDirectory || kind(it) != null) } ?: emptyList()
        entries = list.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) }))
        val rel = dir!!.path.removePrefix(base!!.path).trim('/')
        path.text = (places.firstOrNull { it.second == base }?.first ?: "") + (if (rel.isNotEmpty()) "  /  " + rel.replace("/", "  /  ") else "")
        (fileList.adapter as BaseAdapter).notifyDataSetChanged()
        (placeList.adapter as BaseAdapter).notifyDataSetChanged()
        fileList.setSelection(0)
    }

    private fun kind(f: File): String? {
        val ext = f.extension.lowercase(Locale.ROOT)
        return when {
            ext in VIDEO -> "video"
            ext in AUDIO -> "audio"
            ext == "torrent" -> "torrent"
            else -> null
        }
    }

    private fun click(f: File) {
        if (f.isDirectory) { open(f); return }
        when (kind(f)) {
            "torrent" -> act.startActivity(Intent(act, TorrentActivity::class.java).setData(Uri.fromFile(f)))
            else -> {
                // вся папка — плейлист: следующая серия включится сама
                val same = entries.filter { !it.isDirectory && kind(it) == kind(f) }
                act.startActivity(Intent(act, PlayerActivity::class.java)
                    .setData(Uri.fromFile(f))
                    .putExtra("title", f.nameWithoutExtension)
                    .putExtra("video_list", same.map { Uri.fromFile(it) }.toTypedArray<android.os.Parcelable>())
                    .putExtra("video_list.name", same.map { it.nameWithoutExtension }.toTypedArray()))
            }
        }
    }

    private inner class FileAdapter : BaseAdapter() {
        override fun getCount() = entries.size
        override fun getItem(p: Int) = entries[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, c: View?, parent: android.view.ViewGroup?): View {
            val row = (c as? LinearLayout) ?: LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(d(18), d(12), d(20), d(12)); layoutParams = AbsListView.LayoutParams(-1, -2)
                addView(Ui.text(act, 18f, Ui.INK3, Ui.medium()).apply { gravity = Gravity.CENTER }, Ui.linear(d(32), -2).apply { rightMargin = d(14) })
                addView(Ui.text(act, 18f, Ui.INK, Ui.medium()), Ui.linear(0, -2, 1f))
                addView(Ui.text(act, 14f, Ui.INK3))
            }
            val f = entries[p]
            (row.getChildAt(0) as TextView).text = when { f.isDirectory -> "▸"; kind(f) == "audio" -> "♪"; kind(f) == "torrent" -> "⇣"; else -> "▶" }
            (row.getChildAt(1) as TextView).text = f.name
            (row.getChildAt(2) as TextView).text = when {
                f.isDirectory -> ""
                else -> History.get(act, History.key(Uri.fromFile(f)))?.takeIf { !it.finished }?.let {
                    tr("осталось ", "") + Time.human(it.duration - it.position) + tr("", " left")
                } ?: com.xgimiru.player.torrents.Torznab.human(f.length())
            }
            return row
        }
    }

    private fun simple(count: () -> Int, label: (Int) -> String, color: (Int) -> Int) = object : BaseAdapter() {
        override fun getCount() = count()
        override fun getItem(p: Int): Any = label(p)
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, c: View?, parent: android.view.ViewGroup?): View =
            ((c as? TextView) ?: Ui.text(act, 17f, Ui.INK2, Ui.medium()).apply {
                setPadding(d(18), d(13), d(18), d(13)); layoutParams = AbsListView.LayoutParams(-1, -2)
            }).apply { text = label(p); setTextColor(color(p)) }
    }

    override fun focusFirst(): Boolean {
        if (places.isEmpty()) return false
        fileList.requestFocus(); return true
    }

    override fun onBack(): Boolean {
        val cur = dir ?: return false
        if (base != null && cur.path != base!!.path) { val back = cur; open(cur.parentFile ?: base!!); entries.indexOf(back).takeIf { it >= 0 }?.let { fileList.setSelection(it) }; return true }
        return false
    }

    fun onPermission() = build()
}

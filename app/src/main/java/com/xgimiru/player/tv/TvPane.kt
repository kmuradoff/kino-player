package com.xgimiru.player.tv

import android.app.Activity
import com.xgimiru.player.R
import android.app.AlertDialog
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.AbsListView
import android.widget.AdapterView
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
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * ТВ: слева группы (Избранное, Все каналы, группы плейлиста), посередине каналы с тем, что идёт,
 * справа предпросмотр выбранного канала без звука (через 1,2 с после остановки фокуса) и программа.
 * OK — эфир на весь экран, меню (или долгий OK) — в избранное.
 */
class TvPane(private val act: Activity, private val host: Host) : Pane {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val root = FrameLayout(act)
    override val view: View = root
    private val d = { v: Number -> Ui.dp(act, v) }

    private val groups = ArrayList<Pair<String, String>>()   // ключ списка -> подпись
    private val groupList = ListView(act)
    private val channelAdapter = ChannelAdapter(act)
    private val channelList = ListView(act)
    private var currentList = "all"

    // правая колонка
    private val preview = FrameLayout(act)
    private var vlc: LibVLC? = null
    private var player: MediaPlayer? = null
    private var videoLayout: VLCVideoLayout? = null
    private val pName = Ui.text(act, 24f, Ui.INK, Ui.serif(), 2)
    private val pNow = Ui.text(act, 17f, Ui.INK, Ui.medium(), 2)
    private val pTime = Ui.text(act, 14f, Ui.ACCENT, Ui.medium())
    private val pDesc = Ui.text(act, 14f, Ui.INK2, Ui.sans(), 4).apply { setLineSpacing(0f, 1.3f) }
    private val pNext = Ui.text(act, 14f, Ui.INK3, Ui.sans(), 2)
    private var focused: Channel? = null
    private val startPreview = Runnable { focused?.let { playPreview(it) } }

    override fun onShow() {
        host.backdrop(null)
        host.info(null, null, null, null)
        build()
        io.execute {
            Iptv.refresh(act)
            val wasEmpty = Epg.stale(act)
            Epg.refresh(act)
            main.post { if (wasEmpty && root.isAttachedToWindow) channelAdapter.notifyDataSetChanged() }
        }
    }

    override fun onHide() = stopPreview()

    private fun build() {
        root.removeAllViews()
        if (Iptv.playlists(act).isEmpty()) {
            root.addView(emptyState(act, tr("ТВ", "TV"),
                tr("Добавьте ссылку на плейлист IPTV (M3U), который вам дал провайдер или сервис. Программа передач подтянется сама, если она указана в плейлисте.",
                    "Add a link to an IPTV playlist (M3U) from your provider or service. The programme guide loads by itself if the playlist names one."),
                tr("Добавить плейлист", "Add a playlist")) { addPlaylist(act) { build() } }, Ui.frame(-1, -1))
            return
        }
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; setPadding(d(32), d(8), d(32), d(24)) }

        groups.clear()
        if (Iptv.favorites(act).isNotEmpty()) groups += "fav" to tr("Избранное", "Favorites")
        groups += "all" to tr("Все каналы", "All channels")
        Iptv.groups(act).forEach { groups += it to it }
        if (groups.none { it.first == currentList }) currentList = groups.first().first
        groupList.apply {
            divider = null
            selector = Ui.rounded(0x26F4EFE6, d(12).toFloat())
            isVerticalScrollBarEnabled = false
            adapter = object : BaseAdapter() {
                override fun getCount() = groups.size
                override fun getItem(p: Int) = groups[p]
                override fun getItemId(p: Int) = p.toLong()
                override fun getView(p: Int, c: View?, parent: android.view.ViewGroup?): View =
                    ((c as? TextView) ?: Ui.text(act, 17f, Ui.INK2, Ui.medium()).apply {
                        setPadding(d(18), d(13), d(18), d(13)); layoutParams = AbsListView.LayoutParams(-1, -2)
                    }).apply {
                        text = groups[p].second
                        setTextColor(if (groups[p].first == currentList) Ui.INK else Ui.INK3)
                    }
            }
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(a: AdapterView<*>?, v: View?, p: Int, id: Long) = selectGroup(groups[p].first)
                override fun onNothingSelected(a: AdapterView<*>?) {}
            }
            setOnItemClickListener { _, _, p, _ -> selectGroup(groups[p].first); channelList.requestFocus() }
            setOnKeyListener { _, code, ev ->
                if (ev.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_RIGHT) { channelList.requestFocus(); true } else false
            }
        }
        row.addView(groupList, Ui.linear(d(190), -1).apply { rightMargin = d(14) })

        channelList.apply {
            divider = null
            selector = Ui.rounded(0x2EF4EFE6, d(12).toFloat())
            isVerticalScrollBarEnabled = false
            adapter = channelAdapter
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(a: AdapterView<*>?, v: View?, p: Int, id: Long) = focusChannel(channelAdapter.getItem(p))
                override fun onNothingSelected(a: AdapterView<*>?) {}
            }
            setOnItemClickListener { _, _, p, _ -> stopPreview(); LiveActivity.open(act, channelAdapter.getItem(p), currentList) }
            setOnItemLongClickListener { _, _, p, _ -> toggleFav(p); true }
            setOnKeyListener { _, code, ev ->
                if (ev.action != KeyEvent.ACTION_DOWN) false
                else when (code) {
                    KeyEvent.KEYCODE_MENU -> { if (selectedItemPosition >= 0) toggleFav(selectedItemPosition); true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> { groupList.requestFocus(); groupList.setSelection(groups.indexOfFirst { it.first == currentList }); true }
                    else -> false
                }
            }
        }
        row.addView(channelList, Ui.linear(d(410), -1).apply { rightMargin = d(24) })

        val right = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        preview.background = Ui.rounded(0xFF000000.toInt(), d(14).toFloat())
        preview.clipToOutline = true
        right.addView(preview, Ui.linear(-1, d(150)))
        right.addView(pName, Ui.linear(-1, -2).apply { topMargin = d(18) })
        right.addView(pTime, Ui.linear(-1, -2).apply { topMargin = d(12) })
        right.addView(pNow, Ui.linear(-1, -2).apply { topMargin = d(4) })
        right.addView(pDesc, Ui.linear(-1, -2).apply { topMargin = d(8) })
        right.addView(pNext, Ui.linear(-1, -2).apply { topMargin = d(12) })
        right.addView(Ui.text(act, 12f, Ui.INK3, Ui.sans(), 2).apply {
            text = tr("OK — смотреть · Меню — в избранное", "OK to watch · Menu to add to favorites")
        }, Ui.linear(-1, -2).apply { topMargin = d(16) })
        row.addView(right, Ui.linear(0, -1, 1f))
        root.addView(row, Ui.frame(-1, -1))
        selectGroup(currentList, force = true)
    }

    private fun selectGroup(key: String, force: Boolean = false) {
        if (key == currentList && !force) return
        currentList = key
        channelAdapter.items = LiveActivity.listOf(act, key)
        (groupList.adapter as BaseAdapter).notifyDataSetChanged()
        channelList.setSelection(0)
    }

    private fun toggleFav(p: Int) {
        val c = channelAdapter.getItem(p)
        val on = Iptv.toggleFavorite(act, c.key)
        channelAdapter.refreshFavs()
        android.widget.Toast.makeText(act, if (on) tr("Добавлено в избранное", "Added to favorites") else tr("Убрано из избранного", "Removed from favorites"), android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun focusChannel(c: Channel) {
        focused = c
        val now = System.currentTimeMillis()
        val hm = SimpleDateFormat("HH:mm", Locale.getDefault())
        val p = Epg.now(act, c, now)
        val n = Epg.next(act, c, now)
        pName.text = c.name
        pTime.text = p?.let { "${hm.format(Date(it.start))} – ${hm.format(Date(it.end))}" } ?: ""
        pTime.visibility = if (p != null) View.VISIBLE else View.GONE
        pNow.text = p?.title ?: tr("Нет программы передач", "No programme guide")
        pDesc.text = p?.desc ?: ""
        pDesc.visibility = if (p?.desc.isNullOrEmpty()) View.GONE else View.VISIBLE
        pNext.text = n?.let { tr("Дальше", "Next") + "  ${hm.format(Date(it.start))}  ${it.title}" } ?: ""
        main.removeCallbacks(startPreview)
        player?.stop()
        main.postDelayed(startPreview, 1200)
    }

    private fun playPreview(c: Channel) {
        if (!root.isAttachedToWindow) return
        if (vlc == null) {
            vlc = LibVLC(act, arrayListOf("--no-stats", "--no-audio"))
            player = MediaPlayer(vlc)
            videoLayout = VLCVideoLayout(act)
            preview.addView(videoLayout, Ui.frame(-1, -1))
            player!!.attachViews(videoLayout!!, null, false, false)
        }
        val m = Media(vlc, Uri.parse(c.url))
        m.setHWDecoderEnabled(true, false)
        m.addOption(":network-caching=1000")
        m.addOption(":no-audio")
        m.addOption(":http-user-agent=" + (c.userAgent ?: Net.UA))
        c.referrer?.let { m.addOption(":http-referrer=$it") }
        player!!.media = m
        m.release()
        player!!.play()
    }

    private fun stopPreview() {
        main.removeCallbacks(startPreview)
        player?.let { it.stop(); it.detachViews(); it.release() }
        vlc?.release()
        videoLayout?.let { preview.removeView(it) }
        player = null; vlc = null; videoLayout = null
    }

    override fun focusFirst(): Boolean {
        if (Iptv.playlists(act).isEmpty()) {
            return root.findFocus() != null || root.getChildAt(0)?.let { firstFocusable(it)?.requestFocus() } == true
        }
        channelList.requestFocus()
        return true
    }

    private fun firstFocusable(v: View): View? {
        if (v.isFocusable) return v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) firstFocusable(v.getChildAt(i))?.let { return it }
        return null
    }

    override fun onBack(): Boolean = false

    companion object {
        /** Диалог «Добавить плейлист»: ссылка (и необязательная ссылка на программу передач). */
        fun addPlaylist(act: Activity, done: () -> Unit) {
            val d = { v: Number -> Ui.dp(act, v) }
            val url = EditText(act).apply { hint = "http://…/playlist.m3u"; setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }
            val name = EditText(act).apply { hint = tr("Название (необязательно)", "Name (optional)"); setSingleLine() }
            val epg = EditText(act).apply { hint = tr("Программа передач XMLTV (необязательно)", "XMLTV programme guide (optional)"); setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }
            val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(d(24), d(8), d(24), 0); addView(url); addView(name); addView(epg) }
            AlertDialog.Builder(act, R.style.Lumen_Dialog)
                .setTitle(tr("Добавить плейлист", "Add a playlist"))
                .setView(box)
                .setPositiveButton(tr("Добавить", "Add")) { _, _ ->
                    val u = url.text.toString().trim()
                    if (u.isEmpty()) return@setPositiveButton
                    val toast = android.widget.Toast.makeText(act, tr("Загружаю плейлист…", "Loading the playlist…"), android.widget.Toast.LENGTH_LONG)
                    toast.show()
                    Thread {
                        val r = runCatching { Iptv.add(act, name.text.toString(), u, epg.text.toString()) }
                        act.runOnUiThread {
                            toast.cancel()
                            r.onSuccess {
                                android.widget.Toast.makeText(act, tr("Каналов: ${Iptv.channels(act).size}", "Channels: ${Iptv.channels(act).size}"), android.widget.Toast.LENGTH_SHORT).show()
                                Thread { runCatching { Epg.refresh(act, force = true) } }.start()
                                done()
                            }.onFailure {
                                AlertDialog.Builder(act, R.style.Lumen_Dialog).setTitle(tr("Не получилось", "Could not add it")).setMessage(it.message).setPositiveButton("OK", null).show()
                            }
                        }
                    }.start()
                }
                .setNegativeButton(tr("Отмена", "Cancel"), null)
                .show()
            url.requestFocus()
        }
    }
}

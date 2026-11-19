package com.xgimiru.player.files

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.media3.common.util.UnstableApi
import com.xgimiru.player.History
import com.xgimiru.player.Name
import com.xgimiru.player.PlayerActivity
import com.xgimiru.player.R
import com.xgimiru.player.media.Arts
import com.xgimiru.player.media.Badges
import com.xgimiru.player.media.Fmt
import com.xgimiru.player.media.MediaFacts
import com.xgimiru.player.media.Probe
import com.xgimiru.player.settings.SubtitleText
import com.xgimiru.player.ui.Img
import com.xgimiru.player.ui.Nav
import com.xgimiru.player.ui.Pane
import com.xgimiru.player.ui.Ui
import com.xgimiru.player.ui.chip
import com.xgimiru.player.ui.round
import com.xgimiru.player.ui.tr

/**
 * A folder as a table: name, length, quality, size, with the focused file's card on the right (picture,
 * badges, sound and subtitle languages, size, time left). OK plays the file and the rest of the folder after
 * it, long OK plays it from the start and drops it from the history.
 */
@UnstableApi
class FolderPane(private val act: Activity, private val nav: Nav, val source: Source) : Pane {
    private val root = FrameLayout(act)
    override val view: View = root
    private val p = { v: Number -> Ui.px(act, v) }
    private val ru get() = tr("ru", "en") == "ru"
    private val stack = ArrayList<Pair<String, String>>()   // (name, uri) of the open folders
    private var listing: Listing? = null
    private var items: List<FileItem> = emptyList()
    private val restore = HashMap<String, Int>()            // selected row per folder, for Back
    private val crumb = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val meta = Ui.t(act, 21, 450, Ui.T3)
    private val list = ListView(act)
    private val message = Ui.t(act, 25, 450, Ui.T2, 3)
    private val insp = FrameLayout(act)
    private val statusLeft = Ui.t(act, 20, 450, Ui.T3)
    private val okLabel = Ui.t(act, 20, 450, Ui.T3)
    private val longOk = LinearLayout(act)
    private var built = false
    private var shownAt = -1
    /** Focus was asked for before the list arrived: take it when it does. */
    private var wantFocus = false

    override fun onShow() {
        if (!built) build()
        if (stack.isEmpty()) open(source.title, source.root) else reload()
        statusLeft.text = source.status() ?: ""
    }

    override fun onHide() { wantFocus = false; source.close() }

    private fun build() {
        built = true
        root.addView(crumb, Ui.at(act, 56, 52, -2, p(40)))
        root.addView(LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(act).apply { setImageDrawable(Ui.icon(act, R.drawable.ic_sort, Ui.T3)) }, Ui.linear(p(24), p(24)).apply { rightMargin = p(10) })
            addView(meta)
        }, Ui.frame(-2, -2, Gravity.END).apply { topMargin = p(60); rightMargin = p(56) })
        root.addView(header(), Ui.at(act, 56, 128, p(940), p(44)))
        list.apply {
            divider = ColorDrawable(0); dividerHeight = p(4)
            selector = ColorDrawable(0)
            isVerticalScrollBarEnabled = false
            isLongClickable = true
            setDefaultFocusHighlightEnabled(false)
            adapter = Adapter()
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(a: AdapterView<*>?, v: View?, pos: Int, id: Long) { paintRows(); inspect(pos) }
                override fun onNothingSelected(a: AdapterView<*>?) { paintRows() }
            }
            setOnFocusChangeListener { _, f -> paintRows(); if (f) inspect(selectedItemPosition) }
            setOnItemClickListener { _, _, pos, _ -> click(pos, fromStart = false) }
            setOnItemLongClickListener { _, _, pos, _ -> click(pos, fromStart = true); true }
            // the table keeps the focus at its ends (down from the last row never jumps into the left list)
            setOnKeyListener { _, code, ev ->
                ev.action == android.view.KeyEvent.ACTION_DOWN && (code == android.view.KeyEvent.KEYCODE_DPAD_DOWN && selectedItemPosition >= count - 1 ||
                    code == android.view.KeyEvent.KEYCODE_DPAD_UP && selectedItemPosition <= 0)
            }
        }
        root.addView(list, Ui.at(act, 56, 176, p(940), p(812)))
        root.addView(message, Ui.at(act, 74, 196, p(900)))
        root.addView(insp, Ui.at(act, 1028, 132, p(404), -2))
        val status = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(statusLeft, Ui.linear(0, -2, 1f))
            addView(key("OK"), Ui.linear(-2, p(30)).apply { rightMargin = p(10) })
            addView(okLabel)
            longOk.apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(key(tr("Долго OK", "Hold OK")), Ui.linear(-2, p(30)).apply { rightMargin = p(10) })
                addView(Ui.t(act, 20, 450, Ui.T3).apply { text = tr("С начала, убрать из истории", "From the start, off the history") })
            }
            addView(longOk, Ui.linear(-2, -2).apply { leftMargin = p(28) })
        }
        root.addView(status, Ui.frame(-1, p(32)).apply { leftMargin = p(56); rightMargin = p(56); topMargin = p(1000) })
    }

    private fun key(t: String) = chip(act, t, size = 15, weight = 650, height = 30, pad = 9, radius = 7).apply { minWidth = p(40) }

    private fun header(): View = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(p(18), 0, p(18), 0)
        fun h(t: String, w: Int, right: Boolean = false, weight: Float = 0f) = addView(Ui.t(act, 19, 560, Ui.T3).apply {
            text = t; gravity = if (right) Gravity.END else Gravity.START
        }, Ui.linear(w, -2, weight).apply { if (childCount > 0) leftMargin = p(18) })
        h("", p(52)); h(tr("Имя", "Name"), 0, weight = 1f); h(tr("Время", "Time"), p(128), true)
        h(tr("Качество", "Quality"), p(112)); h(tr("Размер", "Size"), p(92), true)
        background = android.graphics.drawable.LayerDrawable(arrayOf(ColorDrawable(Ui.LINE))).apply { setLayerInset(0, 0, p(43), 0, 0) }
    }

    // ── Folders ──
    private fun open(name: String, uri: String) {
        stack += name to uri
        load()
    }

    private fun reload() = load(keep = true)

    private fun load(keep: Boolean = false) {
        val (_, uri) = stack.last()
        paintCrumb()
        // the table empties while the next folder is read: its focus comes back to it, not to the left list
        if (list.hasFocus()) wantFocus = true
        if (!keep) { items = emptyList(); (list.adapter as BaseAdapter).notifyDataSetChanged(); insp.removeAllViews() }
        message.text = tr("Открываю…", "Opening…")
        message.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        source.list(uri) { l, err ->
            if (stack.lastOrNull()?.second != uri) return@list
            listing = l
            items = l?.items.orEmpty()
            val at = restore[uri]?.coerceAtMost(items.size - 1) ?: 0
            (list.adapter as BaseAdapter).notifyDataSetChanged()
            meta.text = Fmt.objects(items.size, ru) + tr(", по имени", ", by name")
            message.text = when {
                err != null -> tr("Папка не открылась. Проверьте, что компьютер или NAS включён и в той же сети.",
                    "The folder did not open. Check that the computer or NAS is on and in the same network.")
                items.isEmpty() -> tr("Здесь нет видео.", "No videos here.")
                else -> ""
            }
            message.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            if (items.isNotEmpty()) list.setSelection(at.coerceAtLeast(0))
            if (wantFocus && items.isNotEmpty()) { wantFocus = false; list.requestFocus() }
            shownAt = -1
            inspect(list.selectedItemPosition.coerceAtLeast(0))
            probeAll()
        }
    }

    private fun paintCrumb() {
        crumb.removeAllViews()
        val names = stack.map { it.first }
        val shown = if (names.size > 3) listOf(names.first(), "…") + names.takeLast(2) else names
        shown.forEachIndexed { i, n ->
            if (i > 0) crumb.addView(ImageView(act).apply { setImageDrawable(Ui.icon(act, R.drawable.ic_chevron_right, Ui.T2)) },
                Ui.linear(p(30), p(30)).apply { leftMargin = p(10); rightMargin = p(10) })
            val last = i == shown.size - 1
            crumb.addView(Ui.t(act, 30, if (last) 680 else 500, if (last) Ui.T1 else Ui.T2, spacing = if (last) -.4f else 0f).apply { text = n })
        }
    }

    private fun click(pos: Int, fromStart: Boolean) {
        val f = items.getOrNull(pos) ?: return
        if (f.dir) {
            restore[stack.last().second] = pos
            open(f.name, f.uri); return
        }
        if (fromStart) History.forget(act, History.key(Uri.parse(f.uri)))
        // the whole folder is the list: the next episode starts by itself
        val videos = items.filter { !it.dir }
        act.startActivity(Intent(act, PlayerActivity::class.java)
            .setData(Uri.parse(f.uri))
            .putExtra("title", f.title)
            .putExtra("from_start", fromStart)
            .putExtra("video_list", videos.map { Uri.parse(it.uri) }.toTypedArray<android.os.Parcelable>())
            .putExtra("video_list.name", videos.map { it.title }.toTypedArray())
            .putExtra("video_list.subs", videos.map { it.subs.joinToString("\n") }.toTypedArray()))
    }

    override fun focusFirst(): Boolean {
        if (items.isEmpty()) { wantFocus = true; return false }
        return list.requestFocus()
    }

    override fun onBack(): Boolean {
        if (stack.size <= 1) return false
        val (_, gone) = stack.removeAt(stack.size - 1)
        restore.remove(gone)
        load()
        return true
    }

    // ── Rows ──
    private fun probeAll() {
        for (f in items) if (!f.dir) Probe.get(act, f.uri, deep = false) { if (it.durationMs > 0 || it.width > 0) refreshRow(f) }
    }

    private fun refreshRow(f: FileItem) {
        val i = items.indexOf(f)
        val v = list.getChildAt(i - list.firstVisiblePosition) as? FileRow ?: return
        v.bind(f)
        v.paint(i == list.selectedItemPosition && list.hasFocus())
    }

    private fun paintRows() {
        for (i in 0 until list.childCount) (list.getChildAt(i) as? FileRow)?.paint(list.firstVisiblePosition + i == list.selectedItemPosition && list.hasFocus())
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(pos: Int) = items[pos]
        override fun getItemId(pos: Int) = pos.toLong()
        override fun getView(pos: Int, c: View?, parent: ViewGroup?): View {
            val row = (c as? FileRow) ?: FileRow()
            row.bind(items[pos])
            row.paint(pos == list.selectedItemPosition && list.hasFocus())
            return row
        }
    }

    /** One table row: poster, name and file name, length, quality chips, size. */
    private inner class FileRow : LinearLayout(act) {
        private val po = FrameLayout(act).apply { background = Ui.rounded(Ui.S2, p(7).toFloat()); round(p(7).toFloat()) }
        private val poImg = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        private val poIcon = ImageView(act)
        private val pb = FrameLayout(act).apply { setBackgroundColor(0x4DFFFFFF) }
        private val pbFill = View(act)
        private val name = Ui.t(act, 27, 540, Ui.T1)
        private val done = ImageView(act)
        private val file = Ui.t(act, 19, 450, Ui.T3)
        private val du = Ui.t(act, 23, 450, Ui.T2).apply { gravity = Gravity.END; fontFeatureSettings = "tnum" }
        private val qc = LinearLayout(act).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        private val sz = Ui.t(act, 22, 450, Ui.T3).apply { gravity = Gravity.END; fontFeatureSettings = "tnum" }
        private var item: FileItem? = null
        private var progress = 0f
        private var watched = false

        init {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(p(18), 0, p(18), 0)
            layoutParams = AbsListView.LayoutParams(-1, p(92))
            po.addView(poImg, Ui.frame(-1, -1))
            po.addView(poIcon, Ui.frame(p(34), p(34), Gravity.CENTER))
            pb.addView(pbFill, Ui.frame(0, -1))
            po.addView(pb, Ui.frame(-1, p(5), Gravity.BOTTOM))
            addView(po, Ui.linear(p(52), p(78)))
            val col = LinearLayout(act).apply { orientation = VERTICAL }
            col.addView(LinearLayout(act).apply {
                orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(name, Ui.linear(-2, -2, 0f))
                addView(done, Ui.linear(p(24), p(24)).apply { leftMargin = p(10) })
            })
            col.addView(file, Ui.linear(-1, -2).apply { topMargin = p(6) })
            addView(col, Ui.linear(0, -2, 1f).apply { leftMargin = p(18) })
            addView(du, Ui.linear(p(128), -2).apply { leftMargin = p(18) })
            addView(qc, Ui.linear(p(112), -2).apply { leftMargin = p(18) })
            addView(sz, Ui.linear(p(92), -2).apply { leftMargin = p(18) })
        }

        fun bind(f: FileItem) {
            val same = item === f
            item = f
            name.text = if (f.dir) f.name else Name.parse(f.title).let { n -> if (n.episode != null) f.title else n.title }
            file.text = if (f.dir) tr("Папка", "Folder") else f.name
            val facts = Probe.cached(f.uri)
            du.text = when {
                f.dir && f.count >= 0 -> Fmt.files(f.count, ru)
                facts != null && facts.durationMs > 0 -> Fmt.duration(facts.durationMs, ru)
                else -> ""
            }
            sz.text = if (f.size > 0) Fmt.size(f.size, ru) else ""
            qc.removeAllViews()
            if (!f.dir) Badges.chips(facts, f.name).forEach { qc.addView(chip(act, it), Ui.linear(-2, p(28)).apply { rightMargin = p(6) }) }
            val h = if (f.dir) null else History.get(act, History.key(Uri.parse(f.uri)))
            watched = !f.dir && History.watched(act).any { it.key == History.key(Uri.parse(f.uri)) }
            progress = h?.takeIf { !it.finished }?.progress ?: 0f
            pb.visibility = if (progress > 0f) VISIBLE else GONE
            pb.post { pbFill.layoutParams = pbFill.layoutParams.apply { width = (pb.width * progress).toInt() }; pbFill.requestLayout() }
            done.visibility = if (watched) VISIBLE else GONE
            poIcon.setImageDrawable(Ui.icon(act, if (f.dir) R.drawable.ic_folder else R.drawable.ic_movie, Ui.T3))
            if (!same) poImg.setImageDrawable(null)
            poIcon.visibility = VISIBLE
            if (!f.dir) Arts.find(act, f.uri, f.title, listing?.names) { a ->
                // an episode without a poster shows its own still ("S02E22-thumb.jpg"), never the folder's fanart on every row
                val base = f.name.substringBeforeLast('.')
                val own = a.wide?.takeIf { Uri.decode(it.substringAfterLast('/')).startsWith(base) }
                val pic = a.poster ?: own
                if (item === f && pic != null) { Img.load(poImg, pic, 160); poIcon.visibility = GONE }
            }
        }

        fun paint(sel: Boolean) {
            background = if (sel) Ui.rounded(Ui.ACC, p(14).toFloat()) else null
            name.setTextColor(if (sel) Ui.WHITE else Ui.T1)
            du.setTextColor(if (sel) Ui.WHITE else Ui.T2)
            val soft = if (sel) 0xCCFFFFFF.toInt() else Ui.T3
            file.setTextColor(soft); sz.setTextColor(soft)
            done.setImageDrawable(Ui.icon(act, R.drawable.ic_check_circle, soft))
            pbFill.setBackgroundColor(if (sel) Ui.WHITE else Ui.ACC)
            for (i in 0 until qc.childCount) (qc.getChildAt(i) as? TextView)?.apply {
                setTextColor(if (sel) Ui.WHITE else Ui.T2)
                background = Ui.rounded(0, p(6).toFloat(), p(1.5f), if (sel) 0x99FFFFFF.toInt() else Ui.LINE2)
            }
        }
    }

    // ── The file card on the right ──
    private fun inspect(pos: Int) {
        val f = items.getOrNull(pos) ?: run { insp.removeAllViews(); return }
        if (pos == shownAt && insp.childCount > 0) return
        shownAt = pos
        okLabel.text = if (f.dir) tr("Открыть", "Open") else tr("Смотреть", "Watch")
        longOk.visibility = if (f.dir) View.GONE else View.VISIBLE
        if (f.dir) folderCard(f) else fileCard(f, Probe.cached(f.uri))
        if (!f.dir) Probe.get(act, f.uri, deep = true) { facts -> if (shownAt == pos && items.getOrNull(pos) === f) fileCard(f, facts) }
    }

    private fun stillBox(): FrameLayout = FrameLayout(act).apply { background = Ui.rounded(Ui.S1, p(16).toFloat()); round(p(16).toFloat()) }

    private fun folderCard(f: FileItem) {
        insp.removeAllViews()
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        col.addView(stillBox().apply {
            addView(ImageView(act).apply { setImageDrawable(Ui.icon(act, R.drawable.ic_folder_open, Ui.T3)) }, Ui.frame(p(110), p(110), Gravity.CENTER))
        }, Ui.linear(p(404), p(227)))
        col.addView(Ui.t(act, 36, 680, Ui.T1, 2, spacing = -.5f).apply { text = f.name }, Ui.linear(-1, -2).apply { topMargin = p(26) })
        col.addView(Ui.t(act, 23, 450, Ui.T2).apply { text = tr("Папка", "Folder") + if (f.count >= 0) " · " + Fmt.files(f.count, ru) else "" },
            Ui.linear(-1, -2).apply { topMargin = p(10) })
        if (f.inside.isNotEmpty()) col.addView(kv(listOf(tr("Внутри", "Inside") to f.inside.joinToString("\n"))), Ui.linear(-1, -2).apply { topMargin = p(26) })
        insp.addView(col, Ui.frame(-1, -2))
    }

    private fun fileCard(f: FileItem, facts: MediaFacts?) {
        insp.removeAllViews()
        val n = Name.parse(f.title)
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val still = stillBox()
        val img = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        val none = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            addView(ImageView(act).apply { setImageDrawable(Ui.icon(act, R.drawable.ic_movie, Ui.T3)) }, Ui.linear(p(64), p(64)))
            addView(Ui.t(act, 20, 450, Ui.T3).apply { text = tr("Обложки нет", "No picture"); gravity = Gravity.CENTER }, Ui.linear(-2, -2).apply { topMargin = p(8) })
        }
        still.addView(none, Ui.frame(-1, -1))
        still.addView(img, Ui.frame(-1, -1))
        val h = History.get(act, History.key(Uri.parse(f.uri)))?.takeIf { !it.finished }
        if (h != null) still.addView(FrameLayout(act).apply {
            setBackgroundColor(0x47FFFFFF)
            val fill = View(act).apply { setBackgroundColor(Ui.ACC) }
            addView(fill, Ui.frame((p(404) * h.progress).toInt(), -1))
        }, Ui.frame(-1, p(7), Gravity.BOTTOM))
        Arts.find(act, f.uri, f.title, listing?.names) { a -> (a.wide ?: a.poster)?.let { Img.load(img, it, 820); none.visibility = View.GONE } }
        col.addView(still, Ui.linear(p(404), p(227)))
        col.addView(Ui.t(act, 36, 680, Ui.T1, 2, spacing = -.5f).apply { text = n.title }, Ui.linear(-1, -2).apply { topMargin = p(26) })
        val dur = facts?.durationMs?.takeIf { it > 0 }?.let { Fmt.duration(it, ru) }
        val ep = if (n.episode != null) tr("Сезон ${n.season ?: 1} · Серия ${n.episode}", "Season ${n.season ?: 1} · Episode ${n.episode}") else n.year
        col.addView(Ui.t(act, 23, 450, Ui.T2).apply { text = listOfNotNull(ep, dur).joinToString(" · ") }, Ui.linear(-1, -2).apply { topMargin = p(10) })
        val external = f.subs.mapNotNull { SubtitleText.language(Uri.parse(it).lastPathSegment ?: "") }
        val subLangs = (facts?.subs.orEmpty() + external).ifEmpty { if (f.subs.isNotEmpty()) listOf("und") else emptyList() }
        val chips = (facts?.let { Badges.card(it) } ?: Badges.chips(null, f.name))
        val sub = Badges.short(subLangs) ?: if (f.subs.isNotEmpty()) "SRT" else null
        if (chips.isNotEmpty() || sub != null) col.addView(LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            chips.forEach { addView(badge(it, null), Ui.linear(-2, p(36)).apply { rightMargin = p(8) }) }
            sub?.let { addView(badge(it, R.drawable.ic_closed_caption), Ui.linear(-2, p(36)).apply { rightMargin = p(8) }) }
        }, Ui.linear(-1, -2).apply { topMargin = p(20) })
        val rows = ArrayList<Pair<String, String>>()
        Badges.names(facts?.audio.orEmpty(), ru)?.let { rows += tr("Звук", "Sound") to it }
        Badges.names(subLangs, ru, plural = true)?.let { rows += tr("Субтитры", "Subtitles") to it }
        if (f.size > 0) rows += tr("Размер", "Size") to Fmt.size(f.size, ru)
        if (h != null) rows += tr("Осталось", "Left") to Fmt.duration(h.duration - h.position, ru)
        else if (History.watched(act).any { it.key == History.key(Uri.parse(f.uri)) }) rows += tr("Просмотр", "Watched") to tr("Досмотрен", "To the end")
        if (rows.isNotEmpty()) col.addView(kv(rows), Ui.linear(-1, -2).apply { topMargin = p(26) })
        col.addView(Ui.t(act, 18, 450, Ui.T3, 3).apply { text = f.name; setLineSpacing(0f, 1.35f) }, Ui.linear(-1, -2).apply { topMargin = p(22) })
        insp.addView(col, Ui.frame(-1, -2))
    }

    private fun badge(t: String, icon: Int?): View = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(p(12), 0, p(12), 0)
        background = Ui.rounded(0, p(8).toFloat(), p(1.5f), Ui.LINE2)
        if (icon != null) addView(ImageView(act).apply { setImageDrawable(Ui.icon(act, icon, Ui.T1)) }, Ui.linear(p(22), p(22)).apply { rightMargin = p(6) })
        addView(Ui.t(act, 18, 640, Ui.T1).apply { text = t })
    }

    private fun kv(rows: List<Pair<String, String>>): View = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        rows.forEachIndexed { i, (k, v) ->
            addView(LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(Ui.t(act, 21, 450, Ui.T3).apply { text = k }, Ui.linear(p(128), -2))
                addView(Ui.t(act, 21, 450, Ui.T1, 4).apply { text = v; setLineSpacing(0f, 1.25f) }, Ui.linear(0, -2, 1f))
            }, Ui.linear(-1, -2).apply { if (i > 0) topMargin = p(12) })
        }
    }
}

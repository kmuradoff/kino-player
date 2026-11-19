package com.xgimiru.player.settings

import android.app.Activity
import android.app.AlertDialog
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.media3.common.text.Cue
import androidx.media3.ui.SubtitleView
import com.xgimiru.player.BuildConfig
import com.xgimiru.player.History
import com.xgimiru.player.R
import com.xgimiru.player.TorrEngine
import com.xgimiru.player.TorrService
import com.xgimiru.player.TorrSettings
import com.xgimiru.player.dlna.DlnaSettings
import com.xgimiru.player.dovi.HdrPolicy
import com.xgimiru.player.media.Arts
import com.xgimiru.player.send.SendSettings
import com.xgimiru.player.ui.Img
import com.xgimiru.player.ui.Pane
import com.xgimiru.player.ui.Ui
import com.xgimiru.player.ui.round
import com.xgimiru.player.ui.tr

/**
 * Player settings, Kodi style: sections on the left, rows in the middle, and for subtitles a preview on the
 * right that shows the text in its real size and style over a frame. OK on a list row opens its choices in
 * place of the rows (the preview follows the focused choice), a switch flips at once, delays change with
 * Left / Right. Everything is stored at once.
 */
class SettingsPane(private val act: Activity) : Pane {
    private sealed class Row(val title: String) {
        class Of(val s: Setting) : Row("")
        class Flag(title: String, val get: () -> Boolean, val set: (Boolean) -> Unit) : Row(title)
        class Action(title: String, val value: String?, val key: String? = null, val run: () -> Unit) : Row(title)
    }

    private class Tab(val id: String, val label: String, val icon: Int, val section: Section?)

    private val cfg = act.playerSettings()
    private val ru get() = tr("ru", "en") == "ru"
    private val p = { v: Number -> Ui.px(act, v) }
    private val root = FrameLayout(act)
    override val view: View = root
    private val cats = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(act).apply {
        isVerticalScrollBarEnabled = false; isFocusable = false; setDefaultFocusHighlightEnabled(false); clipChildren = false
        addView(list)
    }
    private val sheet = FrameLayout(act).apply { visibility = View.GONE }
    private val preview = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private val subView = SubtitleView(act)
    private val catViews = ArrayList<View>()
    private var tab = 0
    private var sheetKey: String? = null
    private var built = false

    private val tabs = listOf(
        Tab("video", tr("Видео", "Video"), R.drawable.ic_movie, Section.VIDEO),
        Tab("audio", tr("Звук", "Audio"), R.drawable.ic_graphic_eq, Section.AUDIO),
        Tab("subtitles", tr("Субтитры", "Subtitles"), R.drawable.ic_subtitles, Section.SUBTITLES),
        Tab("interface", tr("Интерфейс", "Interface"), R.drawable.ic_tune, Section.INTERFACE),
        Tab("casting", tr("Трансляция", "Casting"), R.drawable.ic_cast, null),
        Tab("torrserver", "TorrServer", R.drawable.ic_dns, null),
        Tab("about", tr("О приложении", "About"), R.drawable.ic_info, null),
    )

    override fun onShow() {
        if (!built) build()
        val want = pending?.first ?: tabs[tab].id
        tab = tabs.indexOfFirst { it.id == want }.coerceAtLeast(0)
        showTab(tab)
        // asked for from outside (the player, a theme change): kept until the user presses a key, because Android
        // may focus the first category while the window is laid out and that would switch the section back
        wantTab = tab
        wantKey = pending?.second
        pending = null
    }

    private var wantTab: Int? = null
    private var wantKey: String? = null

    private fun build() {
        built = true
        root.addView(Ui.t(act, 44, 680, Ui.T1, spacing = -.8f).apply { text = tr("Настройки плеера", "Player settings") }, Ui.at(act, 56, 56))
        tabs.forEachIndexed { i, t -> catViews += category(i, t).also { cats.addView(it, Ui.linear(-1, p(64)).apply { bottomMargin = p(6) }) } }
        root.addView(cats, Ui.at(act, 56, 136, p(256)))
        root.addView(scroll, Ui.at(act, 340, 136, p(604), p(904)))
        root.addView(sheet, Ui.at(act, 340, 136, p(604), -2))
        preview.addView(Ui.t(act, 17, 650, Ui.T3, spacing = 2.4f).apply { text = tr("Так будет на экране", "How it will look").uppercase() },
            Ui.linear(-2, -2).apply { leftMargin = p(4); topMargin = p(6); bottomMargin = p(16) })
        val box = FrameLayout(act).apply { setBackgroundColor(0xFF000000.toInt()); round(p(16).toFloat()) }
        val still = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        box.addView(View(act).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF3A4150.toInt(), 0xFF161A21.toInt()))
        }, Ui.frame(-1, -1))
        box.addView(still, Ui.frame(-1, -1))
        box.addView(subView, Ui.frame(-1, -1))
        preview.addView(box, Ui.linear(p(456), p(300)))
        preview.addView(Ui.t(act, 20, 450, Ui.T3, 4).apply {
            text = tr("Размер в натуральную величину. Внешние субтитры .srt рядом с видео подхватываются сами.",
                "Shown at its real size. External .srt subtitles next to a video are picked up by themselves.")
            setLineSpacing(0f, 1.4f)
        }, Ui.linear(-1, -2).apply { topMargin = p(18) })
        root.addView(preview, Ui.at(act, 976, 136, p(456)))
        History.recent(act).firstOrNull()?.let { e -> Arts.find(act, e.uri, e.title) { a -> (a.wide ?: a.poster)?.let { Img.load(still, it, 920) } } }
        restyle()
    }

    /** The preview with the stored style, or with one choice tried on ([trial] = key to value). */
    private fun restyle(trial: Pair<String, String>? = null) {
        val store = MapStore(HashMap(K.ALL.associate { it.key to (cfg.raw(it) ?: "") }.filterValues { it.isNotEmpty() }))
        trial?.let { store.put(it.first, it.second) }
        val s = Settings(store)
        SubStyle.apply(subView, s, SubStyle.textPx(s, act.resources.displayMetrics.heightPixels))
        subView.setCues(listOf(Cue.Builder().setText(tr("Я же говорила, что он вернётся.", "I told you he would come back.")).build()))
    }

    // ── Sections ──
    private fun category(i: Int, t: Tab): View = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(p(18), 0, p(18), 0)
        isFocusable = true; isClickable = true
        val icon = ImageView(act)
        val label = Ui.t(act, 25, 520, Ui.T2).apply { text = t.label }
        addView(icon, Ui.linear(p(28), p(28)).apply { rightMargin = p(16) })
        addView(label, Ui.linear(0, -2, 1f))
        fun paint() {
            val f = isFocused; val cur = i == tab
            background = if (f || cur) Ui.rounded(if (f) Ui.ACC else Ui.S1, p(14).toFloat()) else null
            val c = if (f) Ui.WHITE else if (cur) Ui.T1 else Ui.T2
            label.setTextColor(c); Ui.weight(label, if (cur) 640 else 520, 25)
            icon.setImageDrawable(Ui.icon(act, t.icon, c))
        }
        tag = Runnable { paint() }
        paint()
        setOnFocusChangeListener { _, f -> if (f && tab != i) showTab(i); catViews.forEach { (it.tag as Runnable).run() } }
        setOnClickListener { firstRow()?.requestFocus() }
        setOnKeyListener { _, code, ev -> ev.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_RIGHT && firstRow()?.requestFocus() == true }
    }

    private fun showTab(i: Int) {
        tab = i
        val t = tabs[i]
        list.removeAllViews()
        list.addView(Ui.t(act, 17, 650, Ui.T3, spacing = 2.4f).apply { text = t.label.uppercase() },
            Ui.linear(-2, -2).apply { leftMargin = p(20); topMargin = p(6); bottomMargin = p(16) })
        for (r in rows(t)) list.addView(rowView(r), Ui.linear(-1, p(70)).apply { bottomMargin = p(4) })
        chainFocus()
        scroll.scrollTo(0, 0)
        preview.visibility = if (t.section == Section.SUBTITLES) View.VISIBLE else View.GONE
        catViews.forEach { (it.tag as Runnable).run() }
    }

    private fun rows(t: Tab): List<Row> {
        val s = t.section ?: return when (t.id) { "casting" -> casting(); "torrserver" -> torrserver(); else -> about() }
        val out = K.of(s).filter { cfg.visible(it) }.map { Row.Of(it) as Row }.toMutableList()
        if (s == Section.VIDEO) out.addAll(out.indexOfFirst { it is Row.Of && it.s == K.SCALE }.coerceAtLeast(0), hdrRows())
        return out
    }

    /** Dolby Vision on the display and HDR10+: only when the display and the decoder can do it. */
    private fun hdrRows(): List<Row> {
        val caps = HdrPolicy.caps(act)
        val p = HdrPolicy.prefs(act)
        val out = ArrayList<Row>()
        if (caps.dvHardware) {
            out += Row.Flag(tr("Dolby Vision через проектор", "Dolby Vision on the display"), { HdrPolicy.prefs(act).dvHardware }) { HdrPolicy.set(act, "dvHardware", it) }
            if (p.dvHardware && 8 in caps.dvProfiles && 7 !in caps.dvProfiles)
                out += Row.Flag(tr("Профиль 7 как 8.1", "Profile 7 as 8.1"), { HdrPolicy.prefs(act).p7to81 }) { HdrPolicy.set(act, "dvP7to81", it) }
            if (p.dvHardware)
                out += Row.Flag(tr("Убрать поля кадра (L5)", "Remove frame borders (L5)"), { HdrPolicy.prefs(act).zeroL5 }) { HdrPolicy.set(act, "dvZeroL5", it) }
        }
        if (caps.hdr10PlusDisplay) out += Row.Flag("HDR10+", { HdrPolicy.prefs(act).hdr10Plus }) { HdrPolicy.set(act, "hdr10Plus", it) }
        return out
    }

    /** DLNA renderer (phones and computers cast to Lumen Play) and browsers allowed to send videos. */
    private fun casting(): List<Row> {
        val out = ArrayList<Row>()
        out += Row.Flag(tr("Трансляция с телефона (DLNA)", "Casting from phones (DLNA)"), { DlnaSettings.enabled(act) }) { DlnaSettings.setEnabled(act, it) }
        if (!DlnaSettings.canOpenFromBackground(act)) DlnaSettings.overlayIntent(act)?.let { i ->
            out += Row.Action(tr("Открываться поверх других приложений", "Open over other apps"), tr("не разрешено", "not allowed")) {
                runCatching { act.startActivity(i) }
            }
        }
        val devices = SendSettings.devices(act)
        if (devices.isEmpty()) out += Row.Action(tr("Отправка из браузера", "Send from a browser"), tr("пока никого", "nobody yet")) { SendSettings.explain(act) }
        for (dev in devices) out += Row.Action(dev.client, SendSettings.connected(dev)) { SendSettings.confirmRemove(act, dev) { reload() } }
        return out
    }

    /** Built-in TorrServer: the mode for Lampa (127.0.0.1:8090), its state and cache. */
    private fun torrserver(): List<Row> {
        if (!TorrEngine.isAvailable(act)) return listOf(Row.Action(tr("Встроенный TorrServer", "Built-in TorrServer"), tr("нет в этой сборке", "not in this build")) {})
        return listOf(
            Row.Flag(tr("TorrServer для Лампы и других приложений", "TorrServer for Lampa and other apps"), { TorrSettings.shared(act) }) { on ->
                TorrSettings.setShared(act, on)
                if (on) TorrService.hold(act, TorrService.SHARED) else TorrService.release(act, TorrService.SHARED)
                list.postDelayed({ if (tabs[tab].id == "torrserver") reload() }, 3000)
            },
            Row.Action(tr("Состояние", "Status"), torrStatus()) { reload() },
            Row.Action(tr("Адрес для Лампы", "Address for Lampa"), "127.0.0.1:${TorrEngine.SHARED_PORT}") {},
            Row.Action(tr("Кэш", "Cache"), cacheLabel(TorrSettings.cacheMb(act)), CACHE_KEY) { pickCache() },
            Row.Action(tr("Версия", "Version"), "TorrServer ${BuildConfig.TORRSERVER}, GPL 3.0") {},
        )
    }

    private fun torrStatus(): String = when {
        TorrEngine.isRunning -> tr("работает: 127.0.0.1:${TorrEngine.port}", "running: 127.0.0.1:${TorrEngine.port}")
        TorrEngine.lastError != null -> tr("не запустился: ", "did not start: ") + TorrEngine.lastError
        else -> tr("выключен", "off")
    }

    private fun cacheLabel(mb: Int): String =
        if (mb > 0) "$mb MB" else tr("авто, ", "auto, ") + "${TorrSettings.autoCacheMb(TorrSettings.totalRamMb(act))} MB"

    private fun pickCache() {
        val opts = TorrSettings.CACHE_CHOICES.map { mb -> Option("$mb", cacheLabel(mb), cacheLabel(mb)) }
        openSheet(CACHE_KEY, tr("Кэш", "Cache"), opts, "${TorrSettings.cacheMb(act)}") { v ->
            TorrSettings.setCacheMb(act, v.toInt())
            if (TorrEngine.isRunning) Thread { runCatching { TorrEngine.applySettings(act) } }.start()
        }
    }

    private fun about(): List<Row> = listOf(
        Row.Action(tr("Версия", "Version"), BuildConfig.VERSION_NAME) {},
        Row.Action(tr("Движок", "Engine"), "Media3 1.6.1") {},
        Row.Action(tr("Лицензии", "Licences"), "Apache 2.0, LGPL 2.1, GPL 3.0") {},
        Row.Action(tr("Сбросить настройки плеера", "Reset player settings"), null) { confirmReset() },
    )

    private fun reload() {
        val idx = rowViews().indexOf(act.currentFocus).takeIf { it >= 0 }
        showTab(tab)
        if (idx != null) list.post { (rowViews().getOrNull(idx) ?: firstRow())?.requestFocus() }
    }

    fun onRestart() { if (tabs[tab].section == null && sheetKey == null) reload() }

    // ── Rows ──
    private fun rowView(r: Row): View {
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(p(20), 0, p(20), 0)
            isFocusable = true; isClickable = true
            id = View.generateViewId()
        }
        val title = Ui.t(act, 24, 500, Ui.T1).apply { text = when (r) { is Row.Of -> if (ru) r.s.ru else r.s.en; else -> r.title } }
        row.addView(title, Ui.linear(0, -2, 1f))
        val value = Ui.t(act, 22, 450, Ui.T2).apply { gravity = Gravity.END; maxWidth = p(320) }
        val left = ImageView(act)
        val right = ImageView(act)
        val switch = View(act)
        val st = (r as? Row.Of)?.s as? Stepper
        when {
            r is Row.Of && r.s is Toggle || r is Row.Flag -> row.addView(switch, Ui.linear(p(54), p(32)).apply { leftMargin = p(16) })
            st != null -> {
                row.addView(left, Ui.linear(p(26), p(26)).apply { leftMargin = p(16) })
                row.addView(value, Ui.linear(-2, -2).apply { leftMargin = p(4); rightMargin = p(4) })
                row.addView(right, Ui.linear(p(26), p(26)))
            }
            r is Row.Of || r is Row.Action && r.key != null -> {
                row.addView(value, Ui.linear(-2, -2).apply { leftMargin = p(16); rightMargin = p(10) })
                row.addView(right, Ui.linear(p(26), p(26)))
            }
            else -> row.addView(value, Ui.linear(-2, -2).apply { leftMargin = p(16) })
        }
        if (r is Row.Of) row.tag = r.s
        if (r is Row.Action && r.key != null) row.tag = r.key
        fun on(): Boolean = when (r) { is Row.Flag -> r.get(); is Row.Of -> (r.s as? Toggle)?.let(cfg::bool) ?: false; else -> false }
        fun paint() {
            val f = row.isFocused
            row.background = if (f) Ui.rounded(Ui.ACC, p(14).toFloat()) else null
            title.setTextColor(if (f) Ui.WHITE else Ui.T1)
            value.setTextColor(if (f) Ui.WHITE else Ui.T2)
            value.text = when (r) { is Row.Of -> cfg.label(r.s, ru); is Row.Action -> r.value ?: ""; else -> "" }
            val chev = if (f) Ui.WHITE else Ui.T3
            left.setImageDrawable(Ui.icon(act, R.drawable.ic_chevron_left, chev))
            right.setImageDrawable(Ui.icon(act, R.drawable.ic_chevron_right, chev))
            left.visibility = if (st != null && !f) View.INVISIBLE else View.VISIBLE
            if (st != null) right.visibility = left.visibility
            switch.background = switchDrawable(on(), f)
        }
        paint()
        row.setOnFocusChangeListener { _, _ -> paint() }
        row.setOnClickListener {
            when (r) {
                is Row.Flag -> { r.set(!r.get()); refresh(r, row) }
                is Row.Action -> r.run()
                is Row.Of -> when (val s = r.s) {
                    is Toggle -> { cfg.set(s, !cfg.bool(s)); refresh(r, row); restyle() }
                    is Choice -> openSheet(s)
                    is Stepper -> Unit
                }
            }
            paint()
        }
        row.setOnKeyListener { _, code, ev ->
            if (ev.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { if (st != null) { cfg.set(st, cfg.int(st) - st.step); paint() } else catViews[tab].requestFocus(); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { if (st != null) { cfg.set(st, cfg.int(st) + st.step); paint() }; true }
                else -> false
            }
        }
        return row
    }

    /** A row changed a value other rows depend on (formats of passthrough, second language): rebuild. */
    private fun refresh(r: Row, row: View) {
        val key = (r as? Row.Of)?.s?.key
        if (key == K.PASSTHROUGH.key || key == K.SUB_LANG.key || r is Row.Flag) {
            val idx = list.indexOfChild(row)
            showTab(tab)
            list.post { (list.getChildAt(idx) ?: firstRow())?.requestFocus() }
        }
    }

    private fun rowViews(): List<View> = (0 until list.childCount).map { list.getChildAt(it) }.filter { it.isFocusable }
    private fun firstRow(): View? = rowViews().firstOrNull()

    /** Up from the first row and down from the last stay put (the focus would jump into the sections). */
    private fun chainFocus() {
        val r = rowViews()
        r.firstOrNull()?.let { it.nextFocusUpId = it.id }
        r.lastOrNull()?.let { it.nextFocusDownId = it.id }
        r.forEach { it.nextFocusRightId = it.id }
    }

    private fun switchDrawable(on: Boolean, focused: Boolean): LayerDrawable {
        val track = Ui.rounded(when {
            focused && on -> 0xF2FFFFFF.toInt(); focused -> 0x4DFFFFFF; on -> Ui.ACC; else -> Ui.S2
        }, p(16).toFloat())
        val knob = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(when { focused && on -> Ui.ACC; focused || on -> Ui.WHITE; else -> Ui.T3 })
        }
        return LayerDrawable(arrayOf(track, knob)).apply {
            val k = p(24); val pad = p(4)
            setLayerSize(1, k, k)
            setLayerGravity(1, Gravity.CENTER_VERTICAL or if (on) Gravity.END else Gravity.START)
            setLayerInset(1, pad, 0, pad, 0)
        }
    }

    // ── Choices, shown in place of the rows ──
    private fun openSheet(c: Choice) = openSheet(c.key, if (ru) c.ru else c.en, c.options, cfg.string(c)) { cfg.set(c, it) }

    private fun openSheet(key: String, heading: String, options: List<Option>, cur: String, pick: (String) -> Unit) {
        sheetKey = key
        sheet.removeAllViews()
        val card = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(Ui.S1, p(16).toFloat())
            setPadding(p(8), p(22), p(8), p(8))
            elevation = p(16).toFloat()
        }
        card.addView(Ui.t(act, 17, 650, Ui.T3, spacing = 2.4f).apply { text = heading.uppercase() },
            Ui.linear(-2, -2).apply { leftMargin = p(20); bottomMargin = p(14) })
        val opts = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        var focus: View? = null
        val subtitles = tabs[tab].section == Section.SUBTITLES
        for (o in options) {
            val sel = o.value == cur
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                minimumHeight = p(64)
                setPadding(p(20), p(8), p(20), p(8))
                isFocusable = true; isClickable = true
            }
            val dot = View(act)
            val label = Ui.t(act, 24, if (sel) 600 else 500, Ui.T1, 2).apply { text = if (ru) o.ru else o.en }
            row.addView(dot, Ui.linear(p(26), p(26)).apply { rightMargin = p(16) })
            row.addView(label, Ui.linear(0, -2, 1f))
            fun paint() {
                val f = row.isFocused
                row.background = if (f) Ui.rounded(Ui.ACC, p(14).toFloat()) else null
                label.setTextColor(if (f) Ui.WHITE else Ui.T1)
                dot.background = radio(sel, f)
            }
            paint()
            row.setOnFocusChangeListener { _, f -> paint(); if (f && subtitles) restyle(key to o.value) }
            row.setOnClickListener {
                pick(o.value)
                showTab(tab)
                closeSheet()
            }
            opts.addView(row, Ui.linear(-1, -2).apply { bottomMargin = p(4) })
            if (sel) focus = row
        }
        val tall = options.size > 10
        card.addView(ScrollView(act).apply { isVerticalScrollBarEnabled = false; addView(opts) },
            if (tall) Ui.linear(-1, p(780)) else Ui.linear(-1, -2))
        sheet.addView(card, Ui.frame(-1, -2))
        sheet.visibility = View.VISIBLE
        scroll.visibility = View.INVISIBLE
        sheet.post { (focus ?: opts.getChildAt(0))?.requestFocus() }
    }

    private fun radio(selected: Boolean, focused: Boolean): LayerDrawable {
        val ink = if (focused) Ui.WHITE else if (selected) Ui.ACC else Ui.T3
        val ring = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(p(3), ink); setColor(0) }
        val core = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (selected) ink else 0) }
        return LayerDrawable(arrayOf(ring, core)).apply { val i = p(7); setLayerInset(1, i, i, i, i) }
    }

    /** Focus first goes back to the row under the choices, then the choices close: the focus never falls into the sections. */
    private fun closeSheet() {
        val key = sheetKey
        sheetKey = null
        scroll.visibility = View.VISIBLE
        (rowViews().firstOrNull { (it.tag as? Setting)?.key == key || (key != null && it.tag == key) } ?: firstRow())?.requestFocus()
        sheet.visibility = View.GONE
        sheet.removeAllViews()
        restyle()
        if (key == K.THEME.key) { pending = "interface" to key; act.recreate() }
    }

    private fun confirmReset() {
        AlertDialog.Builder(act, Ui.dialogTheme())
            .setTitle(tr("Сбросить настройки плеера?", "Reset player settings?"))
            .setMessage(tr("Все пункты вернутся к значениям по умолчанию.", "Every option goes back to its default."))
            .setPositiveButton(tr("Сбросить", "Reset")) { _, _ -> cfg.reset(); showTab(tab); restyle() }
            .setNegativeButton(tr("Отмена", "Cancel"), null)
            .show()
    }

    override fun focusFirst(): Boolean {
        wantTab?.let { if (tab != it) showTab(it) }
        val row = wantKey?.let { k -> rowViews().firstOrNull { (it.tag as? Setting)?.key == k || it.tag == k } }
        return (row ?: catViews.getOrNull(tab))?.requestFocus() == true
    }

    /** Inside the settings: Back closes the choices, then goes from the rows to the sections. */
    override fun onBack(): Boolean {
        if (sheetKey != null) { closeSheet(); return true }
        if (list.hasFocus()) { catViews[tab].requestFocus(); return true }
        return false
    }

    /** Left in the choices closes them (they sit where the rows were). */
    fun onKey(e: KeyEvent): Boolean {
        wantTab = null; wantKey = null
        if (sheetKey != null && e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) { if (e.action == KeyEvent.ACTION_UP) closeSheet(); return true }
        return false
    }

    val section get() = tabs[tab].id

    companion object {
        private const val CACHE_KEY = "torr.cache"
        /** Section and row to show after the screen was rebuilt (theme change, a link from a notification). */
        var pending: Pair<String, String?>? = null
    }
}

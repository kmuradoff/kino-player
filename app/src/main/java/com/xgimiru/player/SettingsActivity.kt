package com.xgimiru.player

import android.app.Activity
import android.app.AlertDialog
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.xgimiru.player.dovi.HdrPolicy
import com.xgimiru.player.settings.Choice
import com.xgimiru.player.settings.K
import com.xgimiru.player.settings.Section
import com.xgimiru.player.settings.Setting
import com.xgimiru.player.settings.Settings
import com.xgimiru.player.settings.Stepper
import com.xgimiru.player.settings.Toggle
import com.xgimiru.player.settings.playerSettings
import com.xgimiru.player.ui.Ui
import com.xgimiru.player.ui.tr

/**
 * Настройки плеера, как в Kodi: слева разделы (Видео, Звук, Субтитры, Интерфейс, О приложении), справа
 * строки со значением. OK на строке со списком открывает выбор справа, переключатель меняется сразу,
 * числа (задержки) меняются кнопками влево и вправо. Всё сохраняется сразу.
 */
class SettingsActivity : Activity() {

    private sealed class Row(val title: String) {
        class Of(val s: Setting) : Row("")
        class Flag(title: String, val get: () -> Boolean, val set: (Boolean) -> Unit) : Row(title)
        class Action(title: String, val value: String?, val run: () -> Unit) : Row(title)
    }

    private class Tab(val id: String, val label: String, val icon: Int, val section: Section?)

    private lateinit var cfg: Settings
    private val ru get() = tr("ru", "en") == "ru"
    private lateinit var rail: LinearLayout
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var overlay: FrameLayout
    private val railItems = ArrayList<View>()
    private var tab = 0
    private var sheetOpen = false
    private val d = { v: Number -> Ui.dp(this, v) }

    private val tabs by lazy {
        listOf(
            Tab("video", tr("Видео", "Video"), R.drawable.ic_videocam, Section.VIDEO),
            Tab("audio", tr("Звук", "Audio"), R.drawable.ic_volume_up, Section.AUDIO),
            Tab("subtitles", tr("Субтитры", "Subtitles"), R.drawable.ic_subtitles, Section.SUBTITLES),
            Tab("interface", tr("Интерфейс", "Interface"), R.drawable.ic_tune, Section.INTERFACE),
            Tab("about", tr("О приложении", "About"), R.drawable.ic_info, null),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this)
        cfg = playerSettings()
        window.setBackgroundDrawable(Ui.field())
        val root = FrameLayout(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(d(48), d(30), d(48), d(24)) }
        col.addView(Ui.text(this, 26f, Ui.INK, Ui.serif()).apply { text = tr("Настройки плеера", "Player settings") },
            Ui.linear(-2, -2).apply { leftMargin = d(8); bottomMargin = d(18) })
        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        rail = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(rail, Ui.linear(d(220), -1).apply { rightMargin = d(20) })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(d(10), d(10), d(10), d(10)) }
        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFocusable = false
            setDefaultFocusHighlightEnabled(false)
            background = Ui.rounded(Ui.SURFACE, d(22).toFloat())
            clipToOutline = true
            addView(list)
        }
        body.addView(scroll, Ui.linear(0, -1, 1f))
        col.addView(body, Ui.linear(-1, 0, 1f))
        root.addView(col, Ui.frame(-1, -1))
        overlay = FrameLayout(this).apply { visibility = View.GONE }
        root.addView(overlay, Ui.frame(-1, -1))
        setContentView(root)

        tabs.forEachIndexed { i, t -> railItems += railItem(i, t).also { rail.addView(it, Ui.linear(-1, d(48)).apply { bottomMargin = d(4) }) } }
        tab = tabs.indexOfFirst { it.id == intent.getStringExtra("section") }.coerceAtLeast(0)
        showTab(tab)
        val focusKey = intent.getStringExtra("focus")
        val sheetKey = intent.getStringExtra("sheet")
        root.post {
            val row = focusKey?.let { k -> rowViews().firstOrNull { (it.tag as? Setting)?.key == k } }
            if (row != null) row.requestFocus() else railItems[tab].requestFocus()
            (K.ALL.firstOrNull { it.key == sheetKey } as? Choice)?.let { openSheet(it) }
        }
    }

    // ── Разделы слева ──
    private fun railItem(i: Int, t: Tab): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(d(16), 0, d(16), 0)
        isFocusable = true; isClickable = true
        val icon = ImageView(this@SettingsActivity)
        val label = Ui.text(this@SettingsActivity, 17f, Ui.INK2, Ui.medium()).apply { text = t.label }
        addView(icon, Ui.linear(d(22), d(22)).apply { rightMargin = d(14) })
        addView(label, Ui.linear(0, -2, 1f))
        fun paint() {
            val f = isFocused; val cur = i == tab
            background = Ui.rounded(if (f) Ui.FOCUS else if (cur) Ui.SOFT else 0, d(14).toFloat())
            val c = if (f) Ui.GROUND else if (cur) Ui.INK else Ui.INK2
            label.setTextColor(c); icon.setImageDrawable(Ui.icon(this@SettingsActivity, t.icon, c))
        }
        tag = Runnable { paint() }
        paint()
        setOnFocusChangeListener { _, f -> if (f && tab != i) showTab(i); railItems.forEach { (it.tag as Runnable).run() } }
        setOnClickListener { firstRow()?.requestFocus() }
        setOnKeyListener { _, code, ev ->
            ev.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_RIGHT && firstRow()?.requestFocus() == true
        }
    }

    private fun showTab(i: Int) {
        tab = i
        val t = tabs[i]
        list.removeAllViews()
        list.addView(Ui.text(this, 14f, Ui.INK3, Ui.medium()).apply {
            text = t.label
            setPadding(d(16), d(6), d(16), d(10))
        })
        for (r in rows(t)) list.addView(rowView(r), Ui.linear(-1, -2).apply { bottomMargin = d(2) })
        chainFocus()
        scroll.scrollTo(0, 0)
    }

    private fun rows(t: Tab): List<Row> {
        val s = t.section ?: return about()
        val out = K.of(s).filter { cfg.visible(it) }.map { Row.Of(it) as Row }.toMutableList()
        if (s == Section.VIDEO) out.addAll(out.indexOfFirst { it is Row.Of && it.s == K.SCALE }.coerceAtLeast(0), hdrRows())
        return out
    }

    /** Dolby Vision через проектор и HDR10+: только если экран и декодер это умеют. */
    private fun hdrRows(): List<Row> {
        val caps = HdrPolicy.caps(this)
        val p = HdrPolicy.prefs(this)
        val out = ArrayList<Row>()
        if (caps.dvHardware) {
            out += Row.Flag(tr("Dolby Vision через проектор", "Dolby Vision on the display"), { HdrPolicy.prefs(this).dvHardware }) { HdrPolicy.set(this, "dvHardware", it) }
            if (p.dvHardware && 8 in caps.dvProfiles && 7 !in caps.dvProfiles)
                out += Row.Flag(tr("Профиль 7 как 8.1", "Profile 7 as 8.1"), { HdrPolicy.prefs(this).p7to81 }) { HdrPolicy.set(this, "dvP7to81", it) }
            if (p.dvHardware)
                out += Row.Flag(tr("Убрать поля кадра (L5)", "Remove frame borders (L5)"), { HdrPolicy.prefs(this).zeroL5 }) { HdrPolicy.set(this, "dvZeroL5", it) }
        }
        if (caps.hdr10PlusDisplay) out += Row.Flag("HDR10+", { HdrPolicy.prefs(this).hdr10Plus }) { HdrPolicy.set(this, "hdr10Plus", it) }
        return out
    }

    private fun about(): List<Row> = listOf(
        Row.Action(tr("Версия", "Version"), BuildConfig.VERSION_NAME) {},
        Row.Action(tr("Движки", "Engines"), "Media3 1.6.1, libVLC 3.6.5") {},
        Row.Action(tr("Лицензии", "Licences"), "Apache 2.0, LGPL 2.1, GPL 3.0") {},
        Row.Action(tr("Сбросить настройки плеера", "Reset player settings"), null) { confirmReset() },
    )

    // ── Строки ──
    private fun rowView(r: Row): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = d(54)
            setPadding(d(16), d(10), d(14), d(10))
            isFocusable = true; isClickable = true
            id = View.generateViewId()
        }
        val title = Ui.text(this, 17f, Ui.INK, Ui.medium()).apply {
            text = when (r) { is Row.Of -> if (ru) r.s.ru else r.s.en; else -> r.title }
        }
        row.addView(title, Ui.linear(0, -2, 1f))
        val left = ImageView(this)
        val value = Ui.text(this, 15f, Ui.INK2)
        val right = ImageView(this)
        val switch = View(this)
        when {
            r is Row.Of && r.s is Toggle || r is Row.Flag -> row.addView(switch, Ui.linear(d(40), d(22)))
            r is Row.Of && r.s is Stepper -> {
                row.addView(left, Ui.linear(d(22), d(22)))
                row.addView(value, Ui.linear(-2, -2).apply { leftMargin = d(6); rightMargin = d(6) })
                row.addView(right, Ui.linear(d(22), d(22)))
            }
            r is Row.Of -> {
                row.addView(value, Ui.linear(-2, -2).apply { rightMargin = d(4); leftMargin = d(16) })
                row.addView(right, Ui.linear(d(22), d(22)))
            }
            else -> row.addView(value, Ui.linear(-2, -2).apply { leftMargin = d(16) })
        }
        if (r is Row.Of) row.tag = r.s
        fun on(): Boolean = when (r) { is Row.Flag -> r.get(); is Row.Of -> (r.s as? Toggle)?.let(cfg::bool) ?: false; else -> false }
        fun paint() {
            val f = row.isFocused
            row.background = Ui.rounded(if (f) Ui.FOCUS else 0, d(14).toFloat())
            title.setTextColor(if (f) Ui.GROUND else Ui.INK)
            val vc = if (f) Ui.withAlpha(Ui.GROUND, 0xCC) else Ui.INK2
            value.setTextColor(vc)
            value.text = when (r) { is Row.Of -> cfg.label(r.s, ru); is Row.Action -> r.value ?: ""; else -> "" }
            left.setImageDrawable(Ui.icon(this, R.drawable.ic_chevron_left, vc))
            right.setImageDrawable(Ui.icon(this, R.drawable.ic_chevron_right, vc))
            switch.background = switchDrawable(on(), f)
        }
        paint()
        row.setOnFocusChangeListener { _, _ -> paint() }
        row.setOnClickListener {
            when (r) {
                is Row.Flag -> { r.set(!r.get()); refresh(r, row) }
                is Row.Action -> r.run()
                is Row.Of -> when (val s = r.s) {
                    is Toggle -> { cfg.set(s, !cfg.bool(s)); refresh(r, row) }
                    is Choice -> openSheet(s)
                    is Stepper -> Unit
                }
            }
            paint()
        }
        row.setOnKeyListener { _, code, ev ->
            if (ev.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
            val st = (r as? Row.Of)?.s as? Stepper
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { if (st != null) { cfg.set(st, cfg.int(st) - st.step); paint() } else railItems[tab].requestFocus(); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { if (st != null) { cfg.set(st, cfg.int(st) + st.step); paint() }; true }
                else -> false
            }
        }
        return row
    }

    /** Строка поменяла значение: если от неё зависят другие строки (форматы звука, второй язык), перестроить. */
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

    /** Вверх с первой строки и вниз с последней никуда не уходят (иначе фокус прыгает в разделы). */
    private fun chainFocus() {
        val r = rowViews()
        r.firstOrNull()?.let { it.nextFocusUpId = it.id }
        r.lastOrNull()?.let { it.nextFocusDownId = it.id }
        r.forEach { it.nextFocusRightId = it.id }
    }

    private fun switchDrawable(on: Boolean, focused: Boolean): LayerDrawable {
        val track = Ui.rounded(if (on) Ui.ACCENT else Ui.withAlpha(if (focused) Ui.GROUND else Ui.INK3, 0x66), d(11).toFloat())
        val knob = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFFFFFFF.toInt()) }
        return LayerDrawable(arrayOf(track, knob)).apply {
            val k = d(16); val pad = d(3)
            setLayerSize(1, k, k)
            setLayerGravity(1, Gravity.CENTER_VERTICAL or if (on) Gravity.END else Gravity.START)
            setLayerInset(1, pad, 0, pad, 0)
        }
    }

    // ── Выбор из списка ──
    private fun openSheet(c: Choice) {
        sheetOpen = true
        overlay.tag = c.key
        overlay.removeAllViews()
        overlay.setBackgroundColor(0x80000000.toInt())
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(Ui.PANEL, d(24).toFloat())
            setPadding(d(14), d(22), d(14), d(14))
            elevation = d(12).toFloat()
        }
        card.addView(Ui.text(this, 20f, Ui.INK, Ui.serif()).apply { text = if (ru) c.ru else c.en; setPadding(d(14), 0, d(14), d(14)) })
        val opts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val cur = cfg.string(c)
        var focus: View? = null
        for (o in c.options) {
            val sel = o.value == cur
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = d(50)
                setPadding(d(14), d(8), d(14), d(8))
                isFocusable = true; isClickable = true
            }
            val dot = View(this)
            val label = Ui.text(this, 17f, Ui.INK, Ui.medium(), 2).apply { text = if (ru) o.ru else o.en }
            row.addView(dot, Ui.linear(d(20), d(20)).apply { rightMargin = d(14) })
            row.addView(label, Ui.linear(0, -2, 1f))
            fun paint() {
                val f = row.isFocused
                row.background = Ui.rounded(if (f) Ui.FOCUS else 0, d(14).toFloat())
                label.setTextColor(if (f) Ui.GROUND else Ui.INK)
                dot.background = radio(sel, f)
            }
            paint()
            row.setOnFocusChangeListener { _, _ -> paint() }
            row.setOnClickListener {
                cfg.set(c, o.value)
                closeSheet()
                val idx = rowViews().indexOfFirst { it.tag == c }
                showTab(tab)
                list.post { rowViews().getOrNull(idx)?.requestFocus() }
            }
            opts.addView(row, Ui.linear(-1, -2).apply { bottomMargin = d(2) })
            if (sel) focus = row
        }
        card.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(opts) }, Ui.linear(-1, -2))
        overlay.addView(card, Ui.frame(d(420), -2, Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = d(48) })
        overlay.visibility = View.VISIBLE
        overlay.post { (focus ?: opts.getChildAt(0))?.requestFocus() }
    }

    private fun radio(selected: Boolean, focused: Boolean): LayerDrawable {
        val ink = if (focused) Ui.GROUND else if (selected) Ui.ACCENT else Ui.INK3
        val ring = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(d(2), ink); setColor(0) }
        val core = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (selected) ink else 0) }
        return LayerDrawable(arrayOf(ring, core)).apply { val i = d(5); setLayerInset(1, i, i, i, i) }
    }

    private fun closeSheet() {
        sheetOpen = false
        overlay.visibility = View.GONE
        overlay.removeAllViews()
    }

    private fun confirmReset() {
        AlertDialog.Builder(this, R.style.Lumen_Dialog)
            .setTitle(tr("Сбросить настройки плеера?", "Reset player settings?"))
            .setMessage(tr("Все пункты вернутся к значениям по умолчанию.", "Every option goes back to its default."))
            .setPositiveButton(tr("Сбросить", "Reset")) { _, _ -> cfg.reset(); showTab(tab) }
            .setNegativeButton(tr("Отмена", "Cancel"), null)
            .show()
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (sheetOpen && (e.keyCode == KeyEvent.KEYCODE_BACK || e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT)) {
            if (e.action == KeyEvent.ACTION_UP) {
                val key = (overlay.tag as? String)
                closeSheet()
                rowViews().firstOrNull { (it.tag as? Setting)?.key == key }?.requestFocus() ?: firstRow()?.requestFocus()
            }
            return true
        }
        if (e.keyCode == KeyEvent.KEYCODE_BACK && list.hasFocus()) {
            if (e.action == KeyEvent.ACTION_UP) railItems[tab].requestFocus()
            return true
        }
        return super.dispatchKeyEvent(e)
    }
}

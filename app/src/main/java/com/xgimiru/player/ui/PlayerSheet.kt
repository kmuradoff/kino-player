package com.xgimiru.player.ui

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.xgimiru.player.R

/**
 * The player's panel: drops from the top over the picture, title with badges, four columns (Субтитры, Звук,
 * Задержка, Картинка). Always dark, whatever the app theme: it sits over a film. Rows with a value change it
 * with OK; a delay row turns Left / Right into steps after OK and gives them back after OK or Back.
 */
class PlayerSheet(ctx: Context) : FrameLayout(ctx) {
    /** One row: a choice with a check mark, or a value row with an icon. */
    class Opt(
        val label: String,
        val selected: Boolean = false,
        val icon: Int? = null,
        val value: String? = null,
        /** Delay rows: one step left (-1) or right (+1), returns the new value text. */
        val step: ((Int) -> String)? = null,
        val onClick: (() -> Unit)? = null,
    )

    class Column(val title: String, val opts: List<Opt>)

    private val p = { v: Number -> Ui.px(ctx, v) }
    private val card = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = Ui.rounded(0xF7131416.toInt(), p(30).toFloat())
        setPadding(p(36), p(32), p(36), p(30))
    }
    private val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val cols = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
    var editing: View? = null; private set
    private val painters = HashMap<View, () -> Unit>()
    val isOpen get() = visibility == VISIBLE && translationY == 0f

    init {
        card.addView(head, Ui.linear(-1, p(40)))
        card.addView(cols, Ui.linear(-1, -2).apply { topMargin = p(30) })
        addView(card, Ui.frame(-1, -2).apply { leftMargin = p(72); rightMargin = p(72); topMargin = p(48) })
        visibility = INVISIBLE
    }

    fun set(title: String, badges: List<String>, engine: String, columns: List<Column>) {
        head.removeAllViews()
        head.addView(Ui.t(context, 32, 680, INK, spacing = -.4f).apply { text = title }, Ui.linear(-2, -2).apply { rightMargin = p(20) })
        for (b in badges) head.addView(chip(context, b, size = 18, weight = 640, height = 36, pad = 12, radius = 8, ink = INK, line = 0x33FFFFFF),
            Ui.linear(-2, p(36)).apply { rightMargin = p(14) })
        head.addView(View(context), Ui.linear(0, 1, 1f))
        head.addView(Ui.t(context, 20, 450, FAINT).apply { text = engine })
        cols.removeAllViews()
        painters.clear()
        editing = null
        columns.forEachIndexed { i, c ->
            val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            col.addView(Ui.t(context, 17, 650, FAINT, spacing = 2.4f).apply { text = c.title.uppercase() },
                Ui.linear(-2, -2).apply { leftMargin = p(18); bottomMargin = p(12) })
            val rows = c.opts.map { row(it) }
            rows.forEach { col.addView(it, Ui.linear(-1, p(62))) }
            rows.firstOrNull()?.let { it.nextFocusUpId = it.id }
            rows.lastOrNull()?.let { it.nextFocusDownId = it.id }
            cols.addView(col, Ui.linear(0, -2, 1f).apply { if (i > 0) leftMargin = p(28) })
        }
    }

    private fun row(o: Opt): View {
        val r = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(p(18), 0, p(18), 0)
            isFocusable = true; isClickable = true
            id = generateViewId()
            if (o.selected) tag = SELECTED
        }
        val mark = ImageView(context)
        val label = Ui.t(context, 23, if (o.selected) 620 else 500, if (o.selected) Ui.WHITE else TEXT).apply { text = o.label }
        val less = ImageView(context).apply { visibility = GONE }
        val value = Ui.t(context, 23, 450, DIM).apply { text = o.value ?: ""; fontFeatureSettings = "tnum" }
        val more = ImageView(context).apply { visibility = GONE }
        r.addView(mark, Ui.linear(p(26), p(26)).apply { rightMargin = p(14) })
        r.addView(label, Ui.linear(0, -2, 1f))
        if (o.value != null) {
            r.addView(less, Ui.linear(p(22), p(22)))
            r.addView(value, Ui.linear(-2, -2).apply { leftMargin = p(4); rightMargin = p(4) })
            r.addView(more, Ui.linear(p(22), p(22)))
        }
        fun paint() {
            val f = r.isFocused
            r.background = if (f) Ui.rounded(Ui.ACC, p(14).toFloat()) else null
            val ink = if (f || o.selected) Ui.WHITE else TEXT
            label.setTextColor(ink)
            value.setTextColor(if (f) Ui.WHITE else DIM)
            val icon = o.icon ?: R.drawable.ic_check
            mark.setImageDrawable(if (o.icon == null && !o.selected) ColorDrawable(0) else Ui.icon(context, icon, if (f) Ui.WHITE else if (o.icon != null) DIM else INK))
            val edit = editing === r
            less.visibility = if (edit) VISIBLE else GONE; more.visibility = less.visibility
            less.setImageDrawable(Ui.icon(context, R.drawable.ic_chevron_left, Ui.WHITE))
            more.setImageDrawable(Ui.icon(context, R.drawable.ic_chevron_right, Ui.WHITE))
        }
        r.setOnFocusChangeListener { _, f -> if (!f && editing === r) editing = null; paint() }
        r.setOnClickListener {
            if (o.step != null) { editing = if (editing === r) null else r; paint() }
            else o.onClick?.invoke()
        }
        r.setOnKeyListener { _, code, ev ->
            if (editing !== r || o.step == null) return@setOnKeyListener false
            val dir = when (code) { KeyEvent.KEYCODE_DPAD_LEFT -> -1; KeyEvent.KEYCODE_DPAD_RIGHT -> 1; KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> 0; else -> return@setOnKeyListener false }
            if (ev.action == KeyEvent.ACTION_DOWN && dir != 0) value.text = o.step.invoke(dir)
            true
        }
        painters[r] = ::paint
        paint()
        return r
    }

    /** Back while a delay row takes Left / Right: give the arrows back. */
    fun stopEditing(): Boolean {
        val r = editing ?: return false
        editing = null
        painters[r]?.invoke()
        return true
    }

    fun open() {
        visibility = VISIBLE
        post {
            translationY = -card.height.toFloat() - p(48)
            animate().translationY(0f).setDuration(260).setInterpolator(DecelerateInterpolator()).start()
            (findViewWithTag<View>(SELECTED) ?: firstFocusable(cols))?.requestFocus()
        }
    }

    fun close(done: () -> Unit = {}) {
        editing = null
        animate().translationY(-card.height.toFloat() - p(48)).setDuration(220).withEndAction { visibility = INVISIBLE; done() }.start()
    }

    /** Keeps the focus on the row that had it after the columns were rebuilt. */
    fun focusAt(column: Int, row: Int) {
        val col = cols.getChildAt(column) as? LinearLayout ?: return
        (col.getChildAt(row + 1) ?: col.getChildAt(1))?.requestFocus()
    }

    /** Column and row of the focused row, or null. */
    fun focusedAt(): Pair<Int, Int>? {
        for (c in 0 until cols.childCount) {
            val col = cols.getChildAt(c) as LinearLayout
            for (r in 1 until col.childCount) if (col.getChildAt(r).isFocused) return c to r - 1
        }
        return null
    }

    private fun firstFocusable(v: View): View? {
        if (v.isFocusable && v !is LinearLayout || v.isFocusable && v.isClickable) return v
        if (v is LinearLayout) for (i in 0 until v.childCount) firstFocusable(v.getChildAt(i))?.let { return it }
        return null
    }

    private companion object {
        const val SELECTED = "selected"
        const val INK = 0xFFF2F3F5.toInt()
        const val TEXT = 0xFFD9DCE1.toInt()
        const val DIM = 0xFFAEB3BB.toInt()
        const val FAINT = 0xFF80858D.toInt()
    }
}

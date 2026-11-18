package com.xgimiru.player.ui

import android.content.Context
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Раздел главного экрана (Главная, ТВ, Торренты, Файлы). */
interface Pane {
    val view: View
    /** Раздел стал видимым: обновить данные. */
    fun onShow() {}
    fun onHide() {}
    /** Отдать фокус первому элементу; false — фокусировать нечего. */
    fun focusFirst(): Boolean
    /** «Назад» внутри раздела (закрыть поиск, подняться из папки). true — обработано. */
    fun onBack(): Boolean = false
}

/** Хост разделов: фон и блок описания общие. */
interface Host {
    fun backdrop(url: String?)
    fun info(eyebrow: String?, title: String?, meta: String?, desc: String?, progress: Float = -1f)
}

/**
 * Карточка 16:9 (или квадратная для логотипов): картинка, подпись снизу, полоска прогресса.
 * Без картинки — тёплая заливка с названием, никогда не серый прямоугольник.
 */
class Card(ctx: Context, wDp: Int, hDp: Int, logo: Boolean = false, plain: Boolean = false) : FrameLayout(ctx) {
    val image = ImageView(ctx)
    val label: TextView
    val sub: TextView
    private val bar = View(ctx)
    private val barBox = FrameLayout(ctx)
    private val r = Ui.dp(ctx, 14).toFloat()

    init {
        val d = { v: Number -> Ui.dp(ctx, v) }
        layoutParams = LinearLayout.LayoutParams(d(wDp), d(hDp)).apply { rightMargin = d(18) }
        background = if (plain) Ui.rounded(Ui.SURFACE, r)
            else GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF3A4252.toInt(), 0xFF1B1E25.toInt())).apply { cornerRadius = r }
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, r)
        }
        image.scaleType = if (logo) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
        if (logo) image.setPadding(d(26), d(22), d(26), d(34))
        addView(image, Ui.frame(-1, -1))
        if (!logo && !plain) addView(View(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xE6000000.toInt(), 0x00000000, 0x00000000))
        }, Ui.frame(-1, -1))
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(d(14), 0, d(14), d(12)) }
        label = Ui.text(ctx, if (logo) 14f else 16f, ON_IMAGE, Ui.medium())
        sub = Ui.text(ctx, 12f, ON_IMAGE2)
        if (plain) { label.setTextColor(Ui.INK); sub.setTextColor(Ui.INK2) }
        col.addView(label); col.addView(sub, Ui.linear(-1, -2).apply { topMargin = d(2) })
        barBox.background = Ui.rounded(0x40000000, d(2).toFloat())
        bar.background = Ui.rounded(0xFFFFFFFF.toInt(), d(2).toFloat())
        barBox.addView(bar, Ui.frame(0, -1))
        barBox.visibility = GONE
        col.addView(barBox, Ui.linear(-1, d(4)).apply { topMargin = d(8) })
        addView(col, Ui.frame(-1, -2, Gravity.BOTTOM))
        if (logo) label.gravity = Gravity.CENTER
        isFocusable = true
        isClickable = true
        foreground = GradientDrawable().apply { cornerRadius = r; setStroke(0, 0) }
        setOnFocusChangeListener { _, f ->
            (foreground as GradientDrawable).setStroke(if (f) d(3) else 0, Ui.RING)
            animate().scaleX(if (f) 1.07f else 1f).scaleY(if (f) 1.07f else 1f).translationZ(if (f) 10f else 0f).setDuration(160).start()
            onFocus?.invoke(f)
            if (f) onRow?.invoke()
        }
    }

    var onFocus: ((Boolean) -> Unit)? = null
    var onRow: (() -> Unit)? = null

    fun progress(f: Float) {
        barBox.visibility = if (f > 0f) VISIBLE else GONE
        barBox.post { bar.layoutParams = (bar.layoutParams as LayoutParams).apply { width = (barBox.width * f.coerceIn(0f, 1f)).toInt() }; bar.requestLayout() }
    }
}

/** Горизонтальный ряд карточек с заголовком. Фокус уходит в ряд и прокручивает его плавно. */
class Row(ctx: Context, title: String) : LinearLayout(ctx) {
    val heading: TextView = Ui.text(ctx, 17f, Ui.INK2, Ui.medium())
    val strip = LinearLayout(ctx).apply { orientation = HORIZONTAL; clipChildren = false; clipToPadding = false }
    private val scroll = HorizontalScrollView(ctx).apply {
        isHorizontalScrollBarEnabled = false
        clipChildren = false; clipToPadding = false
        isSmoothScrollingEnabled = true
    }

    init {
        orientation = VERTICAL
        clipChildren = false; clipToPadding = false
        val d = { v: Number -> Ui.dp(ctx, v) }
        heading.text = title
        heading.letterSpacing = 0.02f
        addView(heading, Ui.linear(-1, -2).apply { leftMargin = d(56); bottomMargin = d(14) })
        strip.setPadding(d(56), d(10), d(56), d(14))
        scroll.addView(strip)
        addView(scroll, Ui.linear(-1, -2))
    }

    fun add(v: View) { strip.addView(v) }
    fun clear() = strip.removeAllViews()
    val size get() = strip.childCount
    fun first(): View? = strip.getChildAt(0)
}

/** Текст на картинке под тёмным затемнением: светлый в обеих темах. */
const val ON_IMAGE = 0xFFF2F4F7.toInt()
const val ON_IMAGE2 = 0xFFD5D9E0.toInt()

/** Блок описания слева сверху: надпись, крупное название, строка с прогрессом, описание. */
class Info(ctx: Context) : LinearLayout(ctx) {
    private val eyebrow = Ui.text(ctx, 13f, Ui.INK2, Ui.bold()).apply { letterSpacing = 0.16f }
    private val title = Ui.text(ctx, 36f, Ui.INK, Ui.serif(), 2)
    private val meta = Ui.text(ctx, 15f, Ui.INK2)
    private val desc = Ui.text(ctx, 15f, Ui.INK2, Ui.sans(), 3).apply { setLineSpacing(0f, 1.3f) }
    private val bar = View(ctx)
    private val barBox = FrameLayout(ctx)
    private val metaRow = LinearLayout(ctx)

    init {
        orientation = VERTICAL
        val d = { v: Number -> Ui.dp(ctx, v) }
        addView(eyebrow)
        addView(title, Ui.linear(-1, -2).apply { topMargin = d(10); bottomMargin = d(10) })
        metaRow.orientation = HORIZONTAL
        metaRow.gravity = Gravity.CENTER_VERTICAL
        barBox.background = Ui.rounded(Ui.withAlpha(Ui.INK3, 0x55), d(2).toFloat())
        bar.background = Ui.rounded(Ui.PROGRESS, d(2).toFloat())
        barBox.addView(bar, Ui.frame(0, -1))
        metaRow.addView(barBox, Ui.linear(d(110), d(3)).apply { rightMargin = d(12) })
        metaRow.addView(meta)
        addView(metaRow, Ui.linear(-1, -2).apply { bottomMargin = d(10) })
        addView(desc, Ui.linear(d(440), -2))
    }

    /** Над картинкой текст светлый, над пустым полем цвета темы. */
    fun onImage(image: Boolean) {
        val a = if (image) ON_IMAGE else Ui.INK
        val b = if (image) ON_IMAGE2 else Ui.INK2
        title.setTextColor(a); eyebrow.setTextColor(b); meta.setTextColor(b); desc.setTextColor(b)
    }

    fun set(e: String?, t: String?, m: String?, ds: String?, progress: Float) {
        animate().cancel()
        alpha = 0f
        eyebrow.text = e?.uppercase() ?: ""; eyebrow.visibility = if (e.isNullOrEmpty()) GONE else VISIBLE
        title.text = t ?: ""
        meta.text = m ?: ""
        metaRow.visibility = if (m.isNullOrEmpty() && progress < 0) GONE else VISIBLE
        barBox.visibility = if (progress >= 0) VISIBLE else GONE
        barBox.post { bar.layoutParams = (bar.layoutParams as FrameLayout.LayoutParams).apply { width = (barBox.width * progress.coerceIn(0f, 1f)).toInt() }; bar.requestLayout() }
        desc.text = ds ?: ""; desc.visibility = if (ds.isNullOrEmpty()) GONE else VISIBLE
        visibility = if (t.isNullOrEmpty()) INVISIBLE else VISIBLE
        animate().alpha(1f).setDuration(260).start()
    }
}

/** Пустой раздел: короткая подсказка и одна кнопка, без лишнего. */
fun emptyState(ctx: Context, title: String, text: String, button: String?, onClick: (() -> Unit)?): LinearLayout =
    LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        val d = { v: Number -> Ui.dp(ctx, v) }
        setPadding(d(56), 0, d(56), 0)
        addView(Ui.text(ctx, 34f, Ui.INK, Ui.serif()).apply { this.text = title })
        addView(Ui.text(ctx, 16f, Ui.INK2, Ui.sans(), 4).apply { this.text = text; setLineSpacing(0f, 1.35f) },
            Ui.linear(d(520), -2).apply { topMargin = d(12); bottomMargin = d(22) })
        if (button != null && onClick != null) addView(Ui.pill(ctx, button, onClick), Ui.linear(-2, -2))
    }

fun View.onKey(code: Int, f: () -> Boolean) = setOnKeyListener { _, c, e -> if (e.action == KeyEvent.ACTION_DOWN && c == code) f() else false }

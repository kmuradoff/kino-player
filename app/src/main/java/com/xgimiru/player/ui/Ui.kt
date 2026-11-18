package com.xgimiru.player.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Оформление Lumen Play в стиле Lumen 2.0: шрифт Inter, светлая или тёмная тема по системе (нейтральная
 * светлая: серое поле, белые плитки, тёмный текст). Размеры в dp рассчитаны на 1920x1080 при плотности 2.
 */
object Ui {
    var dark = true; private set
    /** Поле экрана, плитки, панели. */
    var BG = 0; private set
    var BG_GLOW = 0; private set
    var BG_LOW = 0; private set
    var SURFACE = 0; private set
    var SURFACE2 = 0; private set
    var PANEL = 0; private set
    var LINE = 0; private set
    /** Текст: заголовки, описания, подписи. */
    var INK = 0; private set
    var INK2 = 0; private set
    var INK3 = 0; private set
    /** Заливка фокуса и текст на ней. */
    var FOCUS = 0; private set
    var GROUND = 0; private set
    var ACCENT = 0; private set
    var RING = 0; private set
    var PROGRESS = 0; private set
    /** Лёгкая подложка выбранного (вкладка, место в списке). */
    var SOFT = 0; private set
    val CARD get() = SURFACE
    val CARD_HI get() = SURFACE2

    private var inter: Typeface? = null
    private var interMedium: Typeface? = null
    private var interSemi: Typeface? = null
    private var interBold: Typeface? = null

    fun init(ctx: Context) {
        dark = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_NO
        if (dark) palette(0xFF0E1014, 0xFF161A21, 0xFF0B0C10, 0xFF1B1E25, 0xFF242830, 0xFF1E2128, 0xFF2E333C,
            0xFFF2F4F7, 0xFFB9BFC9, 0xFF8E95A1, 0xFFF2F4F7, 0xFF0E1014, 0xFF6E9BF2, 0xFFFFFFFF, 0xFFFFFFFF, 0x1FF2F4F7)
        else palette(0xFFE9EBEF, 0xFFF4F5F8, 0xFFE1E4EA, 0xFFFFFFFF, 0xFFF6F7F9, 0xFFFFFFFF, 0xFFE3E6EC,
            0xFF12151B, 0xFF424955, 0xFF5B6270, 0xFF12151B, 0xFFFFFFFF, 0xFF2A5CC4, 0xFF12151B, 0xFF3B78E7, 0x1A12151B)
        if (inter != null) return
        val base = runCatching { Typeface.createFromAsset(ctx.assets, "fonts/InterVariable.ttf") }.getOrNull()
        inter = base?.let { Typeface.create(it, 400, false) } ?: Typeface.DEFAULT
        interMedium = base?.let { Typeface.create(it, 500, false) } ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)
        interSemi = base?.let { Typeface.create(it, 600, false) } ?: Typeface.DEFAULT_BOLD
        interBold = base?.let { Typeface.create(it, 700, false) } ?: Typeface.DEFAULT_BOLD
    }

    private fun palette(vararg c: Long) {
        val v = c.map { it.toInt() }
        BG = v[0]; BG_GLOW = v[1]; BG_LOW = v[2]; SURFACE = v[3]; SURFACE2 = v[4]; PANEL = v[5]; LINE = v[6]
        INK = v[7]; INK2 = v[8]; INK3 = v[9]; FOCUS = v[10]; GROUND = v[11]; ACCENT = v[12]; RING = v[13]; PROGRESS = v[14]; SOFT = v[15]
    }

    /** Крупные заголовки: Inter полужирный (Lumen 2.0 без засечек). */
    fun serif(): Typeface = interSemi ?: Typeface.DEFAULT_BOLD
    fun sans(): Typeface = inter ?: Typeface.DEFAULT
    fun medium(): Typeface = interMedium ?: Typeface.DEFAULT_BOLD
    fun bold(): Typeface = interBold ?: Typeface.DEFAULT_BOLD

    /** Поле экрана: свет в правом верхнем углу, как у Lumen Home. */
    fun field(): GradientDrawable = GradientDrawable(GradientDrawable.Orientation.TR_BL, intArrayOf(BG_GLOW, BG, BG_LOW))

    /** Значок Material Symbols нужного цвета. */
    fun icon(ctx: Context, res: Int, color: Int): android.graphics.drawable.Drawable =
        ctx.getDrawable(res)!!.mutate().apply { setTint(color) }

    fun dp(ctx: Context, v: Number): Int = (v.toFloat() * ctx.resources.displayMetrics.density + 0.5f).toInt()

    fun text(ctx: Context, size: Float, color: Int = INK, face: Typeface = sans(), lines: Int = 1): TextView =
        TextView(ctx).apply {
            textSize = size
            setTextColor(color)
            typeface = face
            includeFontPadding = false
            if (lines > 0) {
                maxLines = lines
                ellipsize = TextUtils.TruncateAt.END
            }
        }

    fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (stroke > 0) setStroke(stroke, strokeColor)
    }

    /** Кнопка-«пилюля»: светлая в фокусе, полупрозрачная без него. */
    fun pill(ctx: Context, label: String, onClick: () -> Unit): TextView = text(ctx, 17f, INK, medium()).apply {
        text = label
        gravity = Gravity.CENTER
        val h = dp(ctx, 14); val v = dp(ctx, 9)
        setPadding(h * 2, v, h * 2, v)
        isFocusable = true
        isClickable = true
        val r = dp(ctx, 30).toFloat()
        fun paint(f: Boolean) {
            background = rounded(if (f) FOCUS else SOFT, r)
            setTextColor(if (f) GROUND else INK)
        }
        paint(false)
        setOnFocusChangeListener { _, f -> paint(f) }
        setOnClickListener { onClick() }
    }

    fun lp(w: Int, h: Int) = ViewGroup.LayoutParams(w, h)
    fun frame(w: Int, h: Int, gravity: Int = Gravity.NO_GRAVITY) = FrameLayout.LayoutParams(w, h, gravity)
    fun linear(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

    /** Плавное увеличение в фокусе, как карточки Lumen Home. */
    fun focusScale(v: View, scale: Float = 1.06f) {
        v.setOnFocusChangeListener { x, f ->
            x.animate().scaleX(if (f) scale else 1f).scaleY(if (f) scale else 1f).translationZ(if (f) 8f else 0f)
                .setDuration(160).start()
        }
    }

    fun withAlpha(color: Int, a: Int) = Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
}

/** Текст интерфейса на языке системы: русский для ru/be/uk/kk, иначе английский. */
fun tr(ru: String, en: String): String =
    if (java.util.Locale.getDefault().language in setOf("ru", "be", "uk", "kk")) ru else en

package com.xgimiru.player.ui

import android.content.Context
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
 * Оформление Lumen Play: те же шрифты и тёплая тёмная палитра, что у Lumen Home (Prata для крупных
 * заголовков, Onest для всего остального). Размеры в dp рассчитаны на экран 1920x1080 при плотности 2
 * (проекторы и приставки), на других экранах масштабируются сами.
 */
object Ui {
    const val GROUND = 0xFF0A0908.toInt()
    const val INK = 0xFFF4EFE6.toInt()
    const val INK2 = 0xFFC9C1B4.toInt()
    const val INK3 = 0xFF8E867A.toInt()
    const val ACCENT = 0xFFF2B26B.toInt()
    const val CARD = 0xFF1C1A17.toInt()
    const val CARD_HI = 0xFF2A2622.toInt()
    const val LINE = 0x24F4EFE6

    private var prata: Typeface? = null
    private var onest: Typeface? = null
    private var onestMedium: Typeface? = null
    private var onestBold: Typeface? = null

    fun init(ctx: Context) {
        if (onest != null) return
        val am = ctx.assets
        prata = runCatching { Typeface.createFromAsset(am, "fonts/Prata-Regular.ttf") }.getOrNull()
        val base = runCatching { Typeface.createFromAsset(am, "fonts/Onest-Variable.ttf") }.getOrNull()
        onest = base?.let { Typeface.create(it, 420, false) } ?: Typeface.DEFAULT
        onestMedium = base?.let { Typeface.create(it, 560, false) } ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)
        onestBold = base?.let { Typeface.create(it, 660, false) } ?: Typeface.DEFAULT_BOLD
    }

    fun serif(): Typeface = prata ?: Typeface.SERIF
    fun sans(): Typeface = onest ?: Typeface.DEFAULT
    fun medium(): Typeface = onestMedium ?: Typeface.DEFAULT_BOLD
    fun bold(): Typeface = onestBold ?: Typeface.DEFAULT_BOLD

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
            background = rounded(if (f) INK else 0x24F4EFE6, r)
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

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
import com.xgimiru.player.settings.K
import com.xgimiru.player.settings.playerSettings

/**
 * Lumen Play look "Проводник": graphite field, a darker left list, blue focus fill, Inter. Dark by default,
 * light only when the user picks it in the settings (the player must never look like the light Lumen Home).
 * Sizes come from the 1920x1080 mock: [px] turns a mock pixel into dp (the projector runs 960x540 dp).
 */
object Ui {
    var dark = true; private set

    /** Screen field, left list, raised surfaces, lines. */
    var BG = 0; private set
    var RAIL = 0; private set
    var S1 = 0; private set
    var S2 = 0; private set
    var LINE = 0; private set
    var LINE2 = 0; private set
    /** Text: main, secondary, faint. */
    var T1 = 0; private set
    var T2 = 0; private set
    var T3 = 0; private set
    /** Focus fill and progress, the same blue in both themes. */
    const val ACC = 0xFF3B78E7.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()

    // names used by the older screens (dialogs, casting, pair code)
    val INK get() = T1
    val INK2 get() = T2
    val INK3 get() = T3
    val FOCUS get() = ACC
    val GROUND get() = WHITE
    val ACCENT get() = ACC
    val RING get() = ACC
    val PROGRESS get() = ACC
    val SURFACE get() = S1
    val SURFACE2 get() = S2
    val PANEL get() = S1
    val SOFT get() = S1
    val CARD get() = S1
    val CARD_HI get() = S2

    private var base: Typeface? = null

    fun init(ctx: Context) {
        dark = ctx.playerSettings().string(K.THEME) != "light"
        if (dark) palette(0xFF131416, 0xFF0C0D0F, 0xFF1C1D20, 0xFF27292D, 0x12FFFFFF, 0x2EFFFFFF, 0xFFF2F3F5, 0xFFAEB3BB, 0xFF80858D)
        else palette(0xFFE2E0DB, 0xFFD3CFC8, 0xFFEBE9E5, 0xFFC7C3BB, 0x14141414, 0x38141414, 0xFF16171A, 0xFF474B52, 0xFF6D7179)
        if (base == null) base = runCatching { Typeface.createFromAsset(ctx.assets, "fonts/InterVariable.ttf") }.getOrNull()
    }

    private fun palette(vararg c: Long) {
        val v = c.map { it.toInt() }
        BG = v[0]; RAIL = v[1]; S1 = v[2]; S2 = v[3]; LINE = v[4]; LINE2 = v[5]; T1 = v[6]; T2 = v[7]; T3 = v[8]
    }

    fun sans(): Typeface = base ?: Typeface.DEFAULT
    fun serif(): Typeface = sans()
    fun medium(): Typeface = sans()
    fun bold(): Typeface = sans()

    /** Screen field: one flat colour. */
    fun field(): GradientDrawable = GradientDrawable().apply { setColor(BG) }

    fun icon(ctx: Context, res: Int, color: Int): android.graphics.drawable.Drawable =
        ctx.getDrawable(res)!!.mutate().apply { setTint(color) }

    fun dp(ctx: Context, v: Number): Int = (v.toFloat() * ctx.resources.displayMetrics.density + 0.5f).toInt()

    /** A mock pixel (1920x1080 design) in device pixels. */
    fun px(ctx: Context, v: Number): Int = dp(ctx, v.toFloat() / 2f)

    /**
     * Text the way the mock sets it: [size] and [spacing] in mock pixels, Inter at [weight] with the optical
     * size the browser picks for that size (glyph widths match the mock only with it).
     */
    fun t(ctx: Context, size: Number, weight: Int = 400, color: Int = T1, lines: Int = 1, spacing: Float = 0f): TextView =
        TextView(ctx).apply {
            typeface = sans()
            textSize = size.toFloat() / 2f
            setTextColor(color)
            includeFontPadding = false
            weight(this, weight, size)
            if (spacing != 0f) letterSpacing = spacing / size.toFloat()
            if (lines > 0) { maxLines = lines; ellipsize = TextUtils.TruncateAt.END }
        }

    fun weight(v: TextView, weight: Int, size: Number) {
        v.fontVariationSettings = "'wght' $weight, 'opsz' ${size.toFloat().coerceIn(14f, 32f)}"
    }

    /** Older helper: size in sp. */
    @Suppress("UNUSED_PARAMETER")
    fun text(ctx: Context, size: Float, color: Int = INK, face: Typeface = sans(), lines: Int = 1): TextView =
        t(ctx, size * 2, 450, color, lines)

    fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (stroke > 0) setStroke(stroke, strokeColor)
    }

    /** Pill button: blue fill in focus, a soft surface without it. */
    fun pill(ctx: Context, label: String, onClick: () -> Unit): TextView = t(ctx, 23, 560, T1).apply {
        text = label
        gravity = Gravity.CENTER
        setPadding(px(ctx, 32), px(ctx, 18), px(ctx, 32), px(ctx, 18))
        isFocusable = true
        isClickable = true
        val r = px(ctx, 30).toFloat()
        fun paint(f: Boolean) {
            background = rounded(if (f) ACC else S1, r)
            setTextColor(if (f) WHITE else T1)
        }
        paint(false)
        setOnFocusChangeListener { _, f -> paint(f) }
        setOnClickListener { onClick() }
    }

    fun lp(w: Int, h: Int) = ViewGroup.LayoutParams(w, h)
    fun frame(w: Int, h: Int, gravity: Int = Gravity.NO_GRAVITY) = FrameLayout.LayoutParams(w, h, gravity)
    fun linear(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

    /** Absolute place inside a FrameLayout, in mock pixels. */
    fun at(ctx: Context, x: Number, y: Number, w: Int = -2, h: Int = -2) = FrameLayout.LayoutParams(w, h).apply {
        leftMargin = px(ctx, x); topMargin = px(ctx, y)
    }

    fun focusScale(v: View, scale: Float = 1.06f) {
        v.setOnFocusChangeListener { x, f ->
            x.animate().scaleX(if (f) scale else 1f).scaleY(if (f) scale else 1f).setDuration(200).start()
        }
    }

    fun withAlpha(color: Int, a: Int) = Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))

    /** Dialogs follow the app theme. */
    fun dialogTheme() = if (dark) com.xgimiru.player.R.style.Lumen_Dialog else com.xgimiru.player.R.style.Lumen_Dialog_Light
}

/** Interface text in the system language: Russian for ru/be/uk/kk, English otherwise. */
fun tr(ru: String, en: String): String =
    if (java.util.Locale.getDefault().language in setOf("ru", "be", "uk", "kk")) ru else en

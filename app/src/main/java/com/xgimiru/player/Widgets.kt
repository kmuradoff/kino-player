package com.xgimiru.player

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

private val ACCENT = Color.rgb(255, 122, 89)
private val ACCENT_WARM = Color.rgb(255, 181, 97)

/**
 * Полоса времени: дорожка, загруженная часть, просмотренная часть градиентом.
 * При перемотке — точка и «пузырь» со временем, куда перематываем.
 */
class ProgressView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    var duration = 0L
    var position = 0L
    var buffered = 0L
    /** Куда перематываем (null — не перематываем). */
    var scrub: Long? = null

    private val dp = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(46, 255, 255, 255) }
    private val loaded = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(80, 255, 255, 255) }
    private val played = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; setShadowLayer(8 * dp, 0f, 0f, 0x66000000) }
    private val bubble = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val bubbleText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(20, 20, 24); textSize = 15 * dp; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()
    private val path = Path()

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }   // тень у точки

    fun update(duration: Long, position: Long, buffered: Long, scrub: Long?) {
        this.duration = duration; this.position = position; this.buffered = buffered; this.scrub = scrub
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        played.shader = LinearGradient(0f, 0f, w.toFloat(), 0f, ACCENT, ACCENT_WARM, Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        val barH = if (scrub != null) 8 * dp else 6 * dp
        val left = 0f
        val right = width.toFloat()
        val cy = height - 12 * dp
        val r = barH / 2
        fun x(t: Long) = if (duration <= 0) left else left + (right - left) * (t.coerceIn(0, duration) / duration.toFloat())

        rect.set(left, cy - r, right, cy + r); c.drawRoundRect(rect, r, r, track)
        rect.set(left, cy - r, x(buffered), cy + r); c.drawRoundRect(rect, r, r, loaded)
        rect.set(left, cy - r, maxOf(x(position), left + barH), cy + r); c.drawRoundRect(rect, r, r, played)

        val target = scrub
        if (target != null && duration > 0) {
            val tx = x(target)
            c.drawCircle(tx, cy, 11 * dp, knob)
            // пузырь со временем
            val label = Time.format(target)
            val w = bubbleText.measureText(label) + 24 * dp
            val bx = tx.coerceIn(w / 2, width - w / 2)
            val top = cy - 50 * dp
            rect.set(bx - w / 2, top, bx + w / 2, top + 30 * dp)
            c.drawRoundRect(rect, 10 * dp, 10 * dp, bubble)
            path.reset()
            path.moveTo(tx - 7 * dp, top + 30 * dp); path.lineTo(tx + 7 * dp, top + 30 * dp); path.lineTo(tx, top + 37 * dp); path.close()
            c.drawPath(path, bubble)
            c.drawText(label, bx, top + 21 * dp, bubbleText)
        } else if (duration > 0) {
            c.drawCircle(x(position), cy, 7 * dp, knob)
        }
    }
}

/** Круглый значок ▶ / ❚❚, вспыхивает при переключении. */
class StateBadge @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(110, 0, 0, 0) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.argb(60, 255, 255, 255) }
    private val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val path = Path()
    private var playing = true

    fun flash(playing: Boolean) {
        this.playing = playing
        invalidate()
        animate().cancel()
        alpha = 1f; scaleX = 0.8f; scaleY = 0.8f
        animate().scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(DecelerateInterpolator()).withEndAction {
            if (playing) animate().alpha(0f).scaleX(1.15f).scaleY(1.15f).setStartDelay(350).setDuration(300).start()
        }.start()
    }

    /** На паузе значок остаётся; при воспроизведении — гаснет. */
    fun hide() { animate().cancel(); animate().alpha(0f).setDuration(200).start() }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val r = minOf(width, height) / 2f
        c.drawCircle(cx, cy, r, bg)
        ring.strokeWidth = r * 0.04f
        c.drawCircle(cx, cy, r * 0.98f, ring)
        if (playing) {
            path.reset()
            path.moveTo(cx - r * 0.22f, cy - r * 0.34f); path.lineTo(cx + r * 0.38f, cy); path.lineTo(cx - r * 0.22f, cy + r * 0.34f); path.close()
            c.drawPath(path, fg)
        } else {
            val w = r * 0.16f; val h = r * 0.34f
            c.drawRoundRect(cx - r * 0.26f, cy - h, cx - r * 0.26f + w, cy + h, w / 3, w / 3, fg)
            c.drawRoundRect(cx + r * 0.10f, cy - h, cx + r * 0.10f + w, cy + h, w / 3, w / 3, fg)
        }
    }
}

/** Кольцо загрузки: дуга, которая вращается и «дышит». */
class BufferRing @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private val dp = resources.displayMetrics.density
    private val base = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 5 * dp; color = Color.argb(40, 255, 255, 255)
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 5 * dp; strokeCap = Paint.Cap.ROUND
    }
    private val rect = RectF()
    private var t = 0f
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1400; repeatCount = ValueAnimator.INFINITE
        addUpdateListener { t = it.animatedValue as Float; invalidate() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        arc.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), ACCENT, ACCENT_WARM, Shader.TileMode.CLAMP)
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) anim.start() else anim.cancel()
    }

    override fun onDraw(c: Canvas) {
        val pad = arc.strokeWidth
        rect.set(pad, pad, width - pad, height - pad)
        c.drawOval(rect, base)
        val sweep = 40f + 200f * (0.5f - 0.5f * kotlin.math.cos(t * 2 * Math.PI).toFloat())
        c.drawArc(rect, t * 720f - 90f, sweep, false, arc)
    }
}

object Time {
    /** 1:02:03 или 12:34 */
    fun format(ms: Long): String {
        val s = (ms.coerceAtLeast(0) / 1000)
        val h = s / 3600; val m = s % 3600 / 60; val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    /** «1 ч 12 мин», «34 мин» */
    fun human(ms: Long): String {
        val min = (ms / 60000).toInt()
        return when {
            min >= 60 -> com.xgimiru.player.ui.tr("${min / 60} ч ${min % 60} мин", "${min / 60} h ${min % 60} min")
            min > 0 -> com.xgimiru.player.ui.tr("$min мин", "$min min")
            else -> com.xgimiru.player.ui.tr("меньше минуты", "under a minute")
        }
    }
}

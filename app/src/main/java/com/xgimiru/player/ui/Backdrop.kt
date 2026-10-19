package com.xgimiru.player.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import java.util.concurrent.Executors

/**
 * Фон во весь экран, как у Lumen Home: картинка выбранного, плавная смена (1,2 с) и очень медленное
 * «дыхание» (масштаб 1,02-1,10 за 24 с). Движется только сам слой (scale/translation), без перерисовки.
 * Без картинки — тёплый градиент.
 */
class Backdrop(ctx: Context) : FrameLayout(ctx) {
    private val layers = arrayOf(ImageView(ctx), ImageView(ctx))
    private var top = 0
    private var want: String? = null
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var motion: ValueAnimator? = null

    init {
        setBackgroundColor(Ui.GROUND)
        addView(View(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF2A1E14.toInt(), 0xFF0A0908.toInt(), 0xFF0A0908.toInt()))
        }, Ui.frame(-1, -1))
        for (l in layers) {
            l.scaleType = ImageView.ScaleType.CENTER_CROP
            l.alpha = 0f
            addView(l, Ui.frame(-1, -1))
        }
        // затемнение: слева под текст, снизу под ряды, сверху под панель
        addView(View(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xF50A0908.toInt(), 0xD00A0908.toInt(), 0x400A0908, 0x000A0908))
        }, Ui.frame(-1, -1))
        addView(View(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(0xFF0A0908.toInt(), 0xEB0A0908.toInt(), 0x590A0908, 0x000A0908, 0x000A0908))
        }, Ui.frame(-1, -1))
        addView(View(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xB30A0908.toInt(), 0x000A0908, 0x000A0908, 0x000A0908))
        }, Ui.frame(-1, -1))
    }

    /** Показать картинку по ссылке (null — убрать). Повторный вызов с той же ссылкой ничего не делает. */
    fun show(url: String?) {
        if (url == want) return
        want = url
        if (url == null) { swap(null); return }
        val w = (width.takeIf { it > 0 } ?: 1920)
        io.execute {
            val b = Img.get(context, url, w)
            main.post { if (want == url) swap(b) }
        }
    }

    private fun swap(b: Bitmap?) {
        val next = layers[1 - top]
        val cur = layers[top]
        if (b == null) {
            cur.animate().alpha(0f).setDuration(600).start()
            return
        }
        next.setImageBitmap(b)
        next.animate().cancel()
        next.alpha = 0f
        next.animate().alpha(1f).setDuration(1200).start()
        cur.animate().alpha(0f).setDuration(1200).start()
        top = 1 - top
        startMotion(next)
    }

    private fun startMotion(v: View) {
        motion?.cancel()
        motion = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 24_000
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                v.scaleX = 1.02f + 0.08f * f; v.scaleY = v.scaleX
                v.translationX = -width * 0.02f * f; v.translationY = -height * 0.012f * f
            }
            start()
        }
    }

    fun pause() = motion?.pause()
    fun resume() = motion?.resume()
}

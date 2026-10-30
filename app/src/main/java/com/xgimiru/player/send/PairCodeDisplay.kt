package com.xgimiru.player.send

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.xgimiru.player.ui.tr

/**
 * Код подключения на экране поверх любого приложения. С разрешением «поверх других окон»
 * (то же, что нужно DLNA для открытия плеера из фона): табличка в правом верхнем углу.
 * Без него: уведомление и повторяющийся Toast, пока код действует. Всё в главном потоке.
 */
class PairCodeDisplay(ctx: Context) {
    private val app = ctx.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var toast: Toast? = null
    private var toastUntil = 0L
    private var text = ""

    companion object {
        private const val CHANNEL = "pair"
        private const val NOTIFICATION_ID = 3
        private const val TOAST_EVERY_MS = 4_000L
    }

    fun show(code: String, client: String, ms: Long) = main.post {
        hideNow()
        text = tr("Код для «$client»: $code", "Code for \"$client\": $code")
        val overlay = Settings.canDrawOverlays(app) && runCatching { addOverlay(code, client) }.isSuccess
        if (!overlay) {
            notifyCode()
            toastUntil = System.currentTimeMillis() + ms
            main.post(toastLoop)
        }
        main.postDelayed(hideRunnable, ms)
    }

    fun hide() = main.post { hideNow() }

    /** Короткое сообщение поверх всего (подключено, ошибка). */
    fun message(text: String) = main.post { Toast.makeText(app, text, Toast.LENGTH_LONG).show() }

    private val hideRunnable = Runnable { hideNow() }

    private val toastLoop = object : Runnable {
        override fun run() {
            if (System.currentTimeMillis() >= toastUntil) return
            toast?.cancel()
            toast = Toast.makeText(app, text, Toast.LENGTH_LONG).also { it.show() }
            main.postDelayed(this, TOAST_EVERY_MS)
        }
    }

    private fun hideNow() {
        main.removeCallbacks(hideRunnable)
        main.removeCallbacks(toastLoop)
        toastUntil = 0
        toast?.cancel()
        toast = null
        view?.let { v -> runCatching { app.getSystemService(WindowManager::class.java).removeView(v) } }
        view = null
        runCatching { app.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID) }
    }

    private fun addOverlay(code: String, client: String) {
        val d = app.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val box = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(20), dp(28), dp(20))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.argb(235, 18, 18, 22))
            }
            addView(TextView(app).apply {
                text = tr("Отправить на Lumen Play", "Send to Lumen Play")
                textSize = 16f
                setTextColor(Color.argb(200, 255, 255, 255))
            })
            addView(TextView(app).apply {
                text = tr("Введите код в «$client»", "Enter the code in \"$client\"")
                textSize = 18f
                setTextColor(Color.WHITE)
                setPadding(0, dp(4), 0, dp(6))
            })
            addView(TextView(app).apply {
                text = code.toCharArray().joinToString(" ")
                textSize = 56f
                letterSpacing = 0.1f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(Color.WHITE)
            })
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(48)
            y = dp(48)
        }
        app.getSystemService(WindowManager::class.java).addView(box, lp)
        view = box
    }

    private fun notifyCode() = runCatching {
        val nm = app.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL,
                tr("Коды подключения браузера", "Browser connection codes"), NotificationManager.IMPORTANCE_HIGH))
        }
        nm.notify(NOTIFICATION_ID, Notification.Builder(app, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle(tr("Отправить на Lumen Play", "Send to Lumen Play"))
            .setContentText(text)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setTimeoutAfter(Pairing.CODE_TTL_MS)
            .build())
    }
}

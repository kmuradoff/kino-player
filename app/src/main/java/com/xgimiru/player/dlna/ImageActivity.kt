package com.xgimiru.player.dlna

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.xgimiru.player.ui.Ui
import com.xgimiru.player.ui.tr
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Фото с телефона во весь экран. «Назад» или Stop с телефона закрывают. */
class ImageActivity : Activity(), PlayerBridge.Controls {

    private lateinit var image: ImageView
    private lateinit var status: TextView
    private val main = Handler(Looper.getMainLooper())
    private var uri: String? = null
    private var generation = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        root.addView(image, Ui.frame(-1, -1))
        status = Ui.text(this, 20f, Ui.INK2, Ui.medium(), 3).apply { gravity = Gravity.CENTER }
        root.addView(status, Ui.frame(-2, -2, Gravity.CENTER))
        setContentView(root)
        load(intent)
        main.post(tick)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        load(intent)
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        generation++
        PlayerBridge.detach(this, false)
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) { finish(); return true }
        return super.onKeyDown(keyCode, event)
    }

    private val tick = object : Runnable {
        override fun run() {
            PlayerBridge.report(this@ImageActivity, uri, PlayerBridge.State.PLAYING, 0, 0)
            main.postDelayed(this, 1000)
        }
    }

    private fun load(intent: Intent) {
        val u = intent.data?.toString() ?: run { finish(); return }
        uri = u
        PlayerBridge.attach(this)
        PlayerBridge.report(this, u, PlayerBridge.State.PLAYING, 0, 0)
        val gen = ++generation
        status.text = tr("Загружаю фото…", "Loading photo…")
        status.visibility = View.VISIBLE
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        Thread {
            val bmp = runCatching { decode(u, w, h) }.getOrNull()
            main.post {
                if (gen != generation || isFinishing) return@post
                if (bmp != null) {
                    image.setImageBitmap(bmp)
                    status.visibility = View.GONE
                } else {
                    image.setImageDrawable(null)
                    status.text = tr("Не удалось открыть фото", "Could not open the photo")
                }
            }
        }.apply { isDaemon = true }.start()
    }

    /** Скачать (до 40 МБ), уменьшить под экран и повернуть по EXIF. */
    private fun decode(u: String, w: Int, h: Int): Bitmap? {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        val bytes = try {
            c.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > 40 * 1024 * 1024) return null
                }
                out.toByteArray()
            }
        } finally {
            c.disconnect()
        }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var sample = 1
        while (o.outWidth / (sample * 2) >= w && o.outHeight / (sample * 2) >= h) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val deg = runCatching {
            when (ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        }.getOrDefault(0)
        if (deg == 0) return bmp
        val m = Matrix().apply { postRotate(deg.toFloat()) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    // ── Команды с телефона ──
    override fun remotePlay() {}
    override fun remotePause() {}
    override fun remoteSeek(ms: Long) {}
    override fun remoteStop() = finish()
}

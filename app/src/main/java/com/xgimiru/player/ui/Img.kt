package com.xgimiru.player.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import com.xgimiru.player.tv.Net
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Картинки по ссылке: логотипы каналов, постеры. Память (LRU по байтам) + диск (cache/img), уменьшение
 * при декодировании до нужного размера, 3 потока. Переиспользуемый ImageView получает картинку только
 * если всё ещё ждёт ту же ссылку.
 */
object Img {
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private val mem = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private const val TAG_KEY = 0x7f0b1001

    fun load(v: ImageView, url: String?, maxPx: Int) {
        v.setTag(TAG_KEY, url)
        if (url.isNullOrBlank()) { v.setImageDrawable(null); return }
        val key = "$maxPx|$url"
        mem.get(key)?.let { v.setImageBitmap(it); return }
        v.setImageDrawable(null)
        val ctx = v.context.applicationContext
        pool.execute {
            val b = runCatching { fetch(ctx, url, maxPx) }.getOrNull()
            if (b != null) mem.put(key, b)
            main.post { if (v.getTag(TAG_KEY) == url && b != null) { v.alpha = 0f; v.setImageBitmap(b); v.animate().alpha(1f).setDuration(150).start() } }
        }
    }

    /** Блокирующая загрузка (для фона главного экрана). */
    fun get(ctx: Context, url: String, maxPx: Int): Bitmap? {
        val key = "$maxPx|$url"
        mem.get(key)?.let { return it }
        return runCatching { fetch(ctx.applicationContext, url, maxPx) }.getOrNull()?.also { mem.put(key, it) }
    }

    private fun fetch(ctx: Context, url: String, maxPx: Int): Bitmap? {
        val f = diskFile(ctx, url)
        if (!f.exists() || f.length() == 0L) {
            val tmp = File(f.path + ".part")
            if (url.startsWith("content:")) ctx.contentResolver.openInputStream(android.net.Uri.parse(url))!!.use { i -> tmp.outputStream().use { i.copyTo(it) } }
            else Net.open(url, 15_000).use { i -> tmp.outputStream().use { i.copyTo(it) } }
            tmp.renameTo(f)
        }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var s = 1
        while (o.outWidth / (s * 2) >= maxPx || o.outHeight / (s * 2) >= maxPx) s *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
    }

    private fun diskFile(ctx: Context, url: String): File {
        val dir = File(ctx.cacheDir, "img").apply { mkdirs() }
        val h = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, h)
    }

    /** Кэш картинок не растёт бесконечно: старше 30 дней и сверх 150 МБ — в корзину. */
    fun trim(ctx: Context) {
        val files = File(ctx.cacheDir, "img").listFiles()?.sortedByDescending { it.lastModified() } ?: return
        var total = 0L
        val old = System.currentTimeMillis() - 30L * 86400_000
        for (f in files) { total += f.length(); if (total > 150L * 1024 * 1024 || f.lastModified() < old) f.delete() }
    }
}

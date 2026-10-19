package com.xgimiru.player

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.media.tv.TvContract
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import java.io.File
import java.util.concurrent.Executors

/**
 * «Продолжить просмотр» лаунчера (Lumen Home, Google TV): каждая начатая серия из [History] — строка
 * WatchNextPrograms системного TV-провайдера. Обложка — кадр из самого видео ([grab]), её отдаёт [Art].
 * Всё в фоне: провайдер медленный, а плеер не должен ждать.
 */
object WatchNext {
    private const val PREFS = "watch_next"
    private val io = Executors.newSingleThreadExecutor()

    /** Обновить или добавить строку для записи истории. */
    fun publish(ctx: Context, e: History.Entry) {
        val app = ctx.applicationContext
        io.execute { runCatching { upsert(app, e) } }
    }

    /** Серия досмотрена или убрана из истории — убрать и из лаунчера. */
    fun remove(ctx: Context, key: String) {
        val app = ctx.applicationContext
        io.execute {
            runCatching {
                val id = ids(app).getLong(key, -1L)
                if (id >= 0) app.contentResolver.delete(TvContract.buildWatchNextProgramUri(id), null, null)
                ids(app).edit().remove(key).apply()
                Art.file(app, key).delete()
            }
        }
    }

    private fun ids(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun upsert(ctx: Context, e: History.Entry) {
        val open = Intent(ctx, PlayerActivity::class.java)
            .setData(Uri.parse(e.uri))
            .putExtra("title", e.title)
        val n = Name.parse(e.title)
        val v = ContentValues().apply {
            put(TvContract.WatchNextPrograms.COLUMN_TYPE,
                if (n.episode != null) TvContract.WatchNextPrograms.TYPE_TV_EPISODE else TvContract.WatchNextPrograms.TYPE_MOVIE)
            n.season?.let { put(TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER, it) }
            n.episode?.let { put(TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER, it) }
            n.year?.let { put(TvContract.WatchNextPrograms.COLUMN_RELEASE_DATE, it) }
            put(TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE, TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
            put(TvContract.WatchNextPrograms.COLUMN_TITLE, n.title)
            put(TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS, e.position.toInt())
            put(TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS, e.duration.toInt())
            put(TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS, e.updated)
            put(TvContract.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID, e.key)
            put(TvContract.WatchNextPrograms.COLUMN_INTENT_URI, open.toUri(Intent.URI_INTENT_SCHEME))
            put(TvContract.WatchNextPrograms.COLUMN_POSTER_ART_ASPECT_RATIO, TvContract.WatchNextPrograms.ASPECT_RATIO_16_9)
            if (Art.file(ctx, e.key).exists()) put(TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI, Art.uri(e.key).toString())
        }
        val cr = ctx.contentResolver
        val id = ids(ctx).getLong(e.key, -1L)
        if (id >= 0 && cr.update(TvContract.buildWatchNextProgramUri(id), v, null, null) > 0) return
        // строку могли удалить (лаунчер, «убрать из списка») — добавить заново
        val uri = cr.insert(TvContract.WatchNextPrograms.CONTENT_URI, v) ?: return
        ids(ctx).edit().putLong(e.key, ContentUris.parseId(uri)).apply()
    }

    /**
     * Кадр текущего видео в обложку: раз на запись (или если кадр старше 10 минут), с первой SurfaceView
     * внутри [video]. На защищённом видео PixelCopy вернёт ошибку — тогда обложки просто нет.
     */
    fun grab(ctx: Context, video: View, e: History.Entry, then: () -> Unit) {
        val f = Art.file(ctx, e.key)
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < 10 * 60_000) { then(); return }
        val sv = findSurface(video) ?: run { then(); return }
        if (sv.width <= 0 || sv.height <= 0 || !sv.holder.surface.isValid) { then(); return }
        val w = 640; val h = 360
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        PixelCopy.request(sv, bmp, { r ->
            if (r == PixelCopy.SUCCESS && !isBlack(bmp)) io.execute {
                runCatching {
                    f.parentFile?.mkdirs()
                    val tmp = File(f.path + ".tmp")
                    tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                    tmp.renameTo(f)
                }
                then()
            } else then()
        }, handler)
    }

    private val handler by lazy { Handler(HandlerThread("kino-grab").apply { start() }.looper) }

    private fun findSurface(v: View): SurfaceView? {
        if (v is SurfaceView && v.visibility == View.VISIBLE) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findSurface(v.getChildAt(i))?.let { return it }
        return null
    }

    /** Чёрный кадр (смена сцены, затемнение) в обложку не годится. */
    private fun isBlack(b: Bitmap): Boolean {
        var lit = 0
        for (y in 0 until b.height step 24) for (x in 0 until b.width step 24) {
            val p = b.getPixel(x, y)
            if (((p shr 16) and 0xff) + ((p shr 8) and 0xff) + (p and 0xff) > 90) lit++
        }
        return lit < 20
    }
}

/** Только чтение: обложки «Продолжить просмотр» для лаунчера, files/art/<ключ>.jpg. */
class Art : ContentProvider() {
    companion object {
        const val AUTHORITY = "com.xgimiru.player.art"
        fun uri(key: String): Uri = Uri.parse("content://$AUTHORITY/${name(key)}")
        fun file(ctx: Context, key: String) = File(File(ctx.filesDir, "art"), name(key))
        private fun name(key: String) = Integer.toHexString(key.hashCode()) + "_" + key.length + ".jpg"
    }

    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val name = uri.lastPathSegment ?: return null
        if (mode != "r" || !name.matches(Regex("[0-9a-f]+_\\d+\\.jpg"))) return null
        val f = File(File(context!!.filesDir, "art"), name)
        return if (f.exists()) ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY) else null
    }

    override fun getType(uri: Uri) = "image/jpeg"
    override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}

/**
 * Человеческое название из имени торрент-файла: «Breaking Bad S01 E01 BDRip 1080p-SOFCJ» → «Breaking Bad»,
 * сезон 1, серия 1; «Gran Turismo 2023 1080p MA WEB-DL» → «Gran Turismo», 2023. Названия из Лампы
 * («Гран Туризмо») остаются как есть.
 */
object Name {
    data class Parsed(val title: String, val season: String?, val episode: String?, val year: String?)

    private val SE = Regex("(?i)\\bS(\\d{1,2})\\s*E(\\d{1,3})\\b")
    private val SEASON = Regex("(?i)\\b(?:S(\\d{1,2})|Season\\s*(\\d{1,2}))\\b")
    private val YEAR = Regex("\\b(19\\d\\d|20\\d\\d)\\b")
    private val JUNK = Regex("(?i)\\b(2160p|1080p|1080i|720p|480p|4k|uhd|bdrip|bd-?remux|remux|blu-?ray|web-?dl|web-?rip|hdrip|dvdrip|hdtv|x264|x265|h\\s?26[45]|hevc|avc|hdr10?|dv|sdr|ddp?5\\s?1|aac|ac3|dts|ma|amzn|nf|okko|rus|eng)\\b")

    fun parse(raw: String): Parsed {
        val s = raw.replace('.', ' ').replace('_', ' ').replace(Regex("\\s+"), " ").trim()
        var cut = s.length
        var season: String? = null; var episode: String? = null; var year: String? = null
        SE.find(s)?.let { season = it.groupValues[1].trimStart('0').ifEmpty { "0" }; episode = it.groupValues[2].trimStart('0').ifEmpty { "0" }; cut = minOf(cut, it.range.first) }
        if (season == null) SEASON.find(s)?.takeIf { it.range.first > 0 }?.let {
            season = (it.groupValues[1].ifEmpty { it.groupValues[2] }).trimStart('0'); cut = minOf(cut, it.range.first) }
        YEAR.findAll(s).firstOrNull { it.range.first > 0 }?.let { year = it.value; cut = minOf(cut, it.range.first) }
        JUNK.find(s)?.takeIf { it.range.first > 0 }?.let { cut = minOf(cut, it.range.first) }
        val t = s.substring(0, cut).trim(' ', '-', '(', '[', '.').ifBlank { s }
        return Parsed(t, season, episode, year)
    }
}

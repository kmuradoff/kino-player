package com.xgimiru.player.media

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import com.xgimiru.player.Art
import com.xgimiru.player.History
import com.xgimiru.player.Name
import com.xgimiru.player.files.LocalArt
import com.xgimiru.player.net.NetShares
import com.xgimiru.player.net.Smb
import com.xgimiru.player.net.Tmdb
import java.io.File
import java.util.concurrent.Executors

/**
 * Pictures of a video: first what lies next to it (Kodi naming, [LocalArt]), then TMDB when the build has a
 * key, then the frame Lumen Play grabbed while it played. Found in the background, cached for the app run.
 */
object Arts {
    class Pics(val poster: String?, val wide: String?)

    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val cache = LruCache<String, Pics>(400)
    private val folders = LruCache<String, List<String>>(40)

    fun cached(uri: String): Pics? = cache.get(uri)

    /** [names]: the file names of the video's folder when the caller already listed it. */
    fun find(ctx: Context, uri: String, title: String, names: Collection<String>? = null, done: (Pics) -> Unit) {
        cache.get(uri)?.let { done(it); return }
        val app = ctx.applicationContext
        pool.execute {
            val p = runCatching { resolve(app, uri, title, names) }.getOrNull() ?: Pics(null, null)
            cache.put(uri, p)
            main.post { done(p) }
        }
    }

    private fun resolve(ctx: Context, uri: String, title: String, given: Collection<String>?): Pics {
        val u = Uri.parse(uri)
        val name = u.lastPathSegment ?: ""
        var poster: String? = null
        var wide: String? = null
        when (u.scheme) {
            "file" -> {
                val dir = File(u.path ?: "").parentFile
                if (dir != null) {
                    val (p, w) = LocalArt.pick(name, given ?: dir.list()?.toList().orEmpty())
                    poster = p?.let { "file://" + File(dir, it).path }
                    wide = w?.let { "file://" + File(dir, it).path }
                }
            }
            "smb" -> {
                val parent = uri.substringBeforeLast('/') + "/"
                val names = given ?: folders.get(parent) ?: runCatching {
                    Smb.list(NetShares.authorize(ctx, Uri.parse(parent)).toString()).map { it.name }
                }.getOrNull()?.also { folders.put(parent, it) }.orEmpty()
                val (p, w) = LocalArt.pick(name, names)
                poster = p?.let { parent + Uri.encode(it) }
                wide = w?.let { parent + Uri.encode(it) }
            }
        }
        if ((poster == null || wide == null) && Tmdb.enabled) {
            val n = Name.parse(title)
            Tmdb.find(ctx, n.title, n.year, if (n.episode != null) true else null)?.let { m ->
                if (poster == null) poster = m.poster("w342")
                if (wide == null) wide = (if (n.season != null && n.episode != null) Tmdb.still(ctx, m, n.season, n.episode) else null) ?: m.backdrop("w780")
            }
        }
        if (wide == null) Art.file(ctx, History.key(u)).takeIf { it.exists() }?.let { wide = "file://" + it.path }
        return Pics(poster, wide)
    }
}

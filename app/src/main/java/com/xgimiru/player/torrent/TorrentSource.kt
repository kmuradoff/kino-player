package com.xgimiru.player.torrent

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.xgimiru.player.History
import com.xgimiru.player.SubPrep
import com.xgimiru.player.TorrApi
import com.xgimiru.player.files.FileItem
import com.xgimiru.player.files.Listing
import com.xgimiru.player.files.Source
import com.xgimiru.player.settings.playerSettings

/** An opened torrent as a folder: the Files table shows it like a stick, OK plays through the built-in TorrServer. */
class TorrentSource(private val ctx: Context, override val title: String, private val base: String, private val hash: String,
                    private val files: TorrentFiles, private val line: () -> String?) : Source {
    override val id = "torrent:$hash"
    override val root = ROOT
    override val network = true
    private val main = Handler(Looper.getMainLooper())

    fun stream(f: TorrentFiles.File): String = TorrApi.streamUri(base, hash, f).toString()

    override fun list(dir: String, done: (Listing?, String?) -> Unit) {
        val langs = SubPrep.langs(ctx.playerSettings())
        val items = files.children(dir.removePrefix(ROOT)).map { e ->
            val f = e.file
            if (f == null) FileItem(e.name, ROOT + e.path, true, e.size).apply { count = e.count; inside = e.inside }
            else FileItem(e.name, stream(f), false, e.size, files.subsFor(f, langs).map(::stream))
        }
        val history = History.all(ctx).associateBy { it.key }
        val watched = History.watched(ctx).associateBy { it.key }
        val start = TorrentFiles.resume(items.map { item ->
            if (item.dir) 0L to false
            else History.key(Uri.parse(item.uri)).let { k ->
                val h = history[k]; val w = watched[k]
                if (w != null && (h == null || w.updated >= h.updated)) w.updated to true else (h?.updated ?: 0L) to (h?.finished == true)
            }
        })
        main.post { done(Listing(items, items.map { it.name }, start), null) }
    }

    override fun status() = line()

    companion object {
        const val ROOT = "torrent:/"
    }
}

package com.xgimiru.player.media

import android.content.Context
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.MetadataRetriever
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.xgimiru.player.net.NetShares
import com.xgimiru.player.net.RoutingDataSource
import com.xgimiru.player.net.Smb
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Reads what a file holds, in the background, cached for the app run. The quick pass (length, picture size,
 * HDR) feeds the folder list, the full pass (tracks through Media3) the file card. Local files and SMB only:
 * other network links show what their name says.
 */
@UnstableApi
object Probe {
    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val cache = LruCache<String, MediaFacts>(600)

    fun cached(uri: String): MediaFacts? = cache.get(uri)

    fun canRead(uri: String) = uri.startsWith("file:") || uri.startsWith("smb:") || uri.startsWith("content:")

    /** [deep]: also the tracks (languages, Dolby Vision, Atmos). [done] runs on the main thread. */
    fun get(ctx: Context, uri: String, deep: Boolean, done: (MediaFacts) -> Unit) {
        val have = cache.get(uri)
        if (have != null && (!deep || !have.quick)) { done(have); return }
        if (!canRead(uri)) return
        val app = ctx.applicationContext
        pool.execute {
            val quick = have ?: runCatching { quick(app, uri) }.getOrNull() ?: MediaFacts()
            var f = quick
            if (deep) f = runCatching { tracks(app, uri, quick) }.getOrNull() ?: quick.copy(quick = false)
            cache.put(uri, f)
            main.post { done(f) }
        }
    }

    private fun quick(ctx: Context, uri: String): MediaFacts? {
        val r = MediaMetadataRetriever()
        var smb: Smb.Reader? = null
        try {
            when {
                uri.startsWith("file:") -> r.setDataSource(Uri.parse(uri).path)
                uri.startsWith("smb:") -> {
                    val s = Smb.open(NetShares.authorize(ctx, Uri.parse(uri)).toString()).also { smb = it }
                    r.setDataSource(object : MediaDataSource() {
                        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                            if (position >= s.length) return -1
                            return s.read(position, buffer, offset, minOf(size.toLong(), s.length - position).toInt())
                        }
                        override fun getSize() = s.length
                        override fun close() {}
                    })
                }
                else -> r.setDataSource(ctx, Uri.parse(uri))
            }
            fun key(k: Int) = r.extractMetadata(k)
            val transfer = if (Build.VERSION.SDK_INT >= 30) key(MediaMetadataRetriever.METADATA_KEY_COLOR_TRANSFER)?.toIntOrNull() else null
            return MediaFacts(
                durationMs = key(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0,
                width = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0,
                height = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0,
                hdr = when (transfer) { C.COLOR_TRANSFER_ST2084 -> "HDR10"; C.COLOR_TRANSFER_HLG -> "HLG"; else -> null },
            )
        } finally {
            runCatching { r.release() }
            smb?.close()
        }
    }

    private fun tracks(ctx: Context, uri: String, base: MediaFacts): MediaFacts {
        val factory = DefaultMediaSourceFactory(RoutingDataSource.Factory(DefaultDataSource.Factory(ctx)))
        val open = if (uri.startsWith("smb:")) NetShares.authorize(ctx, Uri.parse(uri)) else Uri.parse(uri)
        val groups = MetadataRetriever.retrieveMetadata(factory, MediaItem.fromUri(open)).get(20, TimeUnit.SECONDS)
        var w = base.width; var h = base.height; var hdr = base.hdr; var dv = false; var atmos = false
        val audio = ArrayList<String>(); val subs = ArrayList<String>()
        for (i in 0 until groups.length) {
            val g = groups[i]
            for (j in 0 until g.length) {
                val f = g.getFormat(j)
                val mime = f.sampleMimeType ?: continue
                when (MimeTypes.getTrackType(mime)) {
                    C.TRACK_TYPE_VIDEO -> {
                        if (f.width > w) w = f.width
                        if (f.height > h) h = f.height
                        if (mime == MimeTypes.VIDEO_DOLBY_VISION || f.codecs?.startsWith("dv") == true) dv = true
                        when (f.colorInfo?.colorTransfer) { C.COLOR_TRANSFER_ST2084 -> hdr = "HDR10"; C.COLOR_TRANSFER_HLG -> hdr = "HLG" }
                    }
                    C.TRACK_TYPE_AUDIO -> {
                        audio += f.language ?: "und"
                        if (mime == MimeTypes.AUDIO_E_AC3_JOC || f.codecs == "ec+3") atmos = true
                    }
                    C.TRACK_TYPE_TEXT -> subs += f.language ?: "und"
                }
            }
        }
        return base.copy(width = w, height = h, hdr = hdr, dolbyVision = dv, atmos = atmos, audio = audio, subs = subs, quick = false)
    }
}

package com.xgimiru.player.files

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.xgimiru.player.R
import com.xgimiru.player.SubPrep
import com.xgimiru.player.media.Fmt
import com.xgimiru.player.net.NetShares
import com.xgimiru.player.net.ShareUri
import com.xgimiru.player.net.Smb
import com.xgimiru.player.settings.SubtitleText
import com.xgimiru.player.settings.playerSettings
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.MediaBrowser
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/** A folder entry: a sub-folder or a video. [subs]: subtitle files next to the video. */
class FileItem(val name: String, val uri: String, val dir: Boolean, val size: Long = -1, val subs: List<String> = emptyList()) {
    /** Sub-folder: how many videos it holds and the first names (-1 = not counted). */
    var count = -1
    var inside: List<String> = emptyList()
    val title get() = if (dir) name else name.substringBeforeLast('.')
    /** A .torrent file: OK opens it through the built-in TorrServer. */
    val torrent get() = !dir && Kinds.isTorrent(name)
}

/** One folder listing plus every file name in it (pictures and subtitles are looked up by name). [start]: row to open on. */
class Listing(val items: List<FileItem>, val names: List<String>, val start: Int = 0)

/** Where a folder list comes from: a USB stick or the device storage, or a network folder. */
interface Source {
    val id: String
    val title: String
    val root: String
    val network: Boolean
    /** Lists [dir], [done] runs on the main thread with the listing or an error text. */
    fun list(dir: String, done: (Listing?, String?) -> Unit)
    /** Left part of the status line: free space or the server. */
    fun status(): String?
    fun close() {}
}

object Kinds {
    val VIDEO = setOf("mkv", "mp4", "m4v", "avi", "mov", "ts", "m2ts", "mts", "webm", "wmv", "mpg", "mpeg", "vob", "flv", "3gp", "divx", "iso", "rmvb", "rm", "asf", "ogm")
    val SUBS = setOf("srt", "ass", "ssa", "vtt", "sub", "smi")
    /** Folders Android and Windows keep at the root of a stick: never shown. */
    val SYSTEM = setOf("Android", "LOST.DIR", "System Volume Information", "\$RECYCLE.BIN", "DCIM")
    fun ext(name: String) = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    fun isVideo(name: String) = ext(name) in VIDEO
    fun isTorrent(name: String) = ext(name) == "torrent"
    fun order(a: FileItem, b: FileItem) = if (a.dir != b.dir) (if (a.dir) -1 else 1) else Fmt.natural(a.name, b.name)
}

private val io = Executors.newFixedThreadPool(2)
private val main = Handler(Looper.getMainLooper())

/** A mounted volume: plain files. */
class LocalSource(private val ctx: Context, private val v: Places.Volume) : Source {
    override val id = v.id
    override val title = v.name
    override val root: String = Uri.fromFile(v.dir).toString()
    override val network = false

    override fun list(dir: String, done: (Listing?, String?) -> Unit) {
        io.execute {
            val d = File(Uri.parse(dir).path ?: "")
            val atRoot = d.path == v.dir.path
            val all = d.listFiles()?.filter { !it.name.startsWith(".") && !(atRoot && it.name in Kinds.SYSTEM) }
            val out = all?.let { files ->
                val names = files.filter { it.isFile }.map { it.name }
                val langs = SubPrep.langs(ctx.playerSettings())
                val items = files.mapNotNull { f ->
                    when {
                        f.isDirectory -> f.listFiles()?.filter { !it.name.startsWith(".") && (it.isDirectory || Kinds.isVideo(it.name) || Kinds.isTorrent(it.name)) }.orEmpty().let { inner ->
                            // the size column of a folder: its films (one level down, no walk through the whole stick)
                            FileItem(f.name, Uri.fromFile(f).toString(), true, inner.filter { it.isFile }.sumOf { it.length() }.takeIf { it > 0 } ?: -1) to inner
                        }.let { (item, inner) ->
                            item.count = inner.size
                            item.inside = inner.map { it.name.substringBeforeLast('.') }.sortedWith(Fmt::natural).take(3)
                            item
                        }.takeIf { it.count > 0 }
                        Kinds.isVideo(f.name) -> FileItem(f.name, Uri.fromFile(f).toString(), false, f.length(),
                            SubtitleText.match(f.name, names.filter { Kinds.ext(it) in Kinds.SUBS }, langs).map { Uri.fromFile(File(d, it)).toString() })
                        Kinds.isTorrent(f.name) -> FileItem(f.name, Uri.fromFile(f).toString(), false, f.length())
                        else -> null
                    }
                }.sortedWith(Kinds::order)
                Listing(items, names)
            }
            main.post { if (out != null) done(out, null) else done(null, "unreadable") }
        }
    }

    override fun status() = Places.free(v.dir)
}

/**
 * A network folder. SMB shares are listed by the SMB reader (sizes included). A server root, NFS, FTP, SFTP,
 * DLNA, and an SMB server the reader cannot talk to are listed by libVLC.
 */
class ShareSource(private val ctx: Context, override val id: String, override val title: String, override val root: String,
                  private val vlc: () -> LibVLC?) : Source {
    override val network = true
    private var browser: MediaBrowser? = null

    override fun list(dir: String, done: (Listing?, String?) -> Unit) {
        browser?.release(); browser = null
        if (dir.startsWith("smb:", true) && Regex("^smb://[^/]+/[^/]+").containsMatchIn(dir)) {
            io.execute {
                val r = runCatching { Smb.list(NetShares.authorize(ctx, Uri.parse(dir)).toString()) }
                main.post {
                    r.onSuccess { done(smbListing(dir, it), null) }.onFailure { vlcList(dir, done) }
                }
            }
        } else vlcList(dir, done)
    }

    private fun smbListing(dir: String, list: List<Smb.Entry>): Listing {
        val base = if (dir.endsWith("/")) dir else "$dir/"
        val names = list.filter { !it.dir }.map { it.name }
        val langs = SubPrep.langs(ctx.playerSettings())
        val items = list.mapNotNull { e ->
            when {
                e.dir -> FileItem(e.name, base + Uri.encode(e.name) + "/", true)
                Kinds.isVideo(e.name) -> FileItem(e.name, base + Uri.encode(e.name), false, e.size,
                    SubtitleText.match(e.name, names.filter { Kinds.ext(it) in Kinds.SUBS }, langs).map { base + Uri.encode(it) })
                else -> null
            }
        }.sortedWith(Kinds::order)
        return Listing(items, names)
    }

    private fun vlcList(dir: String, done: (Listing?, String?) -> Unit) {
        val v = vlc() ?: run { done(null, "libvlc"); return }
        val got = ArrayList<FileItem>()
        browser = MediaBrowser(v, object : MediaBrowser.EventListener {
            override fun onMediaAdded(index: Int, media: IMedia) { entry(media)?.let { got += it } }
            override fun onMediaRemoved(index: Int, media: IMedia) {}
            override fun onBrowseEnd() { done(Listing(got.sortedWith(Kinds::order), emptyList()), null) }
        }, main).also { it.browse(NetShares.authorize(ctx, Uri.parse(dir)), MediaBrowser.Flag.Interact) }
    }

    private fun entry(m: IMedia): FileItem? {
        val uri = m.uri?.toString() ?: return null
        val name = m.getMeta(IMedia.Meta.Title)?.takeIf { it.isNotBlank() } ?: Uri.parse(uri).lastPathSegment ?: uri
        if (name.startsWith(".")) return null
        if (m.type == IMedia.Type.Directory) return FileItem(name, ShareUri.strip(uri), true)
        val ext = Kinds.ext(Uri.parse(uri).lastPathSegment ?: "")
        if (ext !in Kinds.VIDEO && !(ext.isEmpty() && uri.startsWith("http"))) return null
        val subs = runCatching { m.slaves?.filter { it.type == IMedia.Slave.Type.Subtitle }?.map { ShareUri.strip(it.uri) } }.getOrNull().orEmpty()
        return FileItem(name, ShareUri.strip(uri), false, -1, subs)
    }

    override fun status() = Places.shareSub(root)

    override fun close() { browser?.release(); browser = null }

    companion object {
        fun icon(uri: String) = if (uri.startsWith("upnp", true)) R.drawable.ic_lan else R.drawable.ic_dns
    }
}

package com.xgimiru.player.files

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.os.storage.StorageManager
import com.xgimiru.player.media.Fmt
import com.xgimiru.player.net.NetShares
import com.xgimiru.player.net.ShareUri
import com.xgimiru.player.ui.tr
import java.io.File

/** Where videos live: USB sticks and the device storage by name, saved network folders. */
object Places {
    class Volume(val id: String, val name: String, val dir: File, val primary: Boolean)

    val ru get() = tr("ru", "en") == "ru"

    fun volumes(ctx: Context): List<Volume> {
        val sm = ctx.getSystemService(StorageManager::class.java)
        return sm.storageVolumes.mapNotNull { v ->
            val f = v.directory ?: return@mapNotNull null
            if (!f.canRead()) return@mapNotNull null
            val name = if (v.isPrimary) tr("Память устройства", "Device storage") else usbName(v.getDescription(ctx) ?: "")
            Volume("vol:" + f.path, name, f, v.isPrimary)
        }.sortedBy { it.primary }
    }

    /** "KINGSTON" -> "Флешка KINGSTON", a system name that already says USB stays as it is. */
    fun usbName(label: String): String {
        val l = label.trim()
        if (l.isEmpty()) return tr("Флешка", "USB stick")
        if (Regex("(?i)usb|накопител|флеш|drive|stick|disk|диск").containsMatchIn(l)) return l
        return tr("Флешка ", "USB ") + l
    }

    fun free(dir: File): String? = runCatching {
        val s = StatFs(dir.path)
        Fmt.free(s.availableBytes, s.totalBytes, ru)
    }.getOrNull()

    /** "Общая папка · 192.168.1.20". */
    fun shareSub(uri: String): String {
        val host = ShareUri.server(uri)?.substringAfter("://") ?: ""
        val kind = when (uri.substringBefore("://").lowercase()) {
            "smb" -> tr("Общая папка", "Shared folder")
            "nfs" -> "NFS"
            "ftp", "ftps" -> "FTP"
            "sftp" -> "SFTP"
            "upnp" -> tr("Медиасервер", "Media server")
            else -> tr("Папка в сети", "Network folder")
        }
        return if (host.isEmpty()) kind else "$kind · $host"
    }

    /** "Флешка KINGSTON › Фильмы" for a file, "NAS › Сериалы › Сезон 2" for a network link. */
    fun where(ctx: Context, uri: String): String? {
        val u = Uri.parse(uri)
        val sep = " \u203A "
        if (u.scheme == "file") {
            val path = u.path ?: return null
            val v = volumes(ctx).filter { path.startsWith(it.dir.path) }.maxByOrNull { it.dir.path.length } ?: return null
            val parts = path.removePrefix(v.dir.path).trim('/').split('/').dropLast(1).filter { it.isNotEmpty() }
            return (listOf(v.name) + shortPath(parts)).joinToString(sep)
        }
        val share = NetShares.saved(ctx).filter { uri.startsWith(it.uri, true) }.maxByOrNull { it.uri.length }
        val rel = if (share != null) uri.substring(share.uri.length) else ShareUri.strip(uri).substringAfter("://").substringAfter('/')
        val parts = rel.trim('/').split('/').dropLast(1).filter { it.isNotEmpty() }.map { Uri.decode(it) }
        val root = share?.name ?: ShareUri.server(uri)?.substringAfter("://") ?: return null
        return (listOf(root) + shortPath(parts)).joinToString(sep)
    }

    /** "Сериалы › Сезон 2" for Сериалы/Отчаянные домохозяйки/Сезон 2: the first folder and the one with the file. */
    fun shortPath(parts: List<String>): List<String> = if (parts.size > 2) listOf(parts.first(), parts.last()) else parts
}

/**
 * Pictures that people keep next to their films (Kodi naming): "Film-poster.jpg", "Film.jpg", "Film-fanart.jpg",
 * "S02E22-thumb.jpg", or one "poster.jpg" / "fanart.jpg" for the whole folder. Pure code, host tested.
 */
object LocalArt {
    private val IMG = listOf("jpg", "jpeg", "png", "webp")

    /** File names (as in [names], any case) of the poster and the wide picture for [video]. */
    fun pick(video: String, names: Collection<String>): Pair<String?, String?> {
        val byLower = names.associateBy { it.lowercase() }
        val base = video.substringBeforeLast('.').lowercase()
        fun first(stems: List<String>) = stems.flatMap { s -> IMG.map { "$s.$it" } }.firstNotNullOfOrNull { byLower[it] }
        val poster = first(listOf("$base-poster", base, "$base-cover")) ?: first(listOf("poster", "folder", "cover"))
        val wide = first(listOf("$base-fanart", "$base-thumb", "$base-landscape", "$base-still")) ?: first(listOf("fanart", "landscape", "backdrop"))
        return poster to wide
    }
}

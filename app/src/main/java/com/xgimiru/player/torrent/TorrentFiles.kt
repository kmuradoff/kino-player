package com.xgimiru.player.torrent

import com.xgimiru.player.media.Fmt
import com.xgimiru.player.settings.SubtitleText
import java.net.URLDecoder
import java.util.Locale

/**
 * The files of one torrent as folders: videos and the folders that hold videos, the way a folder of a stick
 * looks. The torrent's own top folder ("Sintel/") is skipped, so the list starts where the videos are.
 * Pure, host tested.
 */
class TorrentFiles(all: List<File>) {
    class File(val id: Int, val path: String, val length: Long) {
        val name get() = path.substringAfterLast('/')
    }

    /** A row: a folder ([file] null) or a video. [count]/[inside]: videos in a folder and their first names. */
    class Entry(val name: String, val path: String, val file: File?, val size: Long, val count: Int = 0, val inside: List<String> = emptyList()) {
        val dir get() = file == null
    }

    val videos: List<File> = all.filter { isVideo(it.path) }.sortedWith { a, b -> Fmt.natural(a.path, b.path) }
    private val subs: List<File> = all.filter { isSubtitle(it.path) }

    /** The folder every video sits in, "" when they start at the top. The list opens there. */
    val top: String = videos.map { it.path.substringBeforeLast('/', "") }.reduceOrNull { a, b -> common(a, b) }
        ?.let { if (it.isEmpty()) "" else "$it/" } ?: ""

    /** Folders and videos right inside [dir] ("" = [top]), folders first, natural order. */
    fun children(dir: String): List<Entry> {
        val at = if (dir.isEmpty()) top else if (dir.endsWith("/")) dir else "$dir/"
        val here = videos.filter { it.path.startsWith(at) }
        val folders = here.filter { it.path.indexOf('/', at.length) >= 0 }
            .groupBy { it.path.substring(at.length).substringBefore('/') }
            .map { (name, inner) ->
                Entry(name, "$at$name/", null, inner.sumOf { it.length }, inner.size,
                    inner.map { it.name.substringBeforeLast('.') }.take(3))
            }.sortedWith { a, b -> Fmt.natural(a.name, b.name) }
        val files = here.filter { it.path.indexOf('/', at.length) < 0 }.map { Entry(it.name, it.path, it, it.length) }
        return folders + files
    }

    /**
     * Subtitles of [video]: files next to it or in its "Subs" folder that start with its name, files elsewhere in
     * the torrent with its name, and every subtitle when the torrent has one video. Preferred languages first.
     */
    fun subsFor(video: File, langs: List<String>): List<File> {
        if (subs.isEmpty()) return emptyList()
        val dir = video.path.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        // names relative to the video's folder, the way a folder listing hands them to SubtitleText
        val rel = subs.associateBy { if (it.path.startsWith(dir)) it.path.substring(dir.length) else "../" + it.path }
        val picked = SubtitleText.match(video.name, rel.keys.toList(), langs).mapNotNull { rel[it] }
        if (videos.size != 1) return picked
        return picked + SubtitleText.match("", rel.keys.toList(), langs).mapNotNull { rel[it] }.filter { it !in picked }
    }

    companion object {
        private val VIDEO = setOf(
            "mkv", "mp4", "m4v", "avi", "ts", "m2ts", "mts", "mov", "wmv", "asf", "webm", "mpg", "mpeg",
            "vob", "flv", "divx", "3gp", "ogm", "rm", "rmvb", "m2v",
        )
        private val SUBTITLES = setOf("srt", "ass", "ssa", "vtt")

        private fun ext(path: String) = path.substringAfterLast('/').substringAfterLast('.', "").lowercase(Locale.ROOT)
        fun isVideo(path: String) = ext(path) in VIDEO
        fun isSubtitle(path: String) = ext(path) in SUBTITLES

        private fun common(a: String, b: String): String {
            val x = a.split('/'); val y = b.split('/')
            return x.zip(y).takeWhile { (p, q) -> p == q }.joinToString("/") { it.first }
        }

        /** The name a magnet link carries (dn=), null without one. */
        fun magnetName(link: String): String? {
            if (!link.startsWith("magnet:", true)) return null
            val q = link.substringAfter('?', "")
            return q.split('&').firstOrNull { it.startsWith("dn=", true) }
                ?.let { runCatching { URLDecoder.decode(it.substring(3), "UTF-8") }.getOrNull() }
                ?.trim()?.takeIf { it.isNotEmpty() }
        }

        /** Is this the content of a .torrent file (a bencoded dictionary, at most 16 MB)? */
        fun looksLikeTorrent(bytes: ByteArray) = bytes.isNotEmpty() && bytes.size <= 16 * 1024 * 1024 && bytes[0] == 'd'.code.toByte()

        /**
         * Which row to start on: the video watched last. A video watched to the end moves on to the next one.
         * [last]: per row, when it was last played (0 = never) and whether it was watched to the end.
         */
        fun resume(last: List<Pair<Long, Boolean>>): Int {
            val i = last.indices.maxByOrNull { last[it].first }?.takeIf { last[it].first > 0 } ?: return 0
            return if (last[i].second && i + 1 < last.size) i + 1 else i
        }
    }
}

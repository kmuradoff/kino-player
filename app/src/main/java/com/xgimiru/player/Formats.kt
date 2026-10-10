package com.xgimiru.player

import org.videolan.libvlc.interfaces.IMedia
import java.util.Locale

/** Человеческие названия дорожек и формата видео (данные от libVLC). */
object Formats {
    private val RU = Locale("ru")

    fun language(code: String?): String? {
        if (code.isNullOrBlank() || code == "und") return null
        val name = Locale.forLanguageTag(code.trim()).getDisplayLanguage(RU)
        if (name.isBlank() || name.equals(code, true)) return code.replaceFirstChar { it.titlecase(RU) }
        return name.replaceFirstChar { it.titlecase(RU) }
    }

    fun audio(t: IMedia.AudioTrack?, fallback: String): String {
        t ?: return clean(fallback)
        return listOfNotNull(
            language(t.language),
            t.description?.takeIf { it.isNotBlank() && !it.equals(language(t.language), true) },
            channels(t.channels),
            codec(t.codec),
        ).distinct().joinToString(" · ").ifBlank { clean(fallback) }
    }

    fun text(t: IMedia.SubtitleTrack?, fallback: String): String {
        t ?: return clean(fallback)
        return listOfNotNull(
            language(t.language),
            t.description?.takeIf { it.isNotBlank() && !it.equals(language(t.language), true) },
            codec(t.codec),
        ).distinct().joinToString(" · ").ifBlank { clean(fallback) }
    }

    /** «4K · HEVC», «1080p · AVC», «SD · MPEG-4» */
    fun video(t: IMedia.VideoTrack?): String? {
        t ?: return null
        val w = t.width; val h = t.height
        val res = when {
            w >= 3500 || h >= 2000 -> "4K"
            w >= 2400 || h >= 1400 -> "1440p"
            w >= 1800 || h >= 1000 -> "1080p"
            w >= 1200 || h >= 700 -> "720p"
            w > 0 -> "SD"
            else -> null
        }
        return listOfNotNull(res, codec(t.codec)).joinToString(" · ").ifBlank { null }
    }

    /** Название дорожки от VLC вида «Дорожка 1 - [Русский]» — без номера. */
    private fun clean(s: String) = s.replace(Regex("^(Track|Дорожка)\\s*\\d+\\s*-?\\s*"), "").trim('[', ']', ' ').ifBlank { s }

    private fun channels(n: Int) = when (n) {
        0 -> null
        1 -> "моно"
        2 -> "2.0"
        6 -> "5.1"
        8 -> "7.1"
        else -> "$n кан."
    }

    fun codec(fourcc: String?): String? = when (fourcc?.trim()?.lowercase()) {
        null, "" -> null
        "a52", "ac3", "a52b" -> "AC3"
        "eac3" -> "E-AC3"
        "mlp", "trhd" -> "TrueHD"
        "dts", "dtsh", "dtse" -> "DTS"
        "mp4a", "aac" -> "AAC"
        "mpga", "mp3", "mpg3" -> "MP3"
        "opus" -> "Opus"
        "flac" -> "FLAC"
        "vorb" -> "Vorbis"
        "hevc", "h265" -> "HEVC"
        "h264", "avc1" -> "AVC"
        "av01" -> "AV1"
        "vp90", "vp9" -> "VP9"
        "mp4v", "xvid", "divx", "dx50" -> "MPEG-4"
        "mpgv", "mp2v" -> "MPEG-2"
        "subt" -> "SRT"
        "ssa", "ass" -> "ASS"
        "webv", "wvtt" -> "VTT"
        "pgs", "pgss" -> "PGS"
        "spu", "spu ", "dvds" -> "VobSub"
        else -> null
    }

    /** Название из имени файла: «Stranger.Things.S04E05.1080p.mkv» → «Stranger Things S04E05 1080p». */
    fun titleFromPath(path: String?): String {
        val name = path?.substringAfterLast('/')?.substringBeforeLast('.') ?: return "Видео"
        return runCatching { java.net.URLDecoder.decode(name.replace("+", "%2B"), "UTF-8") }.getOrDefault(name)
            .replace('.', ' ').replace('_', ' ').replace(Regex("\\s+"), " ").trim().ifBlank { "Видео" }
    }
}

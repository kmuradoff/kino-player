package com.xgimiru.player

import android.content.Context
import android.net.Uri
import com.xgimiru.player.settings.K
import com.xgimiru.player.settings.Settings
import com.xgimiru.player.settings.SubtitleText
import java.io.File
import java.util.Locale

/**
 * Внешние субтитры перед открытием файла. Для Media3: найти файлы рядом с видео, перевести их в UTF-8
 * (Media3 читает только UTF-8) и сдвинуть время, если задержка отрицательная. VLC ищет файлы сам,
 * ему нужна только кодировка.
 */
object SubPrep {
    class Result(val subs: List<Uri>, val charset: String?)

    private val SUB_DIRS = setOf("subs", "sub", "subtitles", "subtitle", "субтитры")

    fun prepare(ctx: Context, video: Uri, explicit: List<Uri>, cfg: Settings, vlc: Boolean, delayMs: Int): Result {
        val found = if (cfg.bool(K.SUB_AUTO) && video.scheme == "file") siblings(video, langs(cfg)) else emptyList()
        val forced = cfg.string(K.SUB_ENCODING).takeIf { it != "auto" }
        if (vlc) {
            val local = (explicit + found).firstOrNull { it.scheme == "file" || it.scheme == "content" }
            val cs = forced ?: local?.let { read(ctx, it) }?.let { SubtitleText.detect(it) }
            return Result(explicit, cs)
        }
        cleanup(ctx)
        val out = (explicit + found).distinct().map { u -> runCatching { convert(ctx, u, forced, delayMs) }.getOrNull() ?: u }
        return Result(out, null)
    }

    /** Языки субтитров по порядку предпочтения: выбор в настройках, потом язык системы. */
    fun langs(cfg: Settings): List<String> {
        val sys = Locale.getDefault().language
        val first = cfg.string(K.SUB_LANG).let { if (it in setOf("file", "forced", "off", "system")) sys else it }
        return listOfNotNull(first, cfg.string(K.SUB_LANG2).takeIf { it != "none" }, sys, "en").distinct()
    }

    private fun siblings(video: Uri, langs: List<String>): List<Uri> {
        val f = File(video.path ?: return emptyList())
        val dir = f.parentFile ?: return emptyList()
        val names = ArrayList<String>()
        dir.listFiles()?.forEach { c ->
            if (c.isFile) names += c.name
            else if (c.isDirectory && c.name.lowercase(Locale.ROOT) in SUB_DIRS) c.listFiles()?.forEach { s -> if (s.isFile) names += c.name + "/" + s.name }
        }
        return SubtitleText.match(f.name, names, langs).map { Uri.fromFile(File(dir, it)) }
    }

    private fun read(ctx: Context, u: Uri): ByteArray? = runCatching {
        when (u.scheme) {
            "file" -> File(u.path!!).takeIf { it.length() < MAX }?.readBytes()
            "content" -> ctx.contentResolver.openInputStream(u)?.use { it.readBytes() }?.takeIf { it.size < MAX }
            else -> null
        }
    }.getOrNull()

    private fun convert(ctx: Context, u: Uri, forced: String?, delayMs: Int): Uri? {
        val name = u.lastPathSegment?.substringAfterLast('/') ?: return null
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (ext !in SubtitleText.EXTENSIONS) return null
        val bytes = read(ctx, u) ?: return null
        val cs = forced ?: SubtitleText.detect(bytes)
        var text = SubtitleText.decode(bytes, cs)
        if (delayMs < 0) text = SubtitleText.shift(text, ext, delayMs.toLong())
        // имя файла остаётся в конце: по нему Media3 узнаёт язык («Фильм.ru.srt»)
        val out = File(dir(ctx), Integer.toHexString((u.toString() + cs + delayMs).hashCode()) + "_" + name)
        out.writeText(text, Charsets.UTF_8)
        return Uri.fromFile(out)
    }

    private fun dir(ctx: Context) = File(ctx.cacheDir, "subs").apply { mkdirs() }

    private fun cleanup(ctx: Context) {
        val old = System.currentTimeMillis() - 24 * 3600_000L
        dir(ctx).listFiles()?.filter { it.lastModified() < old }?.forEach { it.delete() }
    }

    private const val MAX = 8L * 1024 * 1024
}

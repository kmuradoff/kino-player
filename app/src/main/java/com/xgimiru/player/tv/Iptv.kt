package com.xgimiru.player.tv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import com.xgimiru.player.ui.tr

/** Канал из плейлиста. [key] постоянный: по нему избранное и «последний канал». */
data class Channel(
    val key: String,
    val name: String,
    val url: String,
    val logo: String?,
    val group: String?,
    val tvgId: String?,
    val number: Int,
    val userAgent: String?,
    val referrer: String?,
    val catchupDays: Int,
) {
    fun toJson(): JSONObject = JSONObject().put("k", key).put("n", name).put("u", url).put("l", logo).put("g", group)
        .put("t", tvgId).put("#", number).put("ua", userAgent).put("rf", referrer).put("cd", catchupDays)

    companion object {
        fun fromJson(o: JSONObject) = Channel(o.getString("k"), o.getString("n"), o.getString("u"), o.optStringOrNull("l"),
            o.optStringOrNull("g"), o.optStringOrNull("t"), o.optInt("#"), o.optStringOrNull("ua"), o.optStringOrNull("rf"), o.optInt("cd"))
    }
}

/** Плейлист, который добавил пользователь: ссылка или файл с флешки. */
data class Playlist(val id: String, val name: String, val url: String, val epg: String?) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("url", url).put("epg", epg)

    companion object {
        fun fromJson(o: JSONObject) = Playlist(o.getString("id"), o.getString("name"), o.getString("url"), o.optStringOrNull("epg"))
    }
}

internal fun JSONObject.optStringOrNull(k: String): String? = if (isNull(k)) null else optString(k).takeIf { it.isNotBlank() }

/** Разбор M3U / M3U8 (#EXTM3U, #EXTINF с атрибутами tvg-*, #EXTGRP, #EXTVLCOPT). */
object M3u {
    class Parsed(val channels: List<Channel>, val epgUrl: String?)

    private val ATTR = Regex("([A-Za-z0-9_-]+)=\"([^\"]*)\"")

    fun parse(playlistId: String, text: String): Parsed {
        val out = ArrayList<Channel>()
        var epg: String? = null
        var attrs: Map<String, String> = emptyMap()
        var title: String? = null
        var group: String? = null
        var ua: String? = null
        var ref: String? = null
        var n = 0
        for (raw in text.lineSequence()) {
            val line = raw.trim().removePrefix("﻿")
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTM3U", true) -> {
                    val a = ATTR.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    epg = (a["url-tvg"] ?: a["x-tvg-url"])?.split(',')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                }
                line.startsWith("#EXTINF", true) -> {
                    attrs = ATTR.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    title = line.substringAfterLast("\",", "").ifBlank { line.substringAfter(',', "") }.trim()
                    group = attrs["group-title"]?.takeIf { it.isNotBlank() }
                }
                line.startsWith("#EXTGRP:", true) -> group = line.substringAfter(':').trim().ifEmpty { null }
                line.startsWith("#EXTVLCOPT:", true) -> {
                    val o = line.substringAfter(':')
                    when {
                        o.startsWith("http-user-agent=", true) -> ua = o.substringAfter('=')
                        o.startsWith("http-referrer=", true) -> ref = o.substringAfter('=')
                    }
                }
                line.startsWith("#") -> Unit
                else -> {
                    if (title != null || line.contains("://")) {
                        n++
                        val name = (title ?: attrs["tvg-name"] ?: line.substringAfterLast('/')).ifBlank { tr("Канал $n", "Channel $n") }
                        out += Channel(
                            key = playlistId + ":" + Integer.toHexString((attrs["tvg-id"] ?: name).lowercase().hashCode()) +
                                ":" + Integer.toHexString(line.hashCode()),
                            name = name,
                            url = line,
                            logo = attrs["tvg-logo"]?.takeIf { it.startsWith("http") },
                            group = group,
                            tvgId = attrs["tvg-id"]?.takeIf { it.isNotBlank() },
                            number = attrs["tvg-chno"]?.toIntOrNull() ?: n,
                            userAgent = ua ?: attrs["user-agent"],
                            referrer = ref,
                            catchupDays = attrs["catchup-days"]?.toIntOrNull() ?: 0,
                        )
                    }
                    attrs = emptyMap(); title = null; group = null; ua = null; ref = null
                }
            }
        }
        // одинаковые каналы в одном плейлисте (часто дублируют по группам) — один раз
        val seen = HashSet<String>()
        return Parsed(out.filter { seen.add(it.url) }, epg)
    }
}

/** Скачать текст или поток по http(s), с распаковкой gzip, или прочитать файл. Блокирующее. */
object Net {
    const val UA = "LumenPlay/1.0 (Android TV)"

    fun open(url: String, timeoutMs: Int = 20_000): InputStream {
        if (url.startsWith("/") || url.startsWith("file://")) return gunzip(File(url.removePrefix("file://")).inputStream())
        var u = URL(url)
        repeat(5) {
            val c = u.openConnection() as HttpURLConnection
            c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept-Encoding", "gzip")
            val code = c.responseCode
            if (code in 300..399) { u = URL(u, c.getHeaderField("Location")); c.disconnect(); return@repeat }
            if (code !in 200..299) { c.disconnect(); throw IOException(tr("сервер ответил $code", "server answered $code")) }
            return gunzip(c.inputStream)
        }
        throw IOException(tr("слишком много перенаправлений", "too many redirects"))
    }

    private fun gunzip(raw: InputStream): InputStream {
        val b = BufferedInputStream(raw, 64 * 1024)
        b.mark(2)
        val gz = b.read() == 0x1f && b.read() == 0x8b
        b.reset()
        return if (gz) BufferedInputStream(GZIPInputStream(b, 64 * 1024), 64 * 1024) else b
    }

    fun text(url: String): String = open(url).use { it.readBytes().toString(Charsets.UTF_8) }
}

/**
 * Плейлисты, их каналы (кэш в files/iptv), избранное и последний канал. Сеть — только в [refresh],
 * его зовут не из главного потока.
 */
object Iptv {
    private const val PREFS = "iptv"
    private const val REFRESH_MS = 12 * 3600_000L

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun dir(ctx: Context) = File(ctx.filesDir, "iptv").apply { mkdirs() }

    fun playlists(ctx: Context): List<Playlist> {
        val a = runCatching { JSONArray(prefs(ctx).getString("playlists", "[]")) }.getOrDefault(JSONArray())
        return (0 until a.length()).map { Playlist.fromJson(a.getJSONObject(it)) }
    }

    private fun savePlaylists(ctx: Context, list: List<Playlist>) {
        val a = JSONArray(); list.forEach { a.put(it.toJson()) }
        prefs(ctx).edit().putString("playlists", a.toString()).apply()
    }

    /** Добавить и сразу загрузить. Бросает IOException с понятным текстом, если ссылка не плейлист. */
    fun add(ctx: Context, name: String, url: String, epg: String?): Playlist {
        val p = Playlist("p" + System.currentTimeMillis().toString(36), name.ifBlank { guessName(url) }, url.trim(), epg?.trim()?.ifEmpty { null })
        load(ctx, p)
        savePlaylists(ctx, playlists(ctx) + p)
        cache = null
        return p
    }

    fun remove(ctx: Context, id: String) {
        savePlaylists(ctx, playlists(ctx).filter { it.id != id })
        File(dir(ctx), "$id.json").delete()
        Epg.forget(ctx)
        cache = null
    }

    private fun guessName(url: String) = url.substringAfter("://").substringBefore('/').removePrefix("www.").ifBlank { tr("Плейлист", "Playlist") }

    /** Скачать и разобрать плейлист, сохранить каналы. */
    private fun load(ctx: Context, p: Playlist): M3u.Parsed {
        val text = try { Net.text(p.url) } catch (e: IOException) { throw IOException(tr("не удалось скачать плейлист: ${e.message}", "could not download the playlist: ${e.message}")) }
        if (!text.trimStart('﻿', ' ', '\n', '\r').startsWith("#EXTM3U", true) && !text.contains("#EXTINF", true))
            throw IOException(tr("по ссылке не плейлист M3U", "the link is not an M3U playlist"))
        val parsed = M3u.parse(p.id, text)
        if (parsed.channels.isEmpty()) throw IOException(tr("в плейлисте нет каналов", "the playlist has no channels"))
        val a = JSONArray(); parsed.channels.forEach { a.put(it.toJson()) }
        File(dir(ctx), "${p.id}.json").writeText(JSONObject().put("at", System.currentTimeMillis()).put("epg", parsed.epgUrl).put("ch", a).toString())
        return parsed
    }

    /** Обновить плейлисты старше 12 часов. Ошибки сети не мешают: остаются старые каналы. */
    fun refresh(ctx: Context, force: Boolean = false) {
        for (p in playlists(ctx)) {
            val f = File(dir(ctx), "${p.id}.json")
            val at = runCatching { JSONObject(f.readText()).optLong("at") }.getOrDefault(0L)
            if (force || System.currentTimeMillis() - at > REFRESH_MS) runCatching { load(ctx, p) }
        }
        cache = null
    }

    @Volatile private var cache: List<Channel>? = null

    /** Все каналы всех плейлистов, по порядку, без повторов одной и той же ссылки. */
    fun channels(ctx: Context): List<Channel> {
        cache?.let { return it }
        val seen = HashSet<String>()
        val out = ArrayList<Channel>()
        for (p in playlists(ctx)) {
            val o = runCatching { JSONObject(File(dir(ctx), "${p.id}.json").readText()) }.getOrNull() ?: continue
            val a = o.getJSONArray("ch")
            for (i in 0 until a.length()) Channel.fromJson(a.getJSONObject(i)).takeIf { seen.add(it.url) }?.let { out += it }
        }
        return out.also { cache = it }
    }

    /** Ссылки на программу передач: заданные вручную, иначе из заголовков плейлистов. */
    fun epgUrls(ctx: Context): List<String> = playlists(ctx).mapNotNull { p ->
        p.epg ?: runCatching { JSONObject(File(dir(ctx), "${p.id}.json").readText()).optStringOrNull("epg") }.getOrNull()
    }.distinct()

    fun groups(ctx: Context): List<String> = channels(ctx).mapNotNull { it.group }.distinct()

    fun favorites(ctx: Context): List<String> =
        prefs(ctx).getString("fav", "")!!.split('\n').filter { it.isNotEmpty() }

    fun isFavorite(ctx: Context, key: String) = key in favorites(ctx)

    fun toggleFavorite(ctx: Context, key: String): Boolean {
        val f = favorites(ctx).toMutableList()
        val on = if (key in f) { f.remove(key); false } else { f.add(key); true }
        prefs(ctx).edit().putString("fav", f.joinToString("\n")).apply()
        return on
    }

    fun favoriteChannels(ctx: Context): List<Channel> {
        val all = channels(ctx).associateBy { it.key }
        return favorites(ctx).mapNotNull { all[it] }
    }

    var lastChannel: String? = null
        private set

    fun setLast(ctx: Context, key: String) {
        lastChannel = key
        prefs(ctx).edit().putString("last", key).apply()
    }

    fun last(ctx: Context): Channel? {
        val k = prefs(ctx).getString("last", null) ?: return null
        return channels(ctx).firstOrNull { it.key == k }
    }
}

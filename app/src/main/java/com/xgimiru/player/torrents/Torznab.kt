package com.xgimiru.player.torrents

import android.content.Context
import android.net.Uri
import android.util.Xml
import com.xgimiru.player.tv.Net
import com.xgimiru.player.ui.tr
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale

/** Результат поиска. [link] — magnet или ссылка на .torrent. */
data class Release(
    val title: String,
    val link: String,
    val size: Long,
    val seeders: Int,
    val peers: Int,
    val tracker: String?,
    val hash: String?,
    val date: String?,
) {
    /** Значки качества из названия раздачи. */
    val badges: List<String> by lazy {
        val t = title.lowercase(Locale.ROOT)
        val out = ArrayList<String>()
        when {
            Regex("2160p|\\b4k\\b|\\buhd\\b").containsMatchIn(t) -> out += "4K"
            "1080p" in t || "1080i" in t -> out += "1080p"
            "720p" in t -> out += "720p"
        }
        if (Regex("\\b(dv|dovi|dolby ?vision)\\b").containsMatchIn(t)) out += "DV"
        if (Regex("\\bhdr10\\+").containsMatchIn(t)) out += "HDR10+" else if (Regex("\\bhdr(10)?\\b").containsMatchIn(t)) out += "HDR"
        if ("remux" in t) out += "Remux"
        if (Regex("\\batmos\\b").containsMatchIn(t)) out += "Atmos"
        out
    }
}

/**
 * Поиск по Torznab: Jackett, Prowlarr и другие совместимые серверы, которые настроил сам пользователь.
 * Своих источников в Lumen Play нет. Адрес — то, что Jackett показывает как «Torznab feed», например
 * http://192.168.1.10:9117/api/v2.0/indexers/all/results/torznab/ (можно без /api на конце).
 */
object Torznab {
    private const val PREFS = "torznab"

    fun url(ctx: Context): String? = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("url", null)
    fun key(ctx: Context): String? = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("key", null)
    fun configured(ctx: Context) = url(ctx) != null

    fun save(ctx: Context, url: String?, key: String?) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("url", url?.trim()?.ifEmpty { null }?.let { normalize(it) })
            .putString("key", key?.trim()?.ifEmpty { null })
            .apply()
    }

    fun normalize(u: String): String {
        var s = u.trim()
        if (!s.contains("://")) s = "http://$s"
        s = s.removeSuffix("/")
        if (!s.endsWith("/api")) s += "/api"
        return s
    }

    /** Поиск. Блокирующий: не из главного потока. Результаты без повторов (по hash или названию), по сидам. */
    fun search(ctx: Context, query: String): List<Release> {
        val base = url(ctx) ?: throw IOException(tr("поиск не настроен", "search is not set up"))
        val q = URLEncoder.encode(query, "UTF-8")
        val k = key(ctx)?.let { "&apikey=" + URLEncoder.encode(it, "UTF-8") } ?: ""
        val text = try { Net.text("$base?t=search&q=$q$k") }
            catch (e: IOException) { throw IOException(tr("сервер поиска не ответил: ${e.message}", "the search server did not answer: ${e.message}")) }
        if (text.contains("<error", true)) {
            val msg = Regex("description=\"([^\"]*)\"").find(text)?.groupValues?.get(1)
            throw IOException(msg ?: tr("сервер поиска вернул ошибку", "the search server returned an error"))
        }
        val seen = HashSet<String>()
        return parse(text).filter { seen.add(it.hash?.lowercase() ?: it.title.lowercase()) }.sortedByDescending { it.seeders }
    }

    private fun parse(xml: String): List<Release> {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(xml.reader())
        val out = ArrayList<Release>()
        var inItem = false
        var title = ""; var link: String? = null; var magnet: String? = null; var size = 0L
        var seeders = 0; var peers = 0; var tracker: String? = null; var hash: String? = null; var date: String? = null
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (p.name) {
                    "item" -> { inItem = true; title = ""; link = null; magnet = null; size = 0; seeders = 0; peers = 0; tracker = null; hash = null; date = null }
                    "title" -> if (inItem) title = p.nextText().trim()
                    "link" -> if (inItem) link = p.nextText().trim()
                    "size" -> if (inItem) size = p.nextText().trim().toLongOrNull() ?: size
                    "pubDate" -> if (inItem) date = p.nextText().trim()
                    "jackettindexer", "prowlarrindexer" -> if (inItem) tracker = p.nextText().trim()
                    "enclosure" -> if (inItem && link == null) link = p.getAttributeValue(null, "url")
                    "torznab:attr", "attr" -> if (inItem) {
                        val v = p.getAttributeValue(null, "value") ?: ""
                        when (p.getAttributeValue(null, "name")) {
                            "seeders" -> seeders = v.toIntOrNull() ?: 0
                            "peers" -> peers = v.toIntOrNull() ?: 0
                            "infohash" -> hash = v
                            "magneturl" -> magnet = v
                            "size" -> if (size == 0L) size = v.toLongOrNull() ?: 0
                        }
                    }
                }
            } else if (ev == XmlPullParser.END_TAG && p.name == "item") {
                inItem = false
                val l = magnet ?: link
                if (l != null && title.isNotBlank()) out += Release(title, l, size, seeders, peers, tracker, hash, date)
            }
            ev = p.next()
        }
        return out
    }

    fun human(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f %s", bytes / (1L shl 30).toDouble(), tr("ГБ", "GB"))
        bytes >= 1L shl 20 -> String.format(Locale.US, "%.0f %s", bytes / (1L shl 20).toDouble(), tr("МБ", "MB"))
        else -> ""
    }

    fun open(ctx: Context, r: Release) {
        ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(r.link))
            .setClass(ctx, com.xgimiru.player.TorrentActivity::class.java))
    }
}

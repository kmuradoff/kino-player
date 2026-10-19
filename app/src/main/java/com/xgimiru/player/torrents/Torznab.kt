package com.xgimiru.player.torrents

import android.util.JsonReader
import android.util.JsonToken
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Разбор ответов источников. Два формата: Torznab (XML, его отдают Jackett и Prowlarr) и JSON API
 * Jackett (/api/v2.0/indexers/all/results, его же повторяют открытые совместимые сервисы). Сеть и
 * выбор формата: в [Sources].
 */
object Torznab {

    /** Ошибка Torznab (<error code=".." description=".."/>) или null. */
    fun error(xml: String): String? {
        if (!xml.contains("<error", true)) return null
        return Regex("description=\"([^\"]*)\"").find(xml)?.groupValues?.get(1) ?: "error"
    }

    fun parse(xml: String): List<Release> {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(StringReader(xml))
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
                            "infohash" -> hash = v.lowercase(Locale.ROOT)
                            "magneturl" -> magnet = v
                            "size" -> if (size == 0L) size = v.toLongOrNull() ?: 0
                        }
                    }
                }
            } else if (ev == XmlPullParser.END_TAG && p.name == "item") {
                inItem = false
                val l = magnet ?: link
                if (l != null && title.isNotBlank()) out += Release(title, l, size, seeders, peers, tracker,
                    hash ?: Release.hashOf(l), date, time(date))
            }
            ev = p.next()
        }
        return out
    }

    /** Результаты JSON API Jackett. smart: сервер сам понимает title/title_original/year (поле «jacred»). */
    class JsonPage(val releases: List<Release>, val smart: Boolean)

    fun parseJson(body: String): JsonPage {
        val r = JsonReader(StringReader(body)).apply { isLenient = true }
        val out = ArrayList<Release>()
        var smart = false
        var sawResults = false
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "Results" -> {
                    sawResults = true
                    if (r.peek() == JsonToken.NULL) r.nextNull() else {
                        r.beginArray()
                        while (r.hasNext()) readResult(r)?.let { out += it }
                        r.endArray()
                    }
                }
                "jacred" -> { smart = true; r.skipValue() }
                else -> r.skipValue()
            }
        }
        r.endObject()
        if (!sawResults) throw java.io.IOException("no Results")
        return JsonPage(out, smart)
    }

    private fun readResult(r: JsonReader): Release? {
        var title = ""; var tracker: String? = null; var size = 0L; var seeders = 0; var peers = 0
        var magnet: String? = null; var link: String? = null; var hash: String? = null; var date: String? = null
        var quality = 0; var hdr = false; val voices = ArrayList<String>(); val seasons = ArrayList<Int>()
        r.beginObject()
        while (r.hasNext()) {
            val name = r.nextName()
            if (r.peek() == JsonToken.NULL) { r.nextNull(); continue }
            when (name) {
                "Title" -> title = r.nextString().trim()
                "Tracker" -> tracker = r.nextString()
                "Size" -> size = runCatching { r.nextLong() }.getOrDefault(0L)
                "Seeders" -> seeders = runCatching { r.nextInt() }.getOrDefault(0)
                "Peers" -> peers = runCatching { r.nextInt() }.getOrDefault(0)
                "MagnetUri" -> magnet = r.nextString().ifBlank { null }
                "Link" -> link = r.nextString().ifBlank { null }
                "InfoHash" -> hash = r.nextString().lowercase(Locale.ROOT).ifBlank { null }
                "PublishDate" -> date = r.nextString()
                "info" -> {
                    if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); continue }
                    r.beginObject()
                    while (r.hasNext()) {
                        val k = r.nextName()
                        if (r.peek() == JsonToken.NULL) { r.nextNull(); continue }
                        when (k) {
                            "quality" -> quality = runCatching { r.nextInt() }.getOrDefault(0)
                            "videotype" -> hdr = r.nextString().equals("hdr", true)
                            "voices" -> { r.beginArray(); while (r.hasNext()) { if (r.peek() == JsonToken.STRING) voices += r.nextString() else r.skipValue() }; r.endArray() }
                            "seasons" -> { r.beginArray(); while (r.hasNext()) { if (r.peek() == JsonToken.NUMBER) seasons += r.nextInt() else r.skipValue() }; r.endArray() }
                            else -> r.skipValue()
                        }
                    }
                    r.endObject()
                }
                else -> r.skipValue()
            }
        }
        r.endObject()
        val l = magnet ?: link ?: return null
        if (title.isBlank()) return null
        return Release(title, l, size, seeders, peers, tracker, hash ?: Release.hashOf(magnet), date, time(date),
            quality, hdr, voices, seasons)
    }

    /** Дата раздачи в мс: ISO 8601 (JSON) или RFC 822 (Torznab). 0: не разобрали. */
    fun time(s: String?): Long {
        if (s.isNullOrBlank()) return 0L
        Regex("^(\\d{4})-(\\d\\d)-(\\d\\d)").find(s)?.let { m ->
            val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            c.clear(); c.set(m.groupValues[1].toInt(), m.groupValues[2].toInt() - 1, m.groupValues[3].toInt())
            return c.timeInMillis
        }
        return runCatching { SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US).parse(s)?.time ?: 0L }.getOrDefault(0L)
    }

    fun human(bytes: Long): String = humanSize(bytes)
}

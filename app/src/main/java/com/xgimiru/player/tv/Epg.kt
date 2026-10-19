package com.xgimiru.player.tv

import android.content.Context
import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/** Передача: время в мс UTC. */
data class Prog(val start: Long, val end: Long, val title: String, val desc: String?) {
    fun progress(now: Long) = if (end > start) ((now - start).toFloat() / (end - start)).coerceIn(0f, 1f) else 0f
}

/**
 * Программа передач XMLTV (обычно .xml.gz на десятки МБ). Разбор потоковый: в памяти только передачи
 * каналов из наших плейлистов и только на окно «2 часа назад … сутки вперёд». Результат в
 * files/iptv/epg.json, обновление раз в 12 часов. Каналы сопоставляются по tvg-id, иначе по имени.
 */
object Epg {
    private const val REFRESH_MS = 12 * 3600_000L
    @Volatile private var data: Map<String, List<Prog>>? = null

    private fun file(ctx: Context) = File(File(ctx.filesDir, "iptv").apply { mkdirs() }, "epg.json")

    fun forget(ctx: Context) { data = null; file(ctx).delete() }

    fun norm(s: String) = s.lowercase(Locale.ROOT).replace(Regex("\\b(hd|fhd|uhd|4k|sd|\\+\\d)\\b"), "")
        .replace(Regex("[^\\p{L}\\p{N}]"), "")

    private fun keyOf(c: Channel) = c.tvgId?.lowercase(Locale.ROOT) ?: ("n:" + norm(c.name))

    /** Передачи канала (из кэша на диске). Быстро, можно из главного потока после первой загрузки. */
    fun of(ctx: Context, c: Channel): List<Prog> {
        val d = data ?: loadCache(ctx).also { data = it }
        return d[keyOf(c)] ?: d["n:" + norm(c.name)] ?: emptyList()
    }

    fun now(ctx: Context, c: Channel, t: Long = System.currentTimeMillis()): Prog? = of(ctx, c).firstOrNull { t in it.start until it.end }
    fun next(ctx: Context, c: Channel, t: Long = System.currentTimeMillis()): Prog? = of(ctx, c).firstOrNull { it.start >= t }

    private fun loadCache(ctx: Context): Map<String, List<Prog>> {
        val o = runCatching { JSONObject(file(ctx).readText()) }.getOrNull() ?: return emptyMap()
        val m = HashMap<String, List<Prog>>()
        val ch = o.getJSONObject("ch")
        for (k in ch.keys()) {
            val a = ch.getJSONArray(k)
            m[k] = (0 until a.length()).map { i -> a.getJSONArray(i).let { Prog(it.getLong(0), it.getLong(1), it.getString(2), it.optString(3).ifEmpty { null }) } }
        }
        return m
    }

    fun stale(ctx: Context): Boolean {
        val at = runCatching { JSONObject(file(ctx).readText()).optLong("at") }.getOrDefault(0L)
        return System.currentTimeMillis() - at > REFRESH_MS
    }

    /** Скачать и разобрать все ссылки EPG. Долго (секунды-минуты): только в фоне. */
    fun refresh(ctx: Context, force: Boolean = false) {
        if (!force && !stale(ctx)) return
        val channels = Iptv.channels(ctx)
        if (channels.isEmpty()) return
        // какие id и имена нам нужны
        val wantIds = channels.mapNotNull { it.tvgId?.lowercase(Locale.ROOT) }.toHashSet()
        val wantNames = channels.map { norm(it.name) }.toHashSet()
        val from = System.currentTimeMillis() - 2 * 3600_000L
        val to = System.currentTimeMillis() + 26 * 3600_000L
        val out = HashMap<String, MutableList<Prog>>()
        for (url in Iptv.epgUrls(ctx)) runCatching { parse(url, wantIds, wantNames, from, to, out) }
        if (out.isEmpty()) return
        val ch = JSONObject()
        for ((k, list) in out) {
            val a = JSONArray()
            list.sortedBy { it.start }.distinctBy { it.start }.forEach { a.put(JSONArray().put(it.start).put(it.end).put(it.title).put(it.desc ?: "")) }
            ch.put(k, a)
        }
        val f = file(ctx)
        val tmp = File(f.path + ".tmp")
        tmp.writeText(JSONObject().put("at", System.currentTimeMillis()).put("ch", ch).toString())
        tmp.renameTo(f)
        data = null
    }

    private val TIME = ThreadLocal.withInitial { SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US) }
    private val TIME_NOZONE = ThreadLocal.withInitial { SimpleDateFormat("yyyyMMddHHmmss", Locale.US) }

    private fun time(s: String?): Long {
        if (s.isNullOrBlank()) return 0
        val t = s.trim()
        return runCatching { TIME.get()!!.parse(t)!!.time }.recoverCatching { TIME_NOZONE.get()!!.parse(t.take(14))!!.time }.getOrDefault(0)
    }

    private fun parse(url: String, wantIds: Set<String>, wantNames: Set<String>, from: Long, to: Long,
                      out: MutableMap<String, MutableList<Prog>>) {
        Net.open(url, 60_000).use { input ->
            val p = Xml.newPullParser()
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            p.setInput(input, null)
            // id канала XMLTV -> наш ключ (tvg-id или «n:имя»)
            val idMap = HashMap<String, String>()
            var chId: String? = null
            var progCh: String? = null; var start = 0L; var stop = 0L
            var title: String? = null; var desc: String? = null
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) when (p.name) {
                    "channel" -> {
                        chId = p.getAttributeValue(null, "id")
                        chId?.lowercase(Locale.ROOT)?.let { if (it in wantIds) idMap[chId!!] = it }
                    }
                    "display-name" -> if (chId != null && idMap[chId] == null) {
                        val n = norm(p.nextText())
                        if (n in wantNames) idMap[chId!!] = "n:$n"
                    }
                    "programme" -> {
                        progCh = idMap[p.getAttributeValue(null, "channel") ?: ""]
                        start = time(p.getAttributeValue(null, "start")); stop = time(p.getAttributeValue(null, "stop"))
                        title = null; desc = null
                        if (progCh == null || stop < from || start > to) progCh = null
                    }
                    "title" -> if (progCh != null && title == null) title = p.nextText().trim()
                    "desc" -> if (progCh != null && desc == null) desc = p.nextText().trim().take(400)
                } else if (ev == XmlPullParser.END_TAG) when (p.name) {
                    "channel" -> chId = null
                    "programme" -> {
                        val k = progCh
                        if (k != null && title != null) out.getOrPut(k) { ArrayList() } += Prog(start, if (stop > start) stop else start + 1800_000, title!!, desc)
                        progCh = null
                    }
                }
                ev = p.next()
            }
        }
    }
}

package com.xgimiru.player.net

import android.content.Context
import com.xgimiru.player.BuildConfig
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Доступ к хостам, которые DNS прячет. В России сам DNS TMDB (Route53) отвечает 127.0.0.1 любому
 * резолверу из России, в том числе Cloudflare и Google, а сами серверы доступны. Поэтому: обычный DNS;
 * если ответ «закольцован» (127.x, 0.0.0.0, частные сети) — Google DNS-over-HTTPS с подсетью клиента
 * из Европы (edns_client_subnet), затем Cloudflare. Соединение — прямо с сервером: свой TLS-сокет с
 * настоящим именем (SNI) и проверкой сертификата на это имя, простой HTTP/1.1 GET. Никаких прокси.
 */
object Resolve {
    private class Entry(val ip: String?, val at: Long)
    private val cache = ConcurrentHashMap<String, Entry>()
    private const val EU_SUBNET = "193.0.0.0/24"
    private const val MAX = 16 shl 20

    private fun bogus(a: InetAddress) = a.isLoopbackAddress || a.isAnyLocalAddress || a.isSiteLocalAddress || a.isLinkLocalAddress
    private fun bogus(ip: String) = runCatching { bogus(InetAddress.getByName(ip)) }.getOrDefault(true)

    /** null — обычный DNS отвечает честно, подменять адрес не нужно. */
    fun override(host: String): String? {
        cache[host]?.takeIf { System.currentTimeMillis() - it.at < 3 * 3600_000L }?.let { return it.ip }
        val sys = runCatching { InetAddress.getAllByName(host).toList() }.getOrDefault(emptyList())
        val ip = if (sys.isNotEmpty() && sys.none { bogus(it) }) null else doh(host)
        cache[host] = Entry(ip, System.currentTimeMillis())
        return ip
    }

    private fun doh(host: String): String? {
        for (u in listOf("https://dns.google/resolve?name=$host&type=A&edns_client_subnet=$EU_SUBNET",
                "https://8.8.8.8/resolve?name=$host&type=A&edns_client_subnet=$EU_SUBNET",
                "https://cloudflare-dns.com/dns-query?name=$host&type=A")) {
            val ip = runCatching {
                val c = URL(u).openConnection() as HttpURLConnection
                c.connectTimeout = 5000; c.readTimeout = 5000
                c.setRequestProperty("accept", "application/dns-json")
                val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                val a = o.optJSONArray("Answer") ?: return@runCatching null
                (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optInt("type") == 1 }
                    .map { it.optString("data") }.firstOrNull { it.isNotEmpty() && !bogus(it) }
            }.onFailure { android.util.Log.w("LumenTmdb", "DoH: $it") }.getOrNull()
            if (!ip.isNullOrEmpty()) return ip
        }
        return null
    }

    /** GET по https; если DNS прячет хост — напрямую на найденный адрес. */
    fun open(url: String, timeout: Int = 12_000): InputStream {
        val u = URL(url)
        val ip = override(u.host)
        if (ip == null) {
            val c = u.openConnection() as HttpURLConnection
            c.connectTimeout = timeout; c.readTimeout = timeout
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            return c.inputStream
        }
        return java.io.ByteArrayInputStream(direct(u, ip, timeout))
    }

    private fun direct(u: URL, ip: String, timeout: Int): ByteArray {
        val host = u.host
        val raw = Socket()
        raw.connect(java.net.InetSocketAddress(ip, if (u.port > 0) u.port else 443), timeout)
        raw.soTimeout = timeout
        // имя хоста (не IP) в createSocket: из него TLS берёт SNI
        val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, raw.port, true) as SSLSocket
        ssl.use { s ->
            s.sslParameters = s.sslParameters.apply { serverNames = listOf(SNIHostName(host)) }
            s.startHandshake()
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, s.session))
                throw javax.net.ssl.SSLPeerUnverifiedException("certificate is not for $host")
            val path = u.file.ifEmpty { "/" }
            s.outputStream.write(("GET $path HTTP/1.1\r\nHost: $host\r\nUser-Agent: LumenPlay/1.0\r\n" +
                "Accept-Encoding: identity\r\nConnection: close\r\n\r\n").toByteArray())
            s.outputStream.flush()
            val inp = java.io.BufferedInputStream(s.inputStream)
            val status = line(inp)
            val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
            var length = -1; var chunked = false
            while (true) {
                val h = line(inp)
                if (h.isEmpty()) break
                val k = h.substringBefore(':').trim().lowercase(Locale.ROOT); val v = h.substringAfter(':').trim()
                if (k == "content-length") length = v.toIntOrNull() ?: -1
                if (k == "transfer-encoding" && v.contains("chunked", true)) chunked = true
            }
            if (code !in 200..299) throw java.io.IOException("HTTP $code")
            val out = java.io.ByteArrayOutputStream()
            if (chunked) {
                while (true) {
                    val n = line(inp).substringBefore(';').trim().toInt(16)
                    if (n == 0) break
                    copy(inp, out, n); line(inp)
                }
            } else if (length >= 0) copy(inp, out, length)
            else { val b = ByteArray(16384); while (true) { val r = inp.read(b); if (r < 0) break; out.write(b, 0, r); if (out.size() > MAX) break } }
            return out.toByteArray()
        }
    }

    private fun copy(i: InputStream, o: java.io.ByteArrayOutputStream, n: Int) {
        if (n > MAX) throw java.io.IOException("too large")
        val b = ByteArray(16384); var left = n
        while (left > 0) { val r = i.read(b, 0, minOf(b.size, left)); if (r < 0) throw java.io.EOFException(); o.write(b, 0, r); left -= r }
    }

    private fun line(i: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val c = i.read()
            if (c < 0 || c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
            if (sb.length > 8192) throw java.io.IOException("header too long")
        }
        return sb.toString()
    }
}

/** Что TMDB знает о фильме или сериале. Картинки — полные ссылки. */
data class Meta(
    val id: Int, val tv: Boolean, val title: String, val original: String?, val year: String?,
    val overview: String?, val backdrop: String?, val poster: String?, val rating: Double,
) {
    fun backdrop(size: String = "w1280") = backdrop?.let { "https://image.tmdb.org/t/p/$size$it" }
    fun poster(size: String = "w342") = poster?.let { "https://image.tmdb.org/t/p/$size$it" }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("tv", tv).put("t", title).put("o", original).put("y", year)
        .put("ov", overview).put("b", backdrop).put("p", poster).put("r", rating)

    companion object {
        fun fromJson(o: JSONObject) = Meta(o.getInt("id"), o.optBoolean("tv"), o.getString("t"), o.optString("o").ifEmpty { null },
            o.optString("y").ifEmpty { null }, o.optString("ov").ifEmpty { null }, o.optString("b").ifEmpty { null },
            o.optString("p").ifEmpty { null }, o.optDouble("r", 0.0))

        fun from(r: JSONObject, tvHint: Boolean? = null): Meta? {
            val type = r.optString("media_type").ifEmpty { if (tvHint == true) "tv" else "movie" }
            if (type != "tv" && type != "movie") return null
            val tv = type == "tv"
            return Meta(r.getInt("id"), tv, r.optString(if (tv) "name" else "title"),
                r.optString(if (tv) "original_name" else "original_title").ifEmpty { null },
                r.optString(if (tv) "first_air_date" else "release_date").take(4).ifEmpty { null },
                r.optString("overview").ifEmpty { null }, r.optString("backdrop_path").takeIf { it.startsWith("/") },
                r.optString("poster_path").takeIf { it.startsWith("/") }, r.optDouble("vote_average", 0.0))
        }
    }
}

/**
 * TMDB: обложки, описания и поиск фильмов. Ключ попадает в APK только при сборке (BuildConfig.TMDB_KEY из
 * файла на машине сборки); без ключа всё молча выключено. Ответы кэшируются на диске (30 дней, промахи — сутки).
 * This product uses the TMDB API but is not endorsed or certified by TMDB.
 */
object Tmdb {
    val enabled get() = BuildConfig.TMDB_KEY.isNotEmpty()
    private val lang get() = if (Locale.getDefault().language == "ru") "ru-RU" else Locale.getDefault().toLanguageTag()

    private fun get(path: String, params: Map<String, String>, language: String = lang): JSONObject {
        val q = (params + mapOf("api_key" to BuildConfig.TMDB_KEY, "language" to language))
            .entries.joinToString("&") { it.key + "=" + URLEncoder.encode(it.value, "UTF-8") }
        return JSONObject(Resolve.open("https://api.themoviedb.org/3$path?$q").bufferedReader().use { it.readText() })
    }

    private fun cacheFile(ctx: Context, key: String) =
        File(File(ctx.cacheDir, "tmdb").apply { mkdirs() }, Integer.toHexString(key.hashCode()) + "_" + key.length)

    private fun norm(s: String) = s.lowercase(Locale.ROOT).replace('ё', 'е').replace(Regex("[^\\p{L}\\p{N}]"), "")

    /**
     * Лучшее совпадение для названия: точное совпадение названия (или оригинального), год, если известен,
     * сериал, если есть сезон/серия; при равенстве — популярность. Блокирующее: не из главного потока.
     */
    fun find(ctx: Context, title: String, year: String? = null, tv: Boolean? = null): Meta? {
        if (!enabled || title.isBlank()) return null
        val key = "find|$lang|$title|$year|$tv"
        val f = cacheFile(ctx, key)
        if (f.exists()) {
            val age = System.currentTimeMillis() - f.lastModified()
            val txt = f.readText()
            if (txt == "-" && age < 86400_000L) return null
            if (txt != "-" && age < 30L * 86400_000L) return runCatching { Meta.fromJson(JSONObject(txt)) }.getOrNull()
        }
        val m = runCatching {
            // кириллическое название ищем по-русски: так оно совпадёт с русским названием в TMDB
            val language = if (title.any { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' || it == 'Ё' }) "ru-RU" else lang
            val res = get("/search/multi", mapOf("query" to title), language).optJSONArray("results") ?: return@runCatching null
            val want = norm(title)
            (0 until res.length()).mapNotNull { Meta.from(res.getJSONObject(it)) }
                .maxByOrNull { x ->
                    var s = 0.0
                    if (norm(x.title) == want || (x.original != null && norm(x.original) == want)) s += 100
                    if (year != null && x.year == year) s += 40
                    if (tv != null && x.tv == tv) s += 25
                    if (x.backdrop != null) s += 10
                    s + minOf(x.rating, 10.0)
                }?.takeIf { norm(it.title) == want || (it.original != null && norm(it.original) == want) || year != null && it.year == year }
        }.onFailure { android.util.Log.w("LumenTmdb", "find \"$title\": $it") }.getOrNull()
        runCatching { f.writeText(m?.toJson()?.toString() ?: "-") }
        return m
    }

    /** Поиск для экрана «Торренты»: фильмы и сериалы с постером, по популярности. */
    fun search(query: String): List<Meta> {
        if (!enabled || query.isBlank()) return emptyList()
        val res = get("/search/multi", mapOf("query" to query)).optJSONArray("results") ?: return emptyList()
        return (0 until res.length()).mapNotNull { Meta.from(res.getJSONObject(it)) }.filter { it.poster != null }.take(12)
    }
}

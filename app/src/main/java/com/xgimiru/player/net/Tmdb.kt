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
 * Адрес хоста, даже если DNS провайдера его прячет (в России api.themoviedb.org отвечает 127.0.0.1).
 * Сначала обычный DNS; если ответ «закольцован» (127.x, 0.0.0.0, частные сети) или пуст — DNS-over-HTTPS
 * Cloudflare, затем Google. Ответ кэшируется на 6 часов. Никаких прокси: соединение прямо с сервером,
 * с правильным SNI и проверкой сертификата на настоящее имя.
 */
object Resolve {
    private class Entry(val ip: String?, val at: Long)
    private val cache = ConcurrentHashMap<String, Entry>()

    private fun bogus(a: InetAddress) = a.isLoopbackAddress || a.isAnyLocalAddress || a.isSiteLocalAddress || a.isLinkLocalAddress

    /** null — обычный DNS отвечает честно, подменять адрес не нужно. */
    fun override(host: String): String? {
        cache[host]?.takeIf { System.currentTimeMillis() - it.at < 6 * 3600_000L }?.let { return it.ip }
        val sys = runCatching { InetAddress.getAllByName(host).toList() }.getOrDefault(emptyList())
        val ip = if (sys.isNotEmpty() && sys.none { bogus(it) }) null else doh(host)
        cache[host] = Entry(ip, System.currentTimeMillis())
        return ip
    }

    private fun doh(host: String): String? {
        for (u in listOf("https://cloudflare-dns.com/dns-query?name=$host&type=A", "https://dns.google/resolve?name=$host&type=A")) {
            val ip = runCatching {
                val c = URL(u).openConnection() as HttpURLConnection
                c.connectTimeout = 5000; c.readTimeout = 5000
                c.setRequestProperty("accept", "application/dns-json")
                val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                val a = o.optJSONArray("Answer") ?: return@runCatching null
                (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optInt("type") == 1 }?.optString("data")
            }.getOrNull()
            if (!ip.isNullOrEmpty()) return ip
        }
        return null
    }

    /** GET по https с подменой адреса при необходимости. */
    fun open(url: String, timeout: Int = 12_000): InputStream {
        val u = URL(url)
        val ip = override(u.host)
        val c = (if (ip == null) u else URL(u.protocol, ip, u.port, u.file)).openConnection() as HttpsURLConnection
        c.connectTimeout = timeout; c.readTimeout = timeout
        if (ip != null) {
            val host = u.host
            c.setRequestProperty("Host", host)
            c.sslSocketFactory = SniFactory(HttpsURLConnection.getDefaultSSLSocketFactory(), host)
            c.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, s -> HttpsURLConnection.getDefaultHostnameVerifier().verify(host, s) }
        }
        if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
        return c.inputStream
    }

    private class SniFactory(private val base: SSLSocketFactory, private val host: String) : SSLSocketFactory() {
        override fun getDefaultCipherSuites(): Array<String> = base.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = base.supportedCipherSuites
        private fun sni(s: Socket): Socket = s.also { (it as? SSLSocket)?.let { ss -> ss.sslParameters = ss.sslParameters.apply { serverNames = listOf(SNIHostName(host)) } } }
        override fun createSocket(s: Socket, h: String, p: Int, a: Boolean) = sni(base.createSocket(s, host, p, a))
        override fun createSocket(h: String, p: Int) = sni(base.createSocket(h, p))
        override fun createSocket(h: String, p: Int, l: InetAddress, lp: Int) = sni(base.createSocket(h, p, l, lp))
        override fun createSocket(h: InetAddress, p: Int) = sni(base.createSocket(h, p))
        override fun createSocket(h: InetAddress, p: Int, l: InetAddress, lp: Int) = sni(base.createSocket(h, p, l, lp))
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

    private fun get(path: String, params: Map<String, String>): JSONObject {
        val q = (params + mapOf("api_key" to BuildConfig.TMDB_KEY, "language" to lang))
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
            val res = get("/search/multi", mapOf("query" to title)).optJSONArray("results") ?: return@runCatching null
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
        }.getOrNull()
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

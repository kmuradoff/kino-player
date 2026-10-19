package com.xgimiru.player.torrents

import android.content.Context
import android.net.Uri
import com.xgimiru.player.tv.Net
import com.xgimiru.player.ui.tr
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import javax.net.ssl.SSLException

/** Как говорить с источником. AUTO: пользователь доверил выбор нам: определяем при проверке или первом поиске. */
enum class Api { AUTO, JSON, TORZNAB }

/**
 * Источник поиска, который добавил пользователь: свой Jackett, Prowlarr или совместимый сервис.
 * [type]: что выбрал пользователь, [api] и [endpoint]: что сработало на деле (для AUTO их находит проверка).
 */
data class Source(
    val id: String = UUID.randomUUID().toString().take(8),
    val name: String = "",
    val url: String = "",
    val key: String? = null,
    val type: Api = Api.AUTO,
    val api: Api? = null,
    val endpoint: String? = null,
    val ok: Boolean? = null,       // null: ещё не проверяли
    val status: String? = null,    // последний итог проверки или поиска
) {
    val label: String get() = name.ifBlank { runCatching { Uri.parse(withScheme(url)).host }.getOrNull() ?: url }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("url", url).put("key", key)
        .put("type", type.name).put("api", api?.name).put("endpoint", endpoint).put("ok", ok).put("status", status)

    companion object {
        fun fromJson(o: JSONObject) = Source(o.getString("id"), o.optString("name"), o.optString("url"),
            o.optString("key").ifEmpty { null }, runCatching { Api.valueOf(o.optString("type")) }.getOrDefault(Api.AUTO),
            o.optString("api").ifEmpty { null }?.let { runCatching { Api.valueOf(it) }.getOrNull() },
            o.optString("endpoint").ifEmpty { null }, if (o.has("ok") && !o.isNull("ok")) o.optBoolean("ok") else null,
            o.optString("status").ifEmpty { null })

        /** «jac.example» → https://, «192.168.1.10:9117» и localhost → http://. */
        fun withScheme(u: String): String {
            val s = u.trim()
            if (s.contains("://")) return s
            val host = s.substringBefore('/').substringBefore(':')
            val local = host == "localhost" || host.endsWith(".local") || host.endsWith(".lan") ||
                Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(host) || s.substringBefore('/').contains(':')
            return (if (local) "http://" else "https://") + s
        }
    }
}

/** Подсказки поиска для серверов, которые их понимают (поиск по названию, оригиналу, году, «сериал или нет»). */
class Hints(val title: String?, val original: String?, val year: String?, val serial: Boolean?)

/**
 * Источники поиска торрентов. Своих в Lumen Play нет: только то, что добавил пользователь. Поиск идёт
 * по всем источникам параллельно (внутри одного источника запросы по очереди, чтобы не упереться
 * в ограничение частоты), результаты сливаются без повторов по info-hash.
 */
object Sources {
    private const val PREFS = "torrent_sources"
    const val SAMPLE = "The Matrix"
    private val pool = Executors.newFixedThreadPool(4) { r -> Thread(r, "lumen-sources").apply { isDaemon = true } }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun list(ctx: Context): List<Source> {
        migrate(ctx)
        val a = runCatching { JSONArray(prefs(ctx).getString("list", "[]")) }.getOrDefault(JSONArray())
        return (0 until a.length()).mapNotNull { runCatching { Source.fromJson(a.getJSONObject(it)) }.getOrNull() }
    }

    fun configured(ctx: Context) = list(ctx).isNotEmpty()

    private fun saveAll(ctx: Context, l: List<Source>) {
        val a = JSONArray(); l.forEach { a.put(it.toJson()) }
        prefs(ctx).edit().putString("list", a.toString()).apply()
    }

    @Synchronized
    fun put(ctx: Context, s: Source) {
        val l = list(ctx).toMutableList()
        val i = l.indexOfFirst { it.id == s.id }
        if (i >= 0) l[i] = s else l += s
        saveAll(ctx, l)
    }

    @Synchronized
    fun remove(ctx: Context, id: String) = saveAll(ctx, list(ctx).filter { it.id != id })

    /** Обновить у сохранённого источника только итог (найденный формат и статус), не трогая остальное. */
    @Synchronized
    private fun update(ctx: Context, id: String, f: (Source) -> Source) {
        val l = list(ctx)
        if (l.none { it.id == id }) return
        saveAll(ctx, l.map { if (it.id == id) f(it) else it })
    }

    /** Адрес Torznab из прежних версий (один источник) становится первым источником списка. */
    private fun migrate(ctx: Context) {
        val old = ctx.getSharedPreferences("torznab", Context.MODE_PRIVATE)
        val url = old.getString("url", null) ?: return
        if (prefs(ctx).contains("list")) return
        // прежняя версия дописывала /api к любому адресу; для голого адреса это было ошибкой
        val bare = url.removeSuffix("/api")
        val path = runCatching { Uri.parse(bare).path }.getOrNull().orEmpty().trim('/')
        val s = if (path.isEmpty()) Source(url = bare, key = old.getString("key", null))
            else Source(url = url, key = old.getString("key", null), type = Api.TORZNAB, api = Api.TORZNAB, endpoint = url)
        saveAll(ctx, listOf(s))
        old.edit().remove("url").remove("key").apply()
    }

    // ── Сеть ──
    private class Reply(val code: Int, val body: String)

    private fun fetch(url: String, timeout: Int): Reply {
        var u = URL(url)
        repeat(5) {
            val c = u.openConnection() as HttpURLConnection
            try {
                c.connectTimeout = minOf(timeout, 10_000); c.readTimeout = timeout
                c.instanceFollowRedirects = false
                c.setRequestProperty("User-Agent", Net.UA)
                c.setRequestProperty("Accept-Encoding", "gzip")
                c.setRequestProperty("Accept", "application/json, application/xml, text/xml, */*")
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location") ?: throw IOException(tr("сервер ответил $code", "server answered $code"))
                    u = URL(u, loc); return@repeat
                }
                val stream = (if (code in 200..299) c.inputStream else c.errorStream) ?: return Reply(code, "")
                val gz = c.contentEncoding?.contains("gzip", true) == true
                return Reply(code, read(if (gz) GZIPInputStream(stream) else stream))
            } finally { c.disconnect() }
        }
        throw IOException(tr("слишком много перенаправлений", "too many redirects"))
    }

    private fun read(i: InputStream): String = i.use {
        val out = java.io.ByteArrayOutputStream()
        val b = ByteArray(32 * 1024)
        while (true) {
            val n = it.read(b); if (n < 0) break
            out.write(b, 0, n)
            if (out.size() > 24 shl 20) throw IOException(tr("слишком большой ответ", "the answer is too large"))
        }
        out.toString("UTF-8")
    }

    /** Понятный текст ошибки сети. */
    fun explain(e: Throwable): String = when (e) {
        is UnknownHostException -> tr("адрес не найден, проверьте написание", "address not found, check the spelling")
        is SocketTimeoutException -> tr("сервер не ответил вовремя", "the server did not answer in time")
        is ConnectException -> tr("сервер не принимает соединение", "the server refuses the connection")
        is SSLException -> tr("не удалось защищённое соединение (https)", "secure connection (https) failed")
        is SearchError -> e.message ?: ""
        else -> e.message ?: e.toString()
    }

    /** Ошибка, которую можно показать как есть. final: дальше пробовать бессмысленно (ключ, частота). */
    open class SearchError(msg: String, val final: Boolean = false) : IOException(msg)

    /** По адресу нет поиска (404, сайт вместо API): пробуем следующий адрес. */
    class NotHere(msg: String) : SearchError(msg)

    private fun httpError(code: Int): SearchError = when (code) {
        401, 403 -> SearchError(tr("доступ закрыт ($code): проверьте ключ API", "access denied ($code): check the API key"), true)
        429 -> SearchError(tr("сервер просит подождать: слишком много запросов", "the server asks to wait: too many requests"), true)
        404 -> NotHere(tr("по этому адресу нет поиска (404)", "no search at this address (404)"))
        in 500..599 -> SearchError(tr("ошибка на сервере ($code)", "server error ($code)"))
        else -> SearchError(tr("сервер ответил $code", "server answered $code"))
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ── Определение формата ──
    /** Куда стучаться: пары (формат, адрес) в порядке проверки. Голый адрес: сначала JSON API, потом Torznab. */
    fun candidates(input: String, type: Api): List<Pair<Api, String>> {
        val full = Source.withScheme(input)
        val u = Uri.parse(full)
        val origin = "${u.scheme}://${u.encodedAuthority}"
        val path = (u.encodedPath ?: "").trimEnd('/')
        val out = ArrayList<Pair<Api, String>>()
        val low = path.lowercase()
        when {
            "torznab" in low -> out += Api.TORZNAB to origin + (if (low.endsWith("/api")) path else "$path/api")
            Regex("/api/v2\\.0/indexers/[^/]+/results$").containsMatchIn(low) -> out += Api.JSON to origin + path
            low.endsWith("/api") -> out += Api.TORZNAB to origin + path
            else -> {
                out += Api.JSON to "$origin$path/api/v2.0/indexers/all/results"
                out += Api.TORZNAB to "$origin$path/api/v2.0/indexers/all/results/torznab/api"
                out += Api.TORZNAB to "$origin$path/api"
            }
        }
        // выбран определённый формат, а адрес похож на другой: всё равно пробуем выбранный по стандартным путям
        if (type != Api.AUTO && out.none { it.first == type }) {
            out.clear()
            out += type to (if (type == Api.JSON) "$origin$path/api/v2.0/indexers/all/results" else "$origin$path/api/v2.0/indexers/all/results/torznab/api")
            if (type == Api.TORZNAB) out += Api.TORZNAB to "$origin$path/api"
        }
        return out.filter { type == Api.AUTO || it.first == type }
    }

    /** Ключ API из вставленной целиком ссылки (…?apikey=…), если поле ключа пустое. */
    fun keyFromUrl(input: String): String? = runCatching { Uri.parse(Source.withScheme(input)).getQueryParameter("apikey") }.getOrNull()?.ifBlank { null }

    private fun keyParam(key: String?) = key?.trim()?.takeIf { it.isNotEmpty() }?.let { "apikey=" + enc(it) + "&" } ?: ""

    private fun jsonUrl(endpoint: String, key: String?, q: String, h: Hints?): String {
        val sb = StringBuilder(endpoint).append('?').append(keyParam(key)).append("Query=").append(enc(q))
        if (h != null) {
            h.title?.let { sb.append("&title=").append(enc(it)) }
            h.original?.let { sb.append("&title_original=").append(enc(it)) }
            h.year?.let { sb.append("&year=").append(enc(it)) }
            h.serial?.let { sb.append("&is_serial=").append(if (it) "2" else "1") }
        }
        return sb.toString()
    }

    private fun torznabUrl(endpoint: String, key: String?, q: String) = "$endpoint?${keyParam(key)}t=search&q=${enc(q)}"

    /** Один запрос к найденному адресу. 429: одна повторная попытка через 2 с. */
    private fun query(api: Api, endpoint: String, key: String?, q: String, h: Hints?, timeout: Int): Torznab.JsonPage {
        val url = if (api == Api.JSON) jsonUrl(endpoint, key, q, h) else torznabUrl(endpoint, key, q)
        var r = fetch(url, timeout)
        if (r.code == 429) { Thread.sleep(2000); r = fetch(url, timeout) }
        if (r.code !in 200..299) throw httpError(r.code)
        val body = r.body.trimStart()
        if (api == Api.JSON) {
            if (!body.startsWith("{")) throw notApi(body)
            return runCatching { Torznab.parseJson(body) }.getOrElse { throw SearchError(tr("ответ не похож на Jackett API", "the answer does not look like the Jackett API")) }
        }
        if (!body.startsWith("<")) throw notApi(body)
        Torznab.error(body)?.let { throw SearchError(tr("сервер ответил: $it", "the server said: $it"), true) }
        if (!body.contains("<rss", true) && !body.contains("<caps", true)) throw notApi(body)
        return Torznab.JsonPage(Torznab.parse(body), false)
    }

    private fun notApi(body: String) = NotHere(
        if (body.startsWith("<!doctype", true) || body.startsWith("<html", true)) tr("по адресу открывается сайт, а не поиск", "the address opens a website, not a search API")
        else tr("ответ не похож на поиск торрентов", "the answer does not look like a torrent search"))

    class Found(val api: Api, val endpoint: String, val count: Int)

    /**
     * Проверка источника: перебираем адреса-кандидаты (для голого https-адреса ещё и http), ищем [SAMPLE].
     * Возвращает рабочий формат и число раздач; иначе бросает SearchError с самой понятной причиной.
     */
    fun detect(s: Source, timeout: Int = 25_000): Found {
        val key = s.key ?: keyFromUrl(s.url)
        val inputs = listOf(s.url) + (if (!s.url.contains("://") && Source.withScheme(s.url).startsWith("https")) listOf("http://" + s.url.trim()) else emptyList())
        val errs = ArrayList<Throwable>()
        for (input in inputs) for ((api, endpoint) in candidates(input, s.type)) {
            try {
                val p = query(api, endpoint, key, SAMPLE, null, timeout)
                return Found(api, endpoint, p.releases.size)
            } catch (e: InterruptedException) { throw e
            } catch (e: Throwable) {
                if (e is SearchError && e.final) throw e
                errs += e
                if (e is UnknownHostException) break   // тот же хост дальше тоже не найдётся
            }
        }
        // сервер ответил, но не так (5xx, странный JSON): это и показываем; «здесь нет поиска»: общий совет
        errs.firstOrNull { it is SearchError && it !is NotHere }?.let { throw SearchError(explain(it)) }
        if (errs.any { it is NotHere } || errs.isEmpty()) throw SearchError(when (s.type) {
            Api.JSON -> tr("по этому адресу нет JSON API Jackett. Проверьте адрес или выберите «Автоматически»", "no Jackett JSON API at this address. Check it or choose Automatic")
            Api.TORZNAB -> tr("по этому адресу нет Torznab. Проверьте адрес или выберите «Автоматически»", "no Torznab at this address. Check it or choose Automatic")
            Api.AUTO -> tr("по этому адресу нет ни JSON API Jackett, ни Torznab. Проверьте адрес", "neither the Jackett JSON API nor Torznab answers at this address. Check it")
        })
        throw SearchError(explain(errs.first()))
    }

    /** Проверить и запомнить итог у сохранённого источника. */
    fun test(ctx: Context, s: Source): Source {
        val res = runCatching { detect(s) }
        val out = res.fold(
            { f -> s.copy(api = f.api, endpoint = f.endpoint, ok = true, status = tr("работает: ${f.count} раздач по запросу «$SAMPLE»", "works: ${f.count} releases for “$SAMPLE”")) },
            { e -> s.copy(ok = false, status = explain(e)) })
        update(ctx, s.id) { it.copy(api = out.api, endpoint = out.endpoint, ok = out.ok, status = out.status) }
        return out
    }

    class Page(val releases: List<Release>, val smart: Boolean)

    /** Поиск в одном источнике. Формат ещё не известен: сначала определяем и запоминаем. */
    fun search(ctx: Context, s: Source, q: String, h: Hints?): Page {
        var api = s.api; var endpoint = s.endpoint
        if (api == null || endpoint == null) {
            val f = detect(s)
            api = f.api; endpoint = f.endpoint
            update(ctx, s.id) { it.copy(api = f.api, endpoint = f.endpoint, ok = true, status = null) }
        }
        val p = query(api, endpoint, s.key ?: keyFromUrl(s.url), q, if (api == Api.JSON) h else null, 40_000)
        val label = s.label
        return Page(p.releases.map { if (it.tracker.isNullOrBlank()) it.copy(tracker = label) else it }, p.smart)
    }

    class Outcome(val releases: List<Release>, val errors: List<String>, val sources: Int)

    /**
     * Поиск по всем источникам сразу. queries: по очереди внутри источника; сервер, который понимает
     * подсказки (smart), после первого запроса уже нашёл всё, остальные запросы ему не шлём.
     * Блокирующий: не из главного потока.
     */
    fun searchAll(ctx: Context, queries: List<String>, h: Hints?): Outcome {
        val app = ctx.applicationContext
        val srcs = list(app)
        val jobs = srcs.map { s ->
            pool.submit<Pair<List<Release>, String?>> {
                val got = ArrayList<Release>()
                var err: String? = null
                for ((i, q) in queries.withIndex()) {
                    try {
                        val p = search(app, s, q, h)
                        got += p.releases
                        if (p.smart && i == 0) break
                    } catch (e: InterruptedException) { break
                    } catch (e: Throwable) {
                        err = "${s.label}: ${explain(e)}"
                        if (e is SearchError && e.final) break
                    }
                }
                got to (if (got.isEmpty()) err else null)
            }
        }
        val merged = LinkedHashMap<String, Release>()
        val errors = ArrayList<String>()
        for (j in jobs) {
            val (list, err) = runCatching { j.get(90, TimeUnit.SECONDS) }.getOrElse { j.cancel(true); emptyList<Release>() to null }
            err?.let { errors += it }
            for (r in list) {
                val k = r.key
                val old = merged[k]
                merged[k] = if (old == null) r else mergeTwo(old, r)
            }
        }
        return Outcome(merged.values.toList(), errors, srcs.size)
    }

    /** Одна раздача из двух источников: больше сидов, все трекеры, magnet важнее ссылки на файл. */
    private fun mergeTwo(a: Release, b: Release): Release {
        val main = if (b.seeders > a.seeders) b else a
        val other = if (main === a) b else a
        val trackers = (listOfNotNull(main.tracker) + listOfNotNull(other.tracker)).flatMap { it.split(',') }
            .map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.joinToString(", ")
        val link = if (main.link.startsWith("magnet:", true)) main.link else if (other.link.startsWith("magnet:", true)) other.link else main.link
        return main.copy(tracker = trackers.ifEmpty { null }, link = link,
            quality = if (main.quality > 0) main.quality else other.quality,
            voices = if (main.voices.isNotEmpty()) main.voices else other.voices,
            seasons = if (main.seasons.isNotEmpty()) main.seasons else other.seasons,
            time = maxOf(main.time, other.time))
    }
}

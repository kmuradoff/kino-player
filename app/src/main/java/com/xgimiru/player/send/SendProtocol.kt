package com.xgimiru.player.send

import com.xgimiru.player.StreamRequest
import org.json.JSONException
import org.json.JSONObject
import java.util.Locale

/**
 * «Отправить на Lumen Play»: разбор запросов от расширения браузера (docs/send-protocol.md).
 * Здесь только чистая логика без Android: что пришло в теле запроса, годится ли ссылка,
 * откуда пришёл запрос. Сервер и запуск плеера живут в [SendServer].
 *
 * Принимаются только адреса http и https. Заголовки потока проходят тот же фильтр, что и
 * заголовки из интента ([StreamRequest.headers]).
 */
object SendProtocol {
    const val PORT = 8765
    const val SERVICE_TYPE = "_lumenplay._tcp"

    const val MAX_BODY = 64 * 1024
    const val MAX_URL = 8192
    const val MAX_HEADERS = 32
    const val MAX_HEADER_VALUE = 4096
    const val MAX_SUBTITLES = 16
    const val MAX_TITLE = 300
    const val MAX_CLIENT = 40

    /** Самая дальняя позиция, с которой можно начать: неделя (защита от мусора в числе). */
    private const val MAX_POSITION_MS = 7L * 24 * 3600 * 1000

    val TYPES = setOf("hls", "dash", "progressive", "auto")
    val ACTIONS = setOf("pause", "play", "seek", "stop")

    /** Тело запроса не подходит; сообщение уходит клиенту в поле error. */
    class BadRequest(message: String) : Exception(message)

    class Subtitle(val url: String, val lang: String?, val label: String?)

    /** Что прислали в POST /lumen/play. positionMs: с какого места начать, 0: с начала. */
    class Play(
        val url: String,
        val type: String,
        val headers: Map<String, String>,
        val title: String?,
        val pageUrl: String?,
        val positionMs: Long,
        val subtitles: List<Subtitle>,
    ) {
        /** Тип для интента плеера: HLS, DASH или null (обычный файл или «угадай по ссылке»). */
        val mime: String?
            get() = when (type) {
                "hls" -> StreamRequest.MIME_HLS
                "dash" -> StreamRequest.MIME_DASH
                else -> null
            }
    }

    /** POST /lumen/control. positionMs нужен только для seek. */
    class Control(val action: String, val positionMs: Long?)

    /**
     * Ссылка http или https с непустым хостом, без пробелов и управляющих символов, не длиннее
     * [MAX_URL]. Разбирается вручную: java.net.URI отвергает ссылки, которые браузеры и CDN
     * спокойно отдают (фигурные скобки, | в запросе).
     */
    fun isHttpUrl(s: String?): Boolean {
        if (s.isNullOrEmpty() || s.length > MAX_URL) return false
        if (s.any { it <= ' ' || it == '\u007f' }) return false
        val lower = s.lowercase(Locale.ROOT)
        val rest = when {
            lower.startsWith("http://") -> s.substring(7)
            lower.startsWith("https://") -> s.substring(8)
            else -> return false
        }
        val authority = rest.substring(0, rest.indexOfAny(charArrayOf('/', '?', '#')).let { if (it < 0) rest.length else it })
        val host = authority.substringAfterLast('@').let { h ->
            if (h.startsWith("[")) h.substringBefore(']').removePrefix("[") else h.substringBefore(':')
        }
        if (host.isEmpty()) return false
        val port = authority.substringAfterLast('@').let { h -> if (h.startsWith("[")) h.substringAfter("]", "") else h.substringAfter(':', "") }
            .removePrefix(":")
        return port.isEmpty() || (port.all { it.isDigit() } && port.length <= 5 && port.toInt() in 1..65535)
    }

    /**
     * CORS: запросы из расширений Chrome и Firefox и без заголовка Origin (curl, тесты).
     * Обычная веб-страница сюда не достучится: иначе любой сайт мог бы выводить коды на экран.
     */
    fun originAllowed(origin: String?): Boolean =
        origin == null || origin.startsWith("chrome-extension://") || origin.startsWith("moz-extension://")

    /** Токен из "Authorization: Bearer <64 hex>", иначе null. */
    fun bearer(header: String?): String? {
        val h = header?.trim() ?: return null
        if (!h.regionMatches(0, "Bearer ", 0, 7, ignoreCase = true)) return null
        val t = h.substring(7).trim().lowercase(Locale.ROOT)
        return t.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
    }

    /** POST /lumen/pair: имя браузера для экрана и списка устройств. Пустое тело допустимо. */
    fun parsePairStart(body: String): String {
        val o = if (body.isBlank()) JSONObject() else obj(body)
        return cleanText(str(o, "client"), MAX_CLIENT) ?: "Браузер"
    }

    /** POST /lumen/pair/confirm: ровно четыре цифры. */
    fun parsePairConfirm(body: String): String {
        val code = str(obj(body), "code")?.trim()
        if (code == null || code.length != 4 || !code.all { it in '0'..'9' }) throw BadRequest("code must be 4 digits")
        return code
    }

    fun parsePlay(body: String): Play {
        val o = obj(body)
        val url = str(o, "url")?.trim()
        if (!isHttpUrl(url)) throw BadRequest("url must be http or https")
        val type = (str(o, "type") ?: "auto").lowercase(Locale.ROOT)
        if (type !in TYPES) throw BadRequest("type must be one of ${TYPES.joinToString("|")}")
        return Play(
            url = url!!,
            type = type,
            headers = headers(o.optJSONObject("headers")),
            title = cleanText(str(o, "title"), MAX_TITLE),
            pageUrl = str(o, "pageUrl")?.trim()?.takeIf { isHttpUrl(it) },
            positionMs = positionMs(o.opt("position")) ?: 0L,
            subtitles = subtitles(o),
        )
    }

    fun parseControl(body: String): Control {
        val o = obj(body)
        val action = str(o, "action")?.lowercase(Locale.ROOT)
        if (action == null || action !in ACTIONS) throw BadRequest("action must be one of ${ACTIONS.joinToString("|")}")
        val pos = positionMs(o.opt("position"))
        if (action == "seek" && pos == null) throw BadRequest("seek needs position in seconds")
        return Control(action, pos)
    }

    private fun obj(body: String): JSONObject = try {
        JSONObject(body)
    } catch (e: JSONException) {
        throw BadRequest("body must be a JSON object")
    }

    /** Строка поля или null (нет поля, null, не строка). */
    private fun str(o: JSONObject, key: String): String? = if (o.isNull(key)) null else o.opt(key) as? String

    /** Секунды из JSON в миллисекунды; отрицательное и не число: null. */
    private fun positionMs(v: Any?): Long? {
        val sec = (v as? Number)?.toDouble() ?: return null
        if (sec.isNaN() || sec.isInfinite() || sec < 0) return null
        return (sec * 1000).toLong().coerceAtMost(MAX_POSITION_MS)
    }

    /** Без управляющих символов, обрезано, пустое: null. */
    private fun cleanText(s: String?, max: Int): String? =
        s?.filter { it >= ' ' && it != '\u007f' }?.trim()?.take(max)?.trim()?.takeIf { it.isNotEmpty() }

    private fun headers(o: JSONObject?): Map<String, String> {
        if (o == null) return emptyMap()
        if (o.length() > MAX_HEADERS) throw BadRequest("too many headers")
        val map = LinkedHashMap<String, String?>()
        for (k in o.keys()) {
            val v = str(o, k) ?: continue
            if (v.length <= MAX_HEADER_VALUE) map[k] = v
        }
        return StreamRequest.headers(null, map)
    }

    private fun subtitles(o: JSONObject): List<Subtitle> {
        val arr = o.optJSONArray("subtitles") ?: return emptyList()
        val out = ArrayList<Subtitle>()
        for (i in 0 until arr.length()) {
            if (out.size >= MAX_SUBTITLES) break
            val s = arr.optJSONObject(i) ?: continue
            val url = str(s, "url")?.trim()
            if (!isHttpUrl(url)) continue
            out += Subtitle(url!!, cleanText(str(s, "lang"), 16), cleanText(str(s, "label"), 60))
        }
        return out
    }
}

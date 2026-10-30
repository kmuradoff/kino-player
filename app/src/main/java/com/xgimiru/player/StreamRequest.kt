package com.xgimiru.player

import java.util.Locale

/**
 * Что пришло вместе с ссылкой: тип потока и заголовки запроса (расширение браузера через send/SendServer, MX Player, Just Player).
 *
 * Заголовки:
 *  - extra "headers": String[] {имя, значение, имя, значение, ...} (соглашение MX Player);
 *  - extra "android.media.intent.extra.HTTP_HEADERS": Bundle {имя: значение}.
 * Обе формы читаются, при совпадении имени побеждает массив. Заголовки, которыми управляет сам
 * HTTP-клиент (Range, Host, ...), отбрасываются: с ними ломаются перемотка и сегменты.
 */
object StreamRequest {
    const val EXTRA_HEADERS = "headers"
    const val EXTRA_HEADERS_BUNDLE = "android.media.intent.extra.HTTP_HEADERS"
    const val DEFAULT_USER_AGENT = "Kino/1.0 (Android TV)"

    const val MIME_HLS = "application/x-mpegURL"
    const val MIME_DASH = "application/dash+xml"

    private val DROPPED = setOf("range", "host", "content-length", "connection", "accept-encoding", "transfer-encoding")

    /** Заголовки из обеих форм; ключи в том виде, как прислали, без дублей без учёта регистра. */
    fun headers(pairs: Array<String?>?, bundle: Map<String, String?>?): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        fun put(k: String?, v: String?) {
            val name = k?.trim().orEmpty()
            if (name.isEmpty() || v == null || name.lowercase(Locale.ROOT) in DROPPED) return
            if (name.any { it <= ' ' || it == ':' } || v.any { it == '\r' || it == '\n' }) return
            out.keys.firstOrNull { it.equals(name, true) }?.let { out.remove(it) }
            out[name] = v.trim()
        }
        bundle?.forEach { (k, v) -> put(k, v) }
        if (pairs != null) for (i in 0 until pairs.size - 1 step 2) put(pairs[i], pairs[i + 1])
        return out
    }

    fun userAgent(headers: Map<String, String>): String =
        headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value?.takeIf { it.isNotBlank() } ?: DEFAULT_USER_AGENT

    fun referer(headers: Map<String, String>): String? =
        headers.entries.firstOrNull { it.key.equals("Referer", true) }?.value?.takeIf { it.isNotBlank() }

    /** Всё, кроме User-Agent (его HTTP-клиент ставит отдельно). */
    fun withoutUserAgent(headers: Map<String, String>) = headers.filterKeys { !it.equals("User-Agent", true) }

    /**
     * Тип потока для Media3: HLS, DASH или null (обычный файл, Media3 разберёт сам).
     * [mimeHint] — тип из интента, [path] — путь ссылки без запроса, [query] — строка запроса.
     */
    fun streamMime(mimeHint: String?, path: String?, query: String? = null): String? {
        when (mimeHint?.lowercase(Locale.ROOT)?.substringBefore(';')?.trim()) {
            "application/x-mpegurl", "application/vnd.apple.mpegurl", "audio/mpegurl", "audio/x-mpegurl" -> return MIME_HLS
            "application/dash+xml" -> return MIME_DASH
        }
        val p = path?.lowercase(Locale.ROOT).orEmpty()
        val ext = p.substringAfterLast('/').substringAfterLast('.', "")
        when (ext) {
            "m3u8" -> return MIME_HLS
            "mpd" -> return MIME_DASH
        }
        // .../master.m3u8/index или ?format=m3u8
        val q = query?.lowercase(Locale.ROOT).orEmpty()
        if (".m3u8/" in p || Regex("(^|[&=])(m3u8|hls)($|&)").containsMatchIn(q)) return MIME_HLS
        if (".mpd/" in p || Regex("(^|[&=])(mpd|dash)($|&)").containsMatchIn(q)) return MIME_DASH
        return null
    }

    fun isAdaptive(mime: String?) = mime == MIME_HLS || mime == MIME_DASH
}

package com.xgimiru.player.net

import com.xgimiru.player.ui.tr
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/** Скачать текст или поток по http(s), с распаковкой gzip, или прочитать файл. Блокирующее. */
object Net {
    const val UA = "LumenPlay/1.0 (Android TV)"

    fun open(url: String, timeoutMs: Int = 20_000): InputStream {
        if (url.startsWith("/") || url.startsWith("file://")) return gunzip(File(url.removePrefix("file://")).inputStream())
        // TMDB в некоторых сетях спрятан DNS-ом: адрес через DNS-over-HTTPS (см. Resolve)
        if (Regex("^https://[^/]*(tmdb\\.org|themoviedb\\.org)/").containsMatchIn(url)) return gunzip(Resolve.open(url, timeoutMs))
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

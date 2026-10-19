package com.xgimiru.player.dlna

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Крошечный HTTP/1.1-сервер для UPnP: описания, SOAP и подписки GENA. Каждое соединение
 * обслуживается одним запросом и закрывается. Потоков не больше [WORKERS], очередь короткая,
 * заголовки до 16 КБ, тело до 256 КБ; без запросов потоки засыпают и уходят.
 */
class HttpServer(private val handler: (Request) -> Response) {

    class Request(
        val method: String,
        val path: String,
        /** Имена заголовков в нижнем регистре. */
        val headers: Map<String, String>,
        val body: ByteArray,
        val remote: InetAddress,
    ) {
        fun header(name: String): String? = headers[name.lowercase(Locale.US)]
        fun text() = String(body, Charsets.UTF_8)
    }

    class Response(
        val status: Int,
        val body: ByteArray = ByteArray(0),
        val type: String? = null,
        val headers: List<Pair<String, String>> = emptyList(),
        /** Выполняется после того, как ответ ушёл (первое событие подписки). */
        val after: (() -> Unit)? = null,
    ) {
        companion object {
            fun xml(text: String, status: Int = 200) = Response(status, text.toByteArray(Charsets.UTF_8), "text/xml; charset=\"utf-8\"")
        }
    }

    private var socket: ServerSocket? = null
    private val pool = ThreadPoolExecutor(WORKERS, WORKERS, 20, TimeUnit.SECONDS, ArrayBlockingQueue(16)) { r ->
        Thread(r, "lumen-dlna-http").apply { isDaemon = true }
    }.apply { allowCoreThreadTimeOut(true) }

    val port: Int get() = socket?.localPort ?: 0

    /** Пробует постоянный порт (контроллеры запоминают адрес), иначе любой свободный. */
    fun start(preferred: Int) {
        val ss = ServerSocket()
        ss.reuseAddress = true
        try {
            ss.bind(InetSocketAddress(preferred), 16)
        } catch (e: Exception) {
            ss.bind(InetSocketAddress(0), 16)
        }
        socket = ss
        Thread({ acceptLoop(ss) }, "lumen-dlna-accept").apply { isDaemon = true }.start()
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
        pool.shutdownNow()
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (!ss.isClosed) {
            val s = try { ss.accept() } catch (e: Exception) { if (ss.isClosed) return else continue }
            try {
                pool.execute { serve(s) }
            } catch (e: RejectedExecutionException) {
                runCatching { s.close() }
            }
        }
    }

    private fun serve(s: Socket) {
        try {
            s.soTimeout = 10_000
            s.tcpNoDelay = true
            val input = Deadline(s.getInputStream().buffered(), System.currentTimeMillis() + 15_000)
            val req = try {
                read(input, s.inetAddress)
            } catch (e: TooLarge) {
                write(s, Response(413), false)
                return
            }
            val res = if (req == null) Response(400) else try {
                handler(req)
            } catch (e: Exception) {
                Response(500)
            }
            write(s, res, req?.method == "HEAD")
            res.after?.let { runCatching { it() } }
        } catch (_: Exception) {
        } finally {
            runCatching { s.close() }
        }
    }

    private class TooLarge : Exception()

    /** Весь запрос должен прийти за 15 секунд, даже если клиент шлёт по байту. */
    private class Deadline(private val inner: InputStream, private val until: Long) : InputStream() {
        private fun check() { if (System.currentTimeMillis() > until) throw java.io.IOException("slow client") }
        override fun read(): Int { check(); return inner.read() }
        override fun read(b: ByteArray, off: Int, len: Int): Int { check(); return inner.read(b, off, len) }
    }

    private fun read(input: InputStream, remote: InetAddress): Request? {
        val head = readHead(input) ?: return null
        val lines = head.split("\r\n", "\n")
        val first = lines.firstOrNull()?.split(' ') ?: return null
        if (first.size < 3 || !first[2].startsWith("HTTP/")) return null
        val headers = HashMap<String, String>()
        for (l in lines.drop(1)) {
            val c = l.indexOf(':')
            if (c > 0 && headers.size < 64) headers[l.substring(0, c).trim().lowercase(Locale.US)] = l.substring(c + 1).trim()
        }
        val body = when {
            headers["transfer-encoding"]?.contains("chunked", true) == true -> readChunked(input)
            else -> {
                val len = headers["content-length"]?.toIntOrNull() ?: 0
                if (len < 0 || len > MAX_BODY) throw TooLarge()
                readN(input, len)
            }
        } ?: return null
        val path = first[1].substringBefore('?')
        return Request(first[0].uppercase(Locale.US), path, headers, body, remote)
    }

    /** Строки до пустой, не больше 16 КБ. */
    private fun readHead(input: InputStream): String? {
        val b = ByteArrayOutputStream(512)
        var state = 0
        while (b.size() < MAX_HEAD) {
            val c = input.read()
            if (c < 0) return null
            b.write(c)
            state = when {
                c == '\n'.code && (state == 1 || state == 3) -> state + 1
                c == '\r'.code && (state == 0 || state == 2) -> state + 1
                c == '\n'.code && state == 0 -> 2       // голый \n
                c == '\n'.code && state == 2 -> 4
                else -> 0
            }
            if (state == 4) return String(b.toByteArray(), Charsets.ISO_8859_1).trimEnd()
        }
        throw TooLarge()
    }

    private fun readN(input: InputStream, n: Int): ByteArray? {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(out, off, n - off)
            if (r < 0) return null
            off += r
        }
        return out
    }

    private fun readLine(input: InputStream): String? {
        val b = StringBuilder()
        while (b.length < 256) {
            val c = input.read()
            if (c < 0) return null
            if (c == '\n'.code) return b.toString().trim()
            b.append(c.toChar())
        }
        return null
    }

    private fun readChunked(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        while (true) {
            val size = readLine(input)?.substringBefore(';')?.trim()?.toIntOrNull(16) ?: return null
            if (size < 0 || out.size() + size > MAX_BODY) throw TooLarge()
            if (size == 0) {
                while (true) { val l = readLine(input) ?: return out.toByteArray(); if (l.isEmpty()) break }
                return out.toByteArray()
            }
            out.write(readN(input, size) ?: return null)
            readLine(input)
        }
    }

    private fun write(s: Socket, r: Response, head: Boolean) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(r.status).append(' ').append(reason(r.status)).append("\r\n")
        sb.append("Server: ").append(SERVER).append("\r\n")
        sb.append("Date: ").append(httpDate()).append("\r\n")
        sb.append("Content-Length: ").append(r.body.size).append("\r\n")
        r.type?.let { sb.append("Content-Type: ").append(it).append("\r\n") }
        for ((k, v) in r.headers) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("Connection: close\r\n\r\n")
        val out = s.getOutputStream()
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
        if (!head) out.write(r.body)
        out.flush()
    }

    companion object {
        private const val WORKERS = 4
        private const val MAX_HEAD = 16 * 1024
        const val MAX_BODY = 256 * 1024

        var SERVER = "Android/${android.os.Build.VERSION.RELEASE} UPnP/1.0 LumenPlay/1"

        fun httpDate(): String = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("GMT") }.format(Date())

        private fun reason(code: Int) = when (code) {
            200 -> "OK"; 400 -> "Bad Request"; 404 -> "Not Found"; 405 -> "Method Not Allowed"
            412 -> "Precondition Failed"; 413 -> "Payload Too Large"; 500 -> "Internal Server Error"
            503 -> "Service Unavailable"; else -> "Status"
        }

        /**
         * Событие GENA подписчику (метод NOTIFY, которого нет у HttpURLConnection). true: доставлено.
         * Тайм-ауты короткие: медленный контроллер не задерживает остальных надолго.
         */
        fun notify(url: URL, sid: String, seq: Long, body: String): Boolean = runCatching {
            val bytes = body.toByteArray(Charsets.UTF_8)
            Socket().use { s ->
                val port = if (url.port > 0) url.port else 80
                s.connect(InetSocketAddress(url.host, port), 2500)
                s.soTimeout = 3000
                val path = (url.path.ifEmpty { "/" }) + (url.query?.let { "?$it" } ?: "")
                val head = "NOTIFY $path HTTP/1.1\r\nHOST: ${url.host}:$port\r\n" +
                    "CONTENT-TYPE: text/xml; charset=\"utf-8\"\r\nNT: upnp:event\r\nNTS: upnp:propchange\r\n" +
                    "SID: $sid\r\nSEQ: $seq\r\nCONTENT-LENGTH: ${bytes.size}\r\nCONNECTION: close\r\n\r\n"
                val out = s.getOutputStream()
                out.write(head.toByteArray(Charsets.UTF_8))
                out.write(bytes)
                out.flush()
                val status = s.getInputStream().bufferedReader().readLine() ?: ""
                status.split(' ').getOrNull(1)?.toIntOrNull()?.let { it in 200..299 } ?: false
            }
        }.getOrDefault(false)
    }
}

package com.xgimiru.player

import android.content.Context
import android.net.Uri
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer

/**
 * Главы MKV: где в серии начинаются титры. Media3 главы из Matroska не отдаёт, поэтому читаем их
 * сами: начало файла (заголовок EBML, SeekHead), а если главы дальше, то только их кусок по
 * ссылке из SeekHead. По HTTP это запросы с Range, и TorrServer отдаёт их как обычный поток.
 */
object Chapters {
    class Chapter(val startMs: Long, val names: List<String>)

    private const val HEAD = 64 * 1024
    private const val MAX_CHAPTERS = 1024 * 1024

    private const val EBML = 0x1A45DFA3L
    private const val SEGMENT = 0x18538067L
    private const val SEEK_HEAD = 0x114D9B74L
    private const val SEEK = 0x4DBBL
    private const val SEEK_ID = 0x53ABL
    private const val SEEK_POSITION = 0x53ACL
    private const val CHAPTERS = 0x1043A770L
    private const val CLUSTER = 0x1F43B675L
    private const val EDITION = 0x45B9L
    private const val EDITION_DEFAULT = 0x45DBL
    private const val EDITION_ORDERED = 0x45DDL
    private const val ATOM = 0xB6L
    private const val TIME_START = 0x91L
    private const val FLAG_HIDDEN = 0x98L
    private const val FLAG_ENABLED = 0x4598L
    private const val DISPLAY = 0x80L
    private const val STRING = 0x85L

    /** Блокирующий, не из главного потока. Пустой список: не MKV, глав нет или файл не прочитался. */
    fun read(ctx: Context, uri: Uri): List<Chapter> = runCatching { parse(ctx, uri) }.getOrDefault(emptyList())

    /**
     * Начало титров: первая глава во второй половине серии с названием вроде «Титры», «Credits»,
     * «Ending», «ED». Во второй половине, чтобы не принять за них начальные титры.
     */
    fun creditsStart(chapters: List<Chapter>, durationMs: Long): Long? =
        chapters.filter { it.startMs >= durationMs / 2 && it.startMs < durationMs && it.names.any(::isCredits) }
            .minOfOrNull { it.startMs }

    fun isCredits(name: String): Boolean {
        val n = name.lowercase().trim()
        if (listOf("начал", "вступ", "opening", "intro", "опенинг").any { it in n }) return false
        if (listOf("титр", "credit", "ending", "эндинг", "outro", "аутро").any { it in n }) return true
        val words = n.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        return words.any { it.matches(Regex("ed\\d*")) } || words == listOf("end") || words == listOf("конец")
    }

    // ── Файл ────────────────────────────────────────────────────────────────
    private class Element(val id: Long, val size: Long, val header: Int)

    private fun parse(ctx: Context, uri: Uri): List<Chapter> {
        val head = fetch(ctx, uri, 0, HEAD)
        val ebml = element(head, 0) ?: return emptyList()
        if (ebml.id != EBML || ebml.size < 0) return emptyList()
        var p = (ebml.header + ebml.size).toInt()
        val segment = element(head, p) ?: return emptyList()
        if (segment.id != SEGMENT) return emptyList()
        val segmentData = (p + segment.header).toLong()
        p = segmentData.toInt()
        var seekChapters: Long? = null
        while (p < head.size) {
            val e = element(head, p) ?: break
            val body = p + e.header
            when (e.id) {
                CHAPTERS -> return chapters(
                    if (body + e.size <= head.size) head.copyOfRange(body, (body + e.size).toInt())
                    else fetch(ctx, uri, body.toLong(), e.size.coerceAtMost(MAX_CHAPTERS.toLong()).toInt()))
                SEEK_HEAD -> if (body + e.size <= head.size) {
                    seekChapters = seekPosition(head, body, (body + e.size).toInt(), CHAPTERS)?.let { segmentData + it }
                }
                CLUSTER -> break
            }
            if (e.size < 0) break
            p = (body + e.size).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        // главы дальше начала файла: читаем только их, по адресу из SeekHead
        val at = seekChapters ?: return emptyList()
        val h = fetch(ctx, uri, at, 16)
        val e = element(h, 0) ?: return emptyList()
        if (e.id != CHAPTERS || e.size !in 1..MAX_CHAPTERS) return emptyList()
        return chapters(fetch(ctx, uri, at + e.header, e.size.toInt()))
    }

    private fun seekPosition(b: ByteArray, from: Int, to: Int, target: Long): Long? {
        for ((seek, sb, se) in children(b, from, to)) {
            if (seek.id != SEEK) continue
            var id = -1L
            var pos = -1L
            for ((c, cb, ce) in children(b, sb, se)) {
                when (c.id) {
                    SEEK_ID -> id = uint(b, cb, ce)
                    SEEK_POSITION -> pos = uint(b, cb, ce)
                }
            }
            if (id == target && pos >= 0) return pos
        }
        return null
    }

    private fun chapters(b: ByteArray): List<Chapter> {
        val editions = children(b, 0, b.size).filter { it.first.id == EDITION }
        // упорядоченные главы (части из других файлов) живут на своей временной шкале: пропускаем
        val usable = editions.filter { (_, s, e) -> children(b, s, e).none { it.first.id == EDITION_ORDERED && uint(b, it.second, it.third) == 1L } }
        val edition = usable.firstOrNull { (_, s, e) -> children(b, s, e).any { it.first.id == EDITION_DEFAULT && uint(b, it.second, it.third) == 1L } }
            ?: usable.firstOrNull() ?: return emptyList()
        val out = mutableListOf<Chapter>()
        atoms(b, edition.second, edition.third, out)
        return out.sortedBy { it.startMs }
    }

    private fun atoms(b: ByteArray, from: Int, to: Int, out: MutableList<Chapter>) {
        for ((atom, s, e) in children(b, from, to)) {
            if (atom.id != ATOM) continue
            var start = -1L
            var visible = true
            val names = mutableListOf<String>()
            for ((c, cs, ce) in children(b, s, e)) {
                when (c.id) {
                    TIME_START -> start = uint(b, cs, ce) / 1_000_000
                    FLAG_HIDDEN -> if (uint(b, cs, ce) == 1L) visible = false
                    FLAG_ENABLED -> if (uint(b, cs, ce) == 0L) visible = false
                    DISPLAY -> children(b, cs, ce).filter { it.first.id == STRING }
                        .forEach { (_, ss, se) -> names += String(b, ss, se - ss, Charsets.UTF_8) }
                }
            }
            if (start >= 0 && visible) out += Chapter(start, names)
            atoms(b, s, e, out)   // вложенные главы
        }
    }

    /** Дочерние элементы с известным размером в [from, to): (элемент, начало данных, конец данных). */
    private fun children(b: ByteArray, from: Int, to: Int): List<Triple<Element, Int, Int>> {
        val out = mutableListOf<Triple<Element, Int, Int>>()
        var p = from
        while (p < to) {
            val e = element(b, p) ?: break
            if (e.size < 0) break
            val s = p + e.header
            val end = s + e.size
            if (end > to) break
            out += Triple(e, s, end.toInt())
            p = end.toInt()
        }
        return out
    }

    /** Заголовок элемента EBML в позиции p: ID (с маркером), размер (-1 = неизвестен), длина заголовка. */
    private fun element(b: ByteArray, p: Int): Element? {
        if (p >= b.size) return null
        val idLen = vintLength(b[p]) ?: return null
        if (idLen > 4 || p + idLen >= b.size) return null
        var id = 0L
        for (i in 0 until idLen) id = (id shl 8) or (b[p + i].toLong() and 0xFF)
        val q = p + idLen
        val sizeLen = vintLength(b[q]) ?: return null
        if (q + sizeLen > b.size) return null
        var size = (b[q].toLong() and 0xFF) and (0xFFL shr sizeLen)
        var allOnes = size == (0xFFL shr sizeLen)
        for (i in 1 until sizeLen) {
            val v = b[q + i].toLong() and 0xFF
            size = (size shl 8) or v
            allOnes = allOnes && v == 0xFFL
        }
        return Element(id, if (allOnes) -1 else size, idLen + sizeLen)
    }

    private fun vintLength(first: Byte): Int? {
        val v = first.toInt() and 0xFF
        if (v == 0) return null
        return Integer.numberOfLeadingZeros(v) - 23
    }

    private fun uint(b: ByteArray, from: Int, to: Int): Long {
        var v = 0L
        for (i in from until to) v = (v shl 8) or (b[i].toLong() and 0xFF)
        return v
    }

    // ── Чтение куска файла: HTTP (Range), file://, content:// ────────────────
    private fun fetch(ctx: Context, uri: Uri, offset: Long, len: Int): ByteArray = when (uri.scheme?.lowercase()) {
        "http", "https" -> fetchHttp(uri, offset, len)
        "file" -> RandomAccessFile(uri.path, "r").use { f ->
            f.seek(offset)
            val buf = ByteArray(len.toLong().coerceAtMost(f.length() - offset).coerceAtLeast(0).toInt())
            f.readFully(buf)
            buf
        }
        "content" -> ctx.contentResolver.openFileDescriptor(uri, "r")!!.use { pfd ->
            FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                val buf = ByteBuffer.allocate(len)
                ch.position(offset)
                while (buf.hasRemaining() && ch.read(buf) > 0) Unit
                buf.array().copyOf(buf.position())
            }
        }
        else -> ByteArray(0)
    }

    private fun fetchHttp(uri: Uri, offset: Long, len: Int): ByteArray {
        val c = URL(uri.toString()).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 30_000   // торрент может не сразу отдать нужный кусок
            c.setRequestProperty("Range", "bytes=$offset-${offset + len - 1}")
            val code = c.responseCode
            // без поддержки Range сервер отдаст файл целиком: тогда годится только начало
            if (code != 206 && !(code == 200 && offset == 0L)) return ByteArray(0)
            c.inputStream.use { inp ->
                val buf = ByteArray(len)
                var n = 0
                while (n < len) {
                    val r = inp.read(buf, n, len - n)
                    if (r < 0) break
                    n += r
                }
                return buf.copyOf(n)
            }
        } finally {
            c.disconnect()
        }
    }
}

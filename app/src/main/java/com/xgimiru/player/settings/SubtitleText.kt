package com.xgimiru.player.settings

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * External subtitle files: which ones belong to a video, which character set they use, and moving their
 * times. Pure code without Android, covered by unit tests.
 */
object SubtitleText {
    val EXTENSIONS = setOf("srt", "ass", "ssa", "vtt")

    /**
     * Character set of a subtitle file. BOM first, then strict UTF-8, then the Cyrillic and Western
     * single-byte sets told apart by letter statistics. [fallback] is the user's choice for "not sure".
     */
    fun detect(bytes: ByteArray, fallback: String = "windows-1252"): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) return "UTF-8"
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return "UTF-16LE"
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return "UTF-16BE"
        if (looksUtf16(bytes)) return if (bytes.size > 1 && bytes[0] == 0.toByte()) "UTF-16BE" else "UTF-16LE"
        val high = bytes.count { it < 0 }
        if (high == 0) return "UTF-8"
        if (isUtf8(bytes)) return "UTF-8"
        val letters = bytes.count { (it in 'a'.code.toByte()..'z'.code.toByte()) || (it in 'A'.code.toByte()..'Z'.code.toByte()) }
        // Cyrillic text is made of high bytes; Western text has an accent here and there.
        if (high < (letters + high) * 0.3) return if (fallback.isCyrillic()) "windows-1252" else fallback
        val cp = cyrillicScore(bytes, "windows-1251")
        val koi = cyrillicScore(bytes, "KOI8-R")
        val dos = cyrillicScore(bytes, "IBM866")
        return listOf("windows-1251" to cp, "KOI8-R" to koi, "IBM866" to dos).maxByOrNull { it.second }!!.first
    }

    private fun String.isCyrillic() = this in setOf("windows-1251", "KOI8-R", "IBM866")

    private fun looksUtf16(b: ByteArray): Boolean {
        if (b.size < 8) return false
        val n = minOf(b.size, 400)
        var zeroEven = 0; var zeroOdd = 0
        for (i in 0 until n) if (b[i] == 0.toByte()) { if (i % 2 == 0) zeroEven++ else zeroOdd++ }
        return maxOf(zeroEven, zeroOdd) > n / 4 && minOf(zeroEven, zeroOdd) < n / 40
    }

    fun isUtf8(b: ByteArray): Boolean = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(b)); true
    } catch (e: CharacterCodingException) { false }

    /**
     * Russian prose is mostly lower case and uses о, е, а, и, н, т, с most: decode with the candidate and
     * score small letters (the frequent ones double), minus capitals inside words.
     */
    private fun cyrillicScore(b: ByteArray, cs: String): Int {
        val s = String(b, Charset.forName(cs))
        var score = 0
        var prev = ' '
        for (c in s) {
            when {
                c in 'а'..'я' || c == 'ё' -> score += if (c in "оеаинтс") 2 else 1
                c in 'А'..'Я' || c == 'Ё' -> if (prev in 'а'..'я') score -= 3
                c in '\u2500'..'\u25FF' -> score -= 2      // box drawing: DOS bytes read as something else
            }
            prev = c
        }
        return score
    }

    fun decode(bytes: ByteArray, charset: String): String {
        var s = String(bytes, Charset.forName(charset))
        if (s.startsWith('\uFEFF')) s = s.substring(1)
        return s
    }

    /** Language from a name like "Film.ru.srt", "Film.rus.forced.ass" or "Film [eng].srt"; null if none. */
    fun language(fileName: String): String? {
        val base = fileName.substringBeforeLast('.').lowercase()
        val parts = base.split('.', '_', ' ', '-', '[', ']', '(', ')').filter { it.isNotEmpty() }
        for (p in parts.asReversed().take(3)) ISO[p]?.let { return it }
        return null
    }

    private val ISO = mapOf(
        "ru" to "ru", "rus" to "ru", "russian" to "ru", "en" to "en", "eng" to "en", "english" to "en",
        "uk" to "uk", "ukr" to "uk", "ua" to "uk", "de" to "de", "ger" to "de", "deu" to "de", "fr" to "fr", "fre" to "fr", "fra" to "fr",
        "es" to "es", "spa" to "es", "it" to "it", "ita" to "it", "pt" to "pt", "por" to "pt", "pl" to "pl", "pol" to "pl",
        "tr" to "tr", "tur" to "tr", "ja" to "ja", "jpn" to "ja", "ko" to "ko", "kor" to "ko", "zh" to "zh", "chi" to "zh", "zho" to "zh",
        "kk" to "kk", "kaz" to "kk",
    )

    /**
     * Subtitle files of [video] among [names] (file names of the same folder, or "Subs/x.srt" from a subtitle
     * folder). The name must start with the video's name; files in the preferred [langs] come first, then the
     * one named exactly like the video, then the rest by name.
     */
    fun match(video: String, names: List<String>, langs: List<String>): List<String> {
        val base = video.substringBeforeLast('.').lowercase()
        val subsDir = Regex("^(subs?|subtitles?|субтитры)/", RegexOption.IGNORE_CASE)
        return names.filter { n ->
            val ext = n.substringAfterLast('.', "").lowercase()
            if (ext !in EXTENSIONS) return@filter false
            val name = n.substringAfterLast('/').lowercase()
            val inSubs = subsDir.containsMatchIn(n)
            name.startsWith(base) || (inSubs && n.count { it == '/' } == 1)
        }.sortedWith(compareBy<String>(
            { language(it.substringAfterLast('/'))?.let { l -> langs.indexOf(l).takeIf { i -> i >= 0 } } ?: langs.size },
            { if (it.substringAfterLast('/').substringBeforeLast('.').lowercase() == base) 0 else 1 },
            { it.lowercase() }))
    }

    private val SRT = Regex("(\\d{1,2}):(\\d{2}):(\\d{2})([,.])(\\d{3})")
    private val VTT_SHORT = Regex("(?<![\\d:])(\\d{2}):(\\d{2})\\.(\\d{3})")
    private val ASS = Regex("^(Dialogue:\\s*[^,]*,)(\\d+):(\\d{2}):(\\d{2})\\.(\\d{2}),(\\d+):(\\d{2}):(\\d{2})\\.(\\d{2}),")

    /** Move every cue by [deltaMs] (positive = later). Times never go below zero. [ext] srt, vtt, ass or ssa. */
    fun shift(text: String, ext: String, deltaMs: Long): String {
        if (deltaMs == 0L) return text
        return when (ext.lowercase()) {
            "ass", "ssa" -> text.lines().joinToString("\n") { line ->
                ASS.find(line)?.let { m ->
                    val g = m.groupValues
                    val a = assTime(g[2], g[3], g[4], g[5]) + deltaMs
                    val b = assTime(g[6], g[7], g[8], g[9]) + deltaMs
                    g[1] + fmtAss(a) + "," + fmtAss(b) + "," + line.substring(m.range.last + 1)
                } ?: line
            }
            else -> text.lines().joinToString("\n") { line ->
                if (!line.contains("-->")) line
                else {
                    val full = SRT.replace(line) { m ->
                        val g = m.groupValues
                        fmtSrt(((g[1].toLong() * 60 + g[2].toLong()) * 60 + g[3].toLong()) * 1000 + g[5].toLong() + deltaMs, g[4])
                    }
                    VTT_SHORT.replace(full) { m ->
                        val g = m.groupValues
                        fmtSrt((g[1].toLong() * 60 + g[2].toLong()) * 1000 + g[3].toLong() + deltaMs, ".")
                    }
                }
            }
        }
    }

    private fun assTime(h: String, m: String, s: String, cs: String) = ((h.toLong() * 60 + m.toLong()) * 60 + s.toLong()) * 1000 + cs.toLong() * 10

    private fun fmtAss(t0: Long): String {
        val t = t0.coerceAtLeast(0)
        return "%d:%02d:%02d.%02d".format(t / 3_600_000, t / 60_000 % 60, t / 1000 % 60, t % 1000 / 10)
    }

    private fun fmtSrt(t0: Long, sep: String): String {
        val t = t0.coerceAtLeast(0)
        return "%02d:%02d:%02d%s%03d".format(t / 3_600_000, t / 60_000 % 60, t / 1000 % 60, sep, t % 1000)
    }
}

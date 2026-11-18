package com.xgimiru.player.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleTextTest {
    private val ru = "1\n00:00:01,000 --> 00:00:03,500\nПривет, как дела? Это проверка русских субтитров.\n\n" +
        "2\n00:00:04,000 --> 00:00:06,000\nОна сказала, что придёт завтра вечером.\n"
    private val fr = "1\n00:00:01,000 --> 00:00:03,500\nC'est déjà l'été, où êtes-vous allés?\n\n" +
        "2\n00:00:04,000 --> 00:00:06,000\nTrès bien, merci beaucoup.\n"

    @Test fun utf8() = assertEquals("UTF-8", SubtitleText.detect(ru.toByteArray(Charsets.UTF_8)))

    @Test fun utf8WithBom() = assertEquals("UTF-8", SubtitleText.detect(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + ru.toByteArray()))

    @Test fun utf16() {
        assertEquals("UTF-16LE", SubtitleText.detect(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + ru.toByteArray(Charsets.UTF_16LE)))
        assertEquals("UTF-16LE", SubtitleText.detect(ru.toByteArray(Charsets.UTF_16LE)))
        assertEquals("UTF-16BE", SubtitleText.detect(ru.toByteArray(Charsets.UTF_16BE)))
    }

    @Test fun windows1251() = assertEquals("windows-1251", SubtitleText.detect(ru.toByteArray(charset("windows-1251"))))

    @Test fun koi8r() = assertEquals("KOI8-R", SubtitleText.detect(ru.toByteArray(charset("KOI8-R"))))

    @Test fun dos866() = assertEquals("IBM866", SubtitleText.detect(ru.toByteArray(charset("IBM866"))))

    @Test fun westernStaysWestern() {
        assertEquals("windows-1252", SubtitleText.detect(fr.toByteArray(charset("windows-1252"))))
        assertEquals("windows-1250", SubtitleText.detect(fr.toByteArray(charset("windows-1252")), fallback = "windows-1250"))
    }

    @Test fun plainAscii() = assertEquals("UTF-8", SubtitleText.detect("1\n00:00:01,000 --> 00:00:02,000\nHello\n".toByteArray()))

    @Test fun decodeDropsBom() {
        val b = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "Привет".toByteArray()
        assertEquals("Привет", SubtitleText.decode(b, "UTF-8"))
        assertEquals(ru, SubtitleText.decode(ru.toByteArray(charset("windows-1251")), "windows-1251"))
    }

    @Test fun languageFromName() {
        assertEquals("ru", SubtitleText.language("Film.2019.ru.srt"))
        assertEquals("en", SubtitleText.language("Film.eng.forced.ass"))
        assertEquals("uk", SubtitleText.language("Film [ukr].srt"))
        assertNull(SubtitleText.language("Film.srt"))
    }

    @Test fun matchesSiblingsInLanguageOrder() {
        val names = listOf("Film.mkv", "Film.en.srt", "Film.srt", "Film.ru.ass", "Other.srt", "Film.nfo", "Subs/Rus.srt", "Subs/deep/x.srt")
        val got = SubtitleText.match("Film.mkv", names, listOf("ru", "en"))
        assertEquals(listOf("Film.ru.ass", "Subs/Rus.srt", "Film.en.srt", "Film.srt"), got)
    }

    @Test fun matchIgnoresCase() {
        assertEquals(listOf("film.SRT"), SubtitleText.match("Film.MKV", listOf("film.SRT", "film.txt"), emptyList()))
    }

    @Test fun shiftSrt() {
        val out = SubtitleText.shift(ru, "srt", 1500)
        assertTrue(out.contains("00:00:02,500 --> 00:00:05,000"))
        assertTrue(out.contains("00:00:05,500 --> 00:00:07,500"))
        assertTrue(out.contains("Привет, как дела?"))
    }

    @Test fun shiftNeverBelowZero() {
        val out = SubtitleText.shift(ru, "srt", -2000)
        assertTrue(out.contains("00:00:00,000 --> 00:00:01,500"))
    }

    @Test fun shiftVtt() {
        val vtt = "WEBVTT\n\n00:01.000 --> 00:03.250\nHi\n\n01:00:00.000 --> 01:00:01.000\nLate\n"
        val out = SubtitleText.shift(vtt, "vtt", 250)
        assertTrue(out, out.contains("00:00:01.250 --> 00:00:03.500"))
        assertTrue(out, out.contains("01:00:00.250 --> 01:00:01.250"))
    }

    @Test fun shiftAss() {
        val ass = "[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
            "Dialogue: 0,0:00:01.50,0:00:04.00,Default,,0,0,0,,Привет, мир\n"
        val out = SubtitleText.shift(ass, "ass", -500)
        assertTrue(out, out.contains("Dialogue: 0,0:00:01.00,0:00:03.50,Default,,0,0,0,,Привет, мир"))
    }
}

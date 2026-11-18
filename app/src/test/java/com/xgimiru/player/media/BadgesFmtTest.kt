package com.xgimiru.player.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class BadgesFmtTest {
    @Test fun qualityByPictureSize() {
        assertEquals("4K", Badges.quality(3840, 1600))
        assertEquals("1080p", Badges.quality(1920, 800))
        assertEquals("720p", Badges.quality(1280, 536))
        assertEquals("SD", Badges.quality(720, 576))
        assertNull(Badges.quality(0, 0))
    }

    @Test fun chipsFromTheNameUntilTheFileWasRead() {
        assertEquals(listOf("4K", "HDR"), Badges.chips(null, "Дюна. Часть вторая (2024) UHD BDRemux 2160p HDR DV.mkv"))
        assertEquals(listOf("1080p"), Badges.chips(null, "Брат 2 (2000) BDRip 1080p.mkv"))
        assertEquals(emptyList<String>(), Badges.chips(null, "Свадьба Лены и Артёма.mp4"))
        // the file itself wins over its name
        assertEquals(listOf("1080p"), Badges.chips(MediaFacts(width = 1920, height = 1080, quick = false), "Film.2160p.HDR.mkv"))
    }

    @Test fun cardBadgesAndLanguages() {
        val f = MediaFacts(width = 3840, height = 2160, hdr = "HDR10", dolbyVision = true, atmos = true, audio = listOf("rus", "eng"), subs = listOf("ru", "en", "ru"), quick = false)
        assertEquals(listOf("4K", "HDR10", "Dolby Vision", "Atmos"), Badges.card(f))
        assertEquals("RU, EN", Badges.short(f.subs))
        assertEquals("Русский, английский", Badges.names(f.audio, ru = true))
        assertEquals("Русские, английские", Badges.names(f.subs, ru = true, plural = true))
        assertEquals("Russian, English", Badges.names(f.audio, ru = false))
        assertNull(Badges.names(listOf("und"), ru = true))
    }

    @Test fun sizesTimesAndCounts() {
        assertEquals("58,4 ГБ", Fmt.size((58.4 * 1073741824).toLong(), true))
        assertEquals("940 МБ", Fmt.size(940L * 1048576, true))
        assertEquals("Свободно 12 из 58 ГБ", Fmt.free(12L * 1073741824, 58L * 1073741824, true))
        assertEquals("2 ч 07 мин", Fmt.duration((2 * 60 + 7) * 60_000L, true))
        assertEquals("47 мин", Fmt.duration(47 * 60_000L, true))
        assertEquals("3 файла", Fmt.files(3, true))
        assertEquals("7 объектов", Fmt.objects(7, true))
        assertEquals("21 объект", Fmt.objects(21, true))
        assertEquals("12 объектов", Fmt.objects(12, true))
        assertEquals("1 file", Fmt.files(1, false))
    }

    @Test fun whenWatchedReadsLikeAPerson() {
        val now = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 19, 1, 30, 0) }.timeInMillis
        fun at(d: Int, h: Int, m: Int) = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, d, h, m, 0) }.timeInMillis
        assertEquals("Сегодня, 0:40", Fmt.whenWatched(at(19, 0, 40), now, true))
        assertEquals("Вчера, 23:10", Fmt.whenWatched(at(18, 23, 10), now, true))
        assertEquals("Понедельник", Fmt.whenWatched(at(16, 21, 0), now, true))
        assertEquals("8 ноября", Fmt.whenWatched(at(8, 20, 0), now, true))
        assertEquals("November 8", Fmt.whenWatched(at(8, 20, 0), now, false))
    }

    @Test fun naturalOrderOfEpisodes() {
        val l = listOf("Серия 10.mkv", "Серия 2.mkv", "Серия 1.mkv").sortedWith(Fmt::natural)
        assertEquals(listOf("Серия 1.mkv", "Серия 2.mkv", "Серия 10.mkv"), l)
    }
}

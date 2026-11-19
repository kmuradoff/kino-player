package com.xgimiru.player

import org.junit.Assert.assertEquals
import org.junit.Test

class NameTest {
    @Test fun titleWithASecondPartKeepsItAfterAColon() {
        val n = Name.parse("Дюна. Часть вторая (2024) UHD BDRemux 2160p HDR DV")
        assertEquals("Дюна: Часть вторая", n.title)
        assertEquals("2024", n.year)
    }

    @Test fun titleFromAFileName() {
        assertEquals("Дюна: Часть вторая (2024) UHD BDRemux 2160p HDR DV", Formats.titleFromPath("/x/Дюна. Часть вторая (2024) UHD BDRemux 2160p HDR DV.mkv"))
        assertEquals("Интерстеллар 2014 UHD", Formats.titleFromPath("/x/Интерстеллар.2014.UHD.mkv"))
    }

    @Test fun dottedReleaseNamesAndShortAbbreviations() {
        assertEquals("Интерстеллар", Name.parse("Интерстеллар.2014.UHD.BDRemux.2160p.HDR").title)
        assertEquals("Dr. Strange", Name.parse("Dr. Strange 2016 1080p").title)
        val e = Name.parse("Отчаянные домохозяйки S02E22")
        assertEquals("Отчаянные домохозяйки", e.title)
        assertEquals("2", e.season)
        assertEquals("22", e.episode)
    }
}

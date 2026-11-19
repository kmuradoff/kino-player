package com.xgimiru.player.files

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalArtTest {
    @Test fun kodiNamesNextToTheFilm() {
        val names = listOf("Брат 2 (2000).mkv", "Брат 2 (2000)-poster.jpg", "Брат 2 (2000)-fanart.JPG", "fanart.jpg")
        assertEquals("Брат 2 (2000)-poster.jpg" to "Брат 2 (2000)-fanart.JPG", LocalArt.pick("Брат 2 (2000).mkv", names))
    }

    @Test fun episodeThumbAndFolderPoster() {
        val names = listOf("S02E22.mkv", "S02E22-thumb.jpg", "poster.jpg")
        assertEquals("poster.jpg" to "S02E22-thumb.jpg", LocalArt.pick("S02E22.mkv", names))
    }

    @Test fun nothingAround() {
        assertEquals(null to null, LocalArt.pick("Свадьба.mp4", listOf("Свадьба.mp4", "notes.txt")))
    }

    @Test fun whereTheFileLiesIsShort() {
        assertEquals(listOf("Сериалы", "Сезон 2"), Places.shortPath(listOf("Сериалы", "Отчаянные домохозяйки", "Сезон 2")))
        assertEquals(listOf("Фильмы"), Places.shortPath(listOf("Фильмы")))
    }

    @Test fun usbNames() {
        assertEquals("USB drive", Places.usbName("USB drive"))
    }
}

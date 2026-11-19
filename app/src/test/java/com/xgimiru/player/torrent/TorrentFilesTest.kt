package com.xgimiru.player.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentFilesTest {
    private fun t(vararg paths: String) = TorrentFiles(paths.mapIndexed { i, p -> TorrentFiles.File(i, p, 100L * (i + 1)) })
    private fun names(l: List<TorrentFiles.Entry>) = l.map { (if (it.dir) "D:" else "") + it.name }

    @Test fun skipsTheTorrentsOwnFolder() {
        val f = t("Sintel/Sintel.mp4", "Sintel/poster.jpg", "Sintel/Sintel.de.srt")
        assertEquals("Sintel/", f.top)
        assertEquals(listOf("Sintel.mp4"), names(f.children("")))
    }

    @Test fun foldersFirstInNaturalOrderAndOnlyWithVideos() {
        val f = t("Show/Season 10/e1.mkv", "Show/Season 2/e1.mkv", "Show/Season 2/e2.mkv", "Show/Extras/notes.txt", "Show/trailer.mp4")
        assertEquals("Show/", f.top)
        val root = f.children("")
        assertEquals(listOf("D:Season 2", "D:Season 10", "trailer.mp4"), names(root))
        val s2 = root[0]
        assertEquals(2, s2.count)
        assertEquals(listOf("e1", "e2"), s2.inside)
        assertEquals(listOf("e1.mkv", "e2.mkv"), names(f.children(s2.path)))
    }

    @Test fun episodesSortNaturally() {
        val f = t("a/Ep 10.mkv", "a/Ep 2.mkv", "a/Ep 1.mkv")
        assertEquals(listOf("Ep 1.mkv", "Ep 2.mkv", "Ep 10.mkv"), names(f.children("")))
        assertEquals(listOf("a/Ep 1.mkv", "a/Ep 2.mkv", "a/Ep 10.mkv"), f.videos.map { it.path })
    }

    @Test fun topLevelVideosStartAtTheTop() {
        val f = t("film.mkv", "extras/making.mkv")
        assertEquals("", f.top)
        assertEquals(listOf("D:extras", "film.mkv"), names(f.children("")))
    }

    @Test fun noVideoNoRows() {
        val f = t("book/readme.txt", "book/cover.jpg")
        assertTrue(f.videos.isEmpty())
        assertTrue(f.children("").isEmpty())
    }

    @Test fun subtitlesByNameNextToItInSubsAndElsewhere() {
        val f = t("S/e1.mkv", "S/e2.mkv", "S/e1.ru.srt", "S/Subs/e1.en.srt", "S/Subs/e2.srt", "Subs RU/e1.ass", "S/e10.srt")
        val e1 = f.videos.first { it.name == "e1.mkv" }
        val got = f.subsFor(e1, listOf("ru")).map { it.path }
        assertTrue(got.contains("S/e1.ru.srt"))
        assertTrue(got.contains("S/Subs/e1.en.srt"))
        assertTrue(got.contains("Subs RU/e1.ass"))
        assertEquals("S/e1.ru.srt", got.first())
    }

    @Test fun oneVideoTakesEverySubtitle() {
        val f = t("Movie/movie.mkv", "Movie/Russian.srt", "Movie/English.srt")
        val got = f.subsFor(f.videos[0], listOf("en")).map { it.path }.toSet()
        assertEquals(setOf("Movie/Russian.srt", "Movie/English.srt"), got)
    }

    @Test fun magnetName() {
        assertEquals("Big Buck Bunny", TorrentFiles.magnetName("magnet:?xt=urn:btih:dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c&dn=Big+Buck+Bunny&tr=udp%3A%2F%2Fx"))
        assertEquals("Синтел", TorrentFiles.magnetName("magnet:?xt=urn:btih:08ada5a7&dn=%D0%A1%D0%B8%D0%BD%D1%82%D0%B5%D0%BB"))
        assertNull(TorrentFiles.magnetName("magnet:?xt=urn:btih:08ada5a7a6183aae1e09d831df6748d566095a10"))
        assertNull(TorrentFiles.magnetName("https://example.com/a.torrent"))
    }

    @Test fun torrentBytes() {
        assertTrue(TorrentFiles.looksLikeTorrent("d8:announce".toByteArray()))
        assertFalse(TorrentFiles.looksLikeTorrent("<html>".toByteArray()))
        assertFalse(TorrentFiles.looksLikeTorrent(ByteArray(0)))
    }

    @Test fun resumeRow() {
        assertEquals(0, TorrentFiles.resume(listOf(0L to false, 0L to false)))
        assertEquals(1, TorrentFiles.resume(listOf(5L to false, 9L to false, 7L to false)))
        // watched to the end: the next one
        assertEquals(2, TorrentFiles.resume(listOf(5L to false, 9L to true, 0L to false)))
        // the last one watched to the end stays on it
        assertEquals(1, TorrentFiles.resume(listOf(5L to false, 9L to true)))
        assertEquals(0, TorrentFiles.resume(emptyList()))
    }
}

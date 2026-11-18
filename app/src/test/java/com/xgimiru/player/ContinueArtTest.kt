package com.xgimiru.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContinueArtTest {
    private val bd = "https://image.tmdb.org/t/p/w1280/bd.jpg"
    private val still = "https://image.tmdb.org/t/p/w1280/st.jpg"
    private val grab = "content://com.xgimiru.player.art/1_2.jpg"

    @Test fun backdropFirstThenStill() {
        val a = ContinueArt.pick(bd, still, grab)
        assertEquals(bd, a.poster)
        assertEquals(still, a.thumbnail)
    }

    @Test fun noStillTheGrabIsSecond() {
        val a = ContinueArt.pick(bd, null, grab)
        assertEquals(bd, a.poster)
        assertEquals(grab, a.thumbnail)
    }

    @Test fun noTmdbOnlyTheGrabAsBefore() {
        val a = ContinueArt.pick(null, null, grab)
        assertEquals(grab, a.poster)
        assertNull(a.thumbnail)
    }

    @Test fun noBackdropTheStillLeads() {
        val a = ContinueArt.pick("", still, grab)
        assertEquals(still, a.poster)
        assertEquals(grab, a.thumbnail)
    }

    @Test fun nothing() {
        val a = ContinueArt.pick(null, null, null)
        assertNull(a.poster)
        assertNull(a.thumbnail)
    }

    @Test fun sameUrlOnce() {
        val a = ContinueArt.pick(bd, bd, null)
        assertEquals(bd, a.poster)
        assertNull(a.thumbnail)
    }

    @Test fun tmdbSize() {
        assertEquals("https://image.tmdb.org/t/p/w1280/x.jpg", ContinueArt.tmdb("/x.jpg"))
    }
}

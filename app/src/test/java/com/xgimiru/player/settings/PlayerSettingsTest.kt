package com.xgimiru.player.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSettingsTest {

    @Test fun defaultsWhenNothingStored() {
        val s = Settings(MapStore())
        assertEquals("auto", s.string(K.ENGINE))
        assertEquals("auto", s.string(K.DECODER))
        assertFalse(s.bool(K.TUNNELING))
        assertTrue(s.bool(K.PASSTHROUGH))
        assertEquals(10_000L, s.seekStepMs)
        assertEquals(3500L, s.osdTimeoutMs)
        assertEquals(0, s.int(K.AUDIO_DELAY))
        assertEquals("file", s.string(K.SUB_LANG))
        assertEquals("auto", s.string(K.SUB_ENCODING))
        assertNull(s.aspect)
    }

    @Test fun valuesSurviveANewInstance() {
        val store = MapStore()
        Settings(store).apply {
            set(K.SEEK_STEP, "30")
            set(K.TUNNELING, true)
            set(K.AUDIO_DELAY, 150)
            set(K.SUB_ENCODING, "windows-1251")
            set(K.ASPECT, "21:9")
        }
        val again = Settings(store)
        assertEquals(30_000L, again.seekStepMs)
        assertTrue(again.bool(K.TUNNELING))
        assertEquals(150, again.int(K.AUDIO_DELAY))
        assertEquals("windows-1251", again.string(K.SUB_ENCODING))
        assertEquals(21f / 9f, again.aspect!!, 0.0001f)
    }

    @Test fun keysOfVersion13StayReadable() {
        // 1.3 stored these through SharedPreferences as boolean and int; the store hands them over as text
        val store = MapStore(mutableMapOf("tunneling" to "true", "doviHdr" to "true", "scaleMode" to "2"))
        val s = Settings(store)
        assertTrue(s.bool(K.TUNNELING))
        assertEquals("true", s.string(K.DV5))
        assertEquals(2, s.scale)
    }

    @Test fun brokenValuesReadAsDefault() {
        val s = Settings(MapStore(mutableMapOf("seekStep" to "7", "passthrough" to "maybe", "audioDelay" to "x")))
        assertEquals(10_000L, s.seekStepMs)
        assertTrue(s.bool(K.PASSTHROUGH))
        assertEquals(0, s.int(K.AUDIO_DELAY))
    }

    @Test fun stepperClampsToRangeAndGrid() {
        val store = MapStore()
        val s = Settings(store)
        s.set(K.AUDIO_DELAY, 99_999)
        assertEquals(2000, s.int(K.AUDIO_DELAY))
        s.set(K.AUDIO_DELAY, -37)
        assertEquals(-50, s.int(K.AUDIO_DELAY))
        store.put("subDelay", "-123456")
        assertEquals(-10_000, s.int(K.SUB_DELAY))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownChoiceIsRefused() = Settings(MapStore()).set(K.SEEK_STEP, "7")

    @Test fun resetRemovesEverything() {
        val store = MapStore()
        val s = Settings(store)
        s.set(K.BOOST, "6"); s.set(K.NIGHT, true); s.set(K.SUB_DELAY, 500)
        s.reset()
        assertTrue(store.map.isEmpty())
        assertEquals("0", s.string(K.BOOST))
    }

    @Test fun formatRowsHideWithoutPassthrough() {
        val s = Settings(MapStore())
        assertTrue(s.visible(K.PT_DTS))
        s.set(K.PASSTHROUGH, false)
        assertFalse(s.visible(K.PT_DTS))
        assertFalse(s.visible(K.SUB_LANG2))
        s.set(K.SUB_LANG, "ru")
        assertTrue(s.visible(K.SUB_LANG2))
    }

    @Test fun passthroughPerFormat() {
        val s = Settings(MapStore())
        assertTrue(Passthrough.allowed("audio/ac3", s))
        assertTrue(Passthrough.allowed("audio/raw", s))
        s.set(K.PT_DTS, false)
        assertFalse(Passthrough.allowed("audio/vnd.dts", s))
        assertTrue(Passthrough.allowed("audio/vnd.dts.hd", s))
        s.set(K.PASSTHROUGH, false)
        assertFalse(Passthrough.allowed("audio/eac3", s))
        assertTrue(Passthrough.allowed("audio/mp4a-latm", s))
    }

    @Test fun labelsReadLikeTheMenu() {
        val s = Settings(MapStore())
        assertEquals("10 с", s.label(K.SEEK_STEP, true))
        assertEquals("Выкл.", s.label(K.TUNNELING, true))
        s.set(K.SUB_DELAY, 1500)
        assertEquals("+1,5 с", s.label(K.SUB_DELAY, true))
        s.set(K.AUDIO_DELAY, -75)
        assertEquals("\u221275 ms", s.label(K.AUDIO_DELAY, false))
    }

    @Test fun everyKeyIsUnique() {
        assertEquals(K.ALL.size, K.ALL.map { it.key }.toSet().size)
    }

    @Test fun frameRateHookIsOffByDefault() {
        assertFalse(FrameRateHook.active())
    }
}

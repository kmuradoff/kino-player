package com.xgimiru.player.audio

import com.xgimiru.player.audio.AudioRoute.Device
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRouteTest {
    private val ac3 = 5
    private val dts = 7
    private val truehd = 14
    private val pcm16 = 2

    @Test fun speakerNeverGetsCompressedSoundEvenIfItsHalListsIt() {
        // the Z9X HAL lists AC3 on the built-in speaker and then reports a wrong play position
        assertFalse(AudioRoute.compressedAllowed(Device(AudioRoute.SPEAKER, setOf(pcm16, ac3)), ac3, false))
        assertFalse(AudioRoute.compressedAllowed(Device(AudioRoute.SPEAKER), ac3, false))
    }

    @Test fun bluetoothHeadphonesAndUsbDecode() {
        for (t in listOf(AudioRoute.BLUETOOTH_A2DP, AudioRoute.BLE_HEADSET, AudioRoute.BLE_SPEAKER, AudioRoute.WIRED_HEADPHONES,
                AudioRoute.WIRED_HEADSET, AudioRoute.USB_HEADSET, AudioRoute.HEARING_AID, AudioRoute.LINE_ANALOG))
            assertFalse("type $t", AudioRoute.compressedAllowed(Device(t, setOf(ac3, dts)), ac3, false))
    }

    @Test fun receiverGetsWhatItDeclares() {
        val arc = Device(AudioRoute.HDMI_ARC, setOf(pcm16, ac3))
        assertTrue(AudioRoute.compressedAllowed(arc, ac3, false))
        assertFalse(AudioRoute.compressedAllowed(arc, dts, false))
        assertTrue(AudioRoute.compressedAllowed(Device(AudioRoute.HDMI_EARC, setOf(pcm16, truehd)), truehd, false))
        assertTrue(AudioRoute.compressedAllowed(Device(AudioRoute.HDMI, setOf(ac3)), ac3, false))
    }

    @Test fun receiverThatListsOnlyPcmDecodes() {
        assertFalse(AudioRoute.compressedAllowed(Device(AudioRoute.HDMI_ARC, setOf(pcm16)), ac3, false))
    }

    @Test fun receiverWithoutAListLeavesItToMedia3() {
        assertTrue(AudioRoute.compressedAllowed(Device(AudioRoute.HDMI_ARC), dts, false))
    }

    @Test fun atmosGoesAsEac3WhenOnlyEac3IsListed() {
        val arc = Device(AudioRoute.HDMI_ARC, setOf(AudioRoute.ENC_E_AC3))
        assertTrue(AudioRoute.compressedAllowed(arc, AudioRoute.ENC_E_AC3_JOC, false))
        assertFalse(AudioRoute.compressedAllowed(Device(AudioRoute.HDMI_ARC, setOf(ac3)), AudioRoute.ENC_E_AC3_JOC, false))
    }

    @Test fun brokenRouteOrNoRouteDecodes() {
        assertFalse(AudioRoute.compressedAllowed(Device(AudioRoute.HDMI_ARC, setOf(ac3)), ac3, true))
        assertFalse(AudioRoute.compressedAllowed(null, ac3, false))
    }

    @Test fun guessFollowsThePolicyOrder() {
        val spk = Device(AudioRoute.SPEAKER)
        val arc = Device(AudioRoute.HDMI_ARC, setOf(ac3))
        val bt = Device(AudioRoute.BLUETOOTH_A2DP)
        assertEquals(spk, AudioRoute.guess(listOf(spk)))
        assertEquals(arc, AudioRoute.guess(listOf(spk, arc)))
        assertEquals(bt, AudioRoute.guess(listOf(spk, arc, bt)))
        assertEquals(Device(AudioRoute.HDMI_EARC), AudioRoute.guess(listOf(Device(AudioRoute.HDMI), Device(AudioRoute.HDMI_EARC))))
        assertEquals(Device(99), AudioRoute.guess(listOf(Device(99))))
        assertNull(AudioRoute.guess(emptyList()))
    }

    @Test fun dolbyAndDtsDecodeWithFfmpegWhenItHasThem() {
        val has = { m: String -> m != "audio/ac4" }
        for (m in listOf("audio/ac3", "audio/eac3", "audio/eac3-joc", "audio/true-hd", "audio/vnd.dts", "audio/vnd.dts.hd"))
            assertTrue(m, AudioRoute.decodeWithFfmpeg(m, has))
        assertFalse(AudioRoute.decodeWithFfmpeg("audio/ac4", has))
        assertFalse(AudioRoute.decodeWithFfmpeg("audio/mp4a-latm", has))
        assertFalse(AudioRoute.decodeWithFfmpeg("audio/ac3") { false })
    }

    @Test fun jumpWatchTripsOnTheThirdJumpWithinTheWindow() {
        val w = JumpWatch(limit = 3, windowMs = 20_000)
        assertFalse(w.jump(0))
        assertFalse(w.jump(1_000))
        assertTrue(w.jump(2_000))
    }

    @Test fun jumpWatchIgnoresRareJumps() {
        val w = JumpWatch(limit = 3, windowMs = 20_000)
        assertFalse(w.jump(0))
        assertFalse(w.jump(15_000))
        assertFalse(w.jump(36_000))
        assertFalse(w.jump(57_000))
        w.reset()
        assertFalse(w.jump(58_000))
        assertFalse(w.jump(59_000))
    }
}

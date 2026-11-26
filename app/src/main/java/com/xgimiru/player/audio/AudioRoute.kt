package com.xgimiru.player.audio

import com.xgimiru.player.settings.Passthrough

/**
 * Where compressed sound (AC3, E-AC3, DTS, TrueHD) may leave the player untouched. Only an HDMI receiver
 * decodes it for real: the built-in speaker, Bluetooth, headphones and USB get PCM decoded by the player.
 * Some vendor audio HALs accept AC3 on the speaker through a compressed offload output and then report
 * the wrong play position, so the picture jumps every second and the sound falls behind (XGIMI Z9X).
 * Pure logic, host tested; [AudioOutput] feeds it from the system.
 */
object AudioRoute {
    // AudioDeviceInfo.TYPE_* values
    const val SPEAKER = 2
    const val WIRED_HEADSET = 3
    const val WIRED_HEADPHONES = 4
    const val LINE_ANALOG = 5
    const val LINE_DIGITAL = 6
    const val BLUETOOTH_A2DP = 8
    const val HDMI = 9
    const val HDMI_ARC = 10
    const val USB_DEVICE = 11
    const val USB_ACCESSORY = 12
    const val AUX_LINE = 19
    const val USB_HEADSET = 22
    const val HEARING_AID = 23
    const val BLE_HEADSET = 26
    const val BLE_SPEAKER = 27
    const val HDMI_EARC = 29
    const val BLE_BROADCAST = 30

    // AudioFormat.ENCODING_* values that have a fallback name on receivers
    const val ENC_E_AC3 = 6
    const val ENC_E_AC3_JOC = 18

    data class Device(val type: Int, val encodings: Set<Int> = emptySet())

    private val RECEIVER = setOf(HDMI, HDMI_ARC, HDMI_EARC)

    /** Media goes to the first present type, the order Android's audio policy uses for these outputs. */
    private val ORDER = listOf(BLE_HEADSET, BLE_SPEAKER, BLE_BROADCAST, BLUETOOTH_A2DP, HEARING_AID,
        WIRED_HEADSET, WIRED_HEADPHONES, USB_HEADSET, USB_DEVICE, USB_ACCESSORY,
        HDMI_EARC, HDMI_ARC, HDMI, LINE_DIGITAL, LINE_ANALOG, AUX_LINE, SPEAKER)

    fun isReceiver(d: Device?) = d != null && d.type in RECEIVER

    /** The device media plays on when the system cannot tell (Android 12 and older). */
    fun guess(outputs: List<Device>): Device? =
        ORDER.firstNotNullOfOrNull { t -> outputs.firstOrNull { it.type == t } } ?: outputs.firstOrNull()

    /**
     * May a compressed stream of [encoding] go out untouched on [route]? Only to an HDMI receiver. A receiver
     * that lists its encodings must list this one; an empty list means the system did not say, then Media3's
     * own capability check decides. [broken]: passthrough already looped on this route in this run.
     */
    fun compressedAllowed(route: Device?, encoding: Int, broken: Boolean): Boolean {
        if (broken || route == null || route.type !in RECEIVER) return false
        if (route.encodings.isEmpty()) return true
        return encoding in route.encodings || (encoding == ENC_E_AC3_JOC && ENC_E_AC3 in route.encodings)
    }

    /**
     * Decode this compressed format with FFmpeg rather than the chip's decoder when it is not passed through.
     * The chip decoder stays for formats FFmpeg lacks (AC-4). Passthrough itself does not use a decoder.
     */
    fun decodeWithFfmpeg(mime: String, ffmpegHas: (String) -> Boolean) = Passthrough.isCompressed(mime) && ffmpegHas(mime)
}

/**
 * Counts audio timestamp jumps while compressed sound goes out untouched. [limit] jumps within [windowMs]
 * mean the output reports a broken position (it resyncs every second): the caller decodes to PCM instead.
 */
class JumpWatch(private val limit: Int = 3, private val windowMs: Long = 20_000) {
    private val times = ArrayDeque<Long>()

    @Synchronized fun jump(nowMs: Long): Boolean {
        times.addLast(nowMs)
        while (nowMs - times.first() > windowMs) times.removeFirst()
        return times.size >= limit
    }

    @Synchronized fun reset() = times.clear()
}

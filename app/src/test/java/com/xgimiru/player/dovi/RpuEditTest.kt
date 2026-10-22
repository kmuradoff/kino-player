package com.xgimiru.player.dovi

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream

/**
 * Правка RPU на настоящих RPU профиля 8.1 (тестовый ролик «DV8 metadata test lake»,
 * первые 48 кадров: полезная нагрузка NAL 62 после заголовка, с эмуляционными байтами).
 */
class RpuEditTest {

    private val rpus: List<ByteArray> = DataInputStream(javaClass.getResourceAsStream("/dv8_rpus.bin")!!).use { s ->
        buildList { while (s.available() > 0) add(ByteArray(s.readInt()).also { s.readFully(it) }) }
    }

    @Test fun untouchedRpuIsByteExact() {
        val e = RpuEditor(toP81 = false, zeroL5 = false)
        for (r in rpus) assertArrayEquals(r, e.edit(r, 0, r.size))
    }

    @Test fun profile8IsUnchangedByP81Conversion() {
        val e = RpuEditor(toP81 = true, zeroL5 = false)
        for (r in rpus) assertArrayEquals(r, e.edit(r, 0, r.size))
    }

    @Test fun brokenCrcIsRefused() {
        val r = rpus[0].copyOf()
        r[10] = (r[10].toInt() xor 0x10).toByte()
        assertNull(RpuEditor(false, false).edit(r, 0, r.size))
    }

    /** Матрицы, которые ставит перевод в 8.1, совпадают с теми, что в настоящем файле 8.1. */
    @Test fun p81MatricesMatchARealProfile81File() {
        val f = DoviRpuParser().parse(rpus[0], 0, rpus[0].size)!!
        for (i in 0 until 9) {
            assertEquals(RpuEditor.P81_YCC[i] / 8192f, f.nonlinear[i], 0f)
            assertEquals(RpuEditor.P81_LMS[i] / 16384f, f.linear[i], 0f)
        }
        for (i in 0 until 3) assertEquals((RpuEditor.P81_OFFSET[i].toDouble() / (1L shl 28)).toFloat(), f.nonlinearOffset[i], 0f)
    }

    @Test fun zeroL5() {
        val read = RpuEditor(false, false)
        val zero = RpuEditor(false, true)
        var withL5 = 0
        for (r in rpus) {
            read.edit(r, 0, r.size)
            val out = zero.edit(r, 0, r.size)!!
            assertNotNull(DoviRpuParser().parse(out, 0, out.size))
            read.edit(out, 0, out.size)!!                   // CRC новой RPU верна, иначе null
            read.l5?.let { assertArrayEquals(IntArray(4), it); withL5++ }
        }
        assertTrue("в ролике есть L5", withL5 > 0)
    }

    /** Синтетический профиль 7 (из настоящей 8.1: NLQ, другие матрицы) → 8.1. */
    @Test fun profile7To81() {
        for (src in rpus.take(8)) {
            val p7 = Nal.escape(makeProfile7(Nal.unescape(src, 0, src.size)))
            assertNull("парсер шейдера не берёт RPU с NLQ", DoviRpuParser().parse(p7, 0, p7.size))
            val out = RpuEditor(toP81 = true, zeroL5 = false).edit(p7, 0, p7.size)!!
            val f = DoviRpuParser().parse(out, 0, out.size)!!
            for (c in 0 until 3) {
                assertEquals(2, f.numPivots[c])
                assertEquals(0f, f.pivots[c][0], 0f); assertEquals(1f, f.pivots[c][1], 0f)
                assertEquals(0, f.method[c][0])
                assertArrayEquals(floatArrayOf(0f, 1f, 0f), f.poly[c][0], 0f)
            }
            for (i in 0 until 9) assertEquals(RpuEditor.P81_YCC[i] / 8192f, f.nonlinear[i], 0f)
            // RPU, переведённая в 8.1, без правок переписывается байт в байт
            assertArrayEquals(out, RpuEditor(false, false).edit(out, 0, out.size))
        }
    }

    @Test fun hdr10PlusSei() {
        // payloadType 4, size, B5 003C 0001 04
        val sei = byteArrayOf(4, 40, 0xB5.toByte(), 0, 0x3C, 0, 1, 4, 1, 2)
        assertTrue(Nal.isHdr10PlusSei(sei, 0, sei.size))
        val mastering = byteArrayOf(137.toByte(), 24, 0, 1, 2, 3, 4, 5)
        assertTrue(!Nal.isHdr10PlusSei(mastering, 0, mastering.size))
    }

    @Test fun escapeRoundTrip() {
        val raw = byteArrayOf(0, 0, 0, 0, 0, 1, 0, 0, 2, 0, 0, 3, 5)
        val esc = Nal.escape(raw)
        assertArrayEquals(raw, Nal.unescape(esc, 0, esc.size))
    }

    // ---- выбор пути, как в Kodi ----

    private val z9x = HdrPolicy.Caps(dvDisplay = false, hdr10PlusDisplay = true, dvProfiles = setOf(4, 5, 8))
    private val dvTv = HdrPolicy.Caps(dvDisplay = true, hdr10PlusDisplay = true, dvProfiles = setOf(5, 8))
    private val dv = MimeTypes.VIDEO_DOLBY_VISION

    @Test fun planWithoutDvDisplay() {
        val p = HdrPolicy.Prefs()
        assertEquals(HdrPolicy.Route.SOFT5, HdrPolicy.plan(dv, "dvhe.05.06", z9x, p).route)
        assertEquals(HdrPolicy.Route.STRIP, HdrPolicy.plan(dv, "dvhe.08.06", z9x, p).route)
        assertEquals(HdrPolicy.Route.STRIP, HdrPolicy.plan(dv, "dvhe.07.06", z9x, p).route)
        assertEquals(HdrPolicy.Route.PASS, HdrPolicy.plan(MimeTypes.VIDEO_H265, null, z9x, p).route)
        assertEquals(HdrPolicy.Route.HEVC_EDIT, HdrPolicy.plan(MimeTypes.VIDEO_H265, null, z9x, p.copy(hdr10Plus = false)).route)
    }

    @Test fun planWithDvDisplay() {
        val p = HdrPolicy.Prefs()
        assertEquals(HdrPolicy.Route.PASS, HdrPolicy.plan(dv, "dvhe.05.06", dvTv, p).route)
        assertEquals(HdrPolicy.Route.PASS, HdrPolicy.plan(dv, "dvh1.08.06", dvTv, p).route)
        val p7 = HdrPolicy.plan(dv, "dvhe.07.06", dvTv, p)
        assertEquals(HdrPolicy.Route.DV_EDIT, p7.route)
        assertTrue(p7.toP81)
        assertEquals("dvhe.08.06", p7.codecs)
        assertEquals(HdrPolicy.Route.STRIP, HdrPolicy.plan(dv, "dvhe.07.06", dvTv, p.copy(p7to81 = false)).route)
        assertEquals(HdrPolicy.Route.DV_EDIT, HdrPolicy.plan(dv, "dvhe.08.06", dvTv, p.copy(zeroL5 = true)).route)
        assertEquals(HdrPolicy.Route.STRIP, HdrPolicy.plan(dv, "dvhe.08.06", dvTv, p.copy(dvHardware = false)).route)
        assertEquals(HdrPolicy.Route.SOFT5, HdrPolicy.plan(dv, "dvhe.05.06", dvTv, p.copy(dvHardware = false)).route)
    }

    /**
     * Делает из RPU 8.1 «профиль 7»: el_spatial_resampling = 1, disable_residual = 0,
     * заголовок NLQ после опорных точек, данные NLQ после кривых, свои матрицы DM.
     */
    private fun makeProfile7(raw: ByteArray): ByteArray {
        var size = raw.size
        while (raw[size - 1].toInt() == 0) size--
        val r = BitReader(raw, 1, size - 5)
        val w = BitWriter()
        w.bits(25, 8)
        fun cp(n: Int) = w.bitsLong(r.bitsLong(n), n)
        fun cpUe() = r.ueLong().also { w.ue(it) }
        cp(6); cp(11); cp(4); cp(4)
        check(r.bit()); w.bit(true)
        cp(1)
        val coefType = r.bits(2).also { w.bits(it, 2) }
        check(coefType == 0)
        val denom = cpUe().toInt()
        cp(2); cp(1)
        val bl = cpUe().toInt() + 8
        val el = (cpUe().toInt() and 0xFF) + 8
        cpUe(); cp(1); cp(3)
        r.bit(); r.bit(); w.bit(true); w.bit(false)
        val dm = r.bit().also { w.bit(it) }
        check(!r.bit()); w.bit(false)
        cpUe(); cpUe(); cpUe()
        val nPiv = IntArray(3)
        for (c in 0 until 3) { nPiv[c] = cpUe().toInt() + 2; repeat(nPiv[c]) { cp(bl) } }
        w.bits(0, 3); w.bits(0, bl); w.bits((1 shl bl) - 1, bl)       // NLQ: linear dead zone
        cpUe(); cpUe()
        fun cpSe() { w.se(r.seLong()); cp(denom) }
        for (c in 0 until 3) for (i in 0 until nPiv[c] - 1) {
            when (cpUe()) {
                0L -> { val o = cpUe(); if (o == 0L) cp(1); repeat(o.toInt() + 2) { cpSe() } }
                else -> { val o = r.bits(2).also { w.bits(it, 2) }; cpSe(); repeat((o + 1) * 7) { cpSe() } }
            }
        }
        for (c in 0 until 3) { w.bits(64, el); repeat(3) { w.ue(1); w.bitsLong(12345, denom) } }
        check(dm)
        cpUe(); cpUe(); cpUe()
        r.skip(9 * 16 + 3 * 32 + 9 * 16)
        repeat(9) { w.bits(1000 + it, 16) }; repeat(3) { w.bitsLong(1L shl 27, 32) }; repeat(9) { w.bits(2000 + it, 16) }
        for (n in intArrayOf(16, 16, 16, 32, 5, 2, 2, 2, 12, 12, 10)) cp(n)
        fun ext() {
            val n = cpUe()
            if (n > 0) { r.align(); w.alignZero() }
            repeat(n.toInt()) { val len = cpUe(); cp(8); repeat(len.toInt()) { cp(8) } }
        }
        ext()
        if (r.left() >= 8) ext()
        w.alignZero()
        val body = w.toByteArray()
        val crc = Nal.crc32(body, 1, body.size)
        return body + byteArrayOf((crc ushr 24).toByte(), (crc ushr 16).toByte(), (crc ushr 8).toByte(), crc.toByte(), 0x80.toByte())
    }
}

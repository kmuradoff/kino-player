package com.xgimiru.player.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class Ac3FramerTest {
    private class Out(val data: ByteArray, val timeUs: Long)

    /** AC-3, 48 kHz, 192 kbit/s: 768 bytes, 1536 samples (32 ms) per frame. */
    private fun ac3(rnd: Random): ByteArray = ByteArray(768).also {
        rnd.nextBytes(it)
        it[0] = 0x0B; it[1] = 0x77; it[4] = 20; it[5] = (8 shl 3).toByte()
    }

    /** E-AC-3, 6 blocks: [type] 0 = independent, 1 = dependent substream. */
    private fun eac3(rnd: Random, type: Int, size: Int): ByteArray = ByteArray(size).also {
        rnd.nextBytes(it)
        val frmsiz = size / 2 - 1
        it[0] = 0x0B; it[1] = 0x77
        it[2] = ((type shl 6) or (frmsiz shr 8)).toByte(); it[3] = frmsiz.toByte()
        it[4] = (3 shl 4).toByte(); it[5] = (16 shl 3).toByte()
    }

    /** Feeds [stream] the AVI way: chunks of [chunk] bytes, timestamps spread by bytes from [startUs]. */
    private fun feed(f: Ac3Framer, stream: ByteArray, chunk: Int, startUs: Long, usPerByte: Double) {
        var at = 0
        while (at < stream.size) {
            val n = minOf(chunk, stream.size - at)
            f.append(stream, at, n)
            f.chunk(startUs + (at * usPerByte).toLong(), n, 0)
            at += n
        }
    }

    private fun framer(out: MutableList<Out>) = Ac3Framer(48_000) { d, o, n, t -> out += Out(d.copyOfRange(o, o + n), t) }

    @Test fun aviChunksOfOneAndAQuarterFramesComeOutAsWholeFrames() {
        val rnd = Random(1)
        val frames = List(200) { ac3(rnd) }
        val out = mutableListOf<Out>()
        // 25 fps AVI: one 960-byte chunk per 40 ms
        feed(framer(out), frames.reduce { a, b -> a + b }, 960, 0, 40_000.0 / 960)
        assertEquals(200, out.size)
        out.forEachIndexed { i, o ->
            assertArrayEquals("frame $i", frames[i], o.data)
            assertEquals("time of frame $i", i * 32_000L, o.timeUs)
        }
    }

    @Test fun timestampsFollowTheFramesNotTheChunkCount() {
        // the sink counts 1536 samples per sample: with whole frames it sees exactly the timestamps' duration
        val rnd = Random(2)
        val out = mutableListOf<Out>()
        feed(framer(out), List(400) { ac3(rnd) }.reduce { a, b -> a + b }, 960, 5_000_000, 40_000.0 / 960)
        for (i in 1 until out.size) assertEquals(32_000L, out[i].timeUs - out[i - 1].timeUs)
    }

    @Test fun afterASeekTheFirstPartFrameIsSkippedAndTimeComesFromTheChunk() {
        val rnd = Random(3)
        val frames = List(50) { ac3(rnd) }
        val stream = frames.reduce { a, b -> a + b }
        val out = mutableListOf<Out>()
        val f = framer(out)
        feed(f, stream.copyOfRange(0, 9600), 960, 0, 40_000.0 / 960)
        f.reset()
        out.clear()
        // a seek lands on a chunk that starts 300 bytes into frame 20
        val from = 20 * 768 + 300
        feed(f, stream.copyOfRange(from, stream.size), 960, (from * 40_000.0 / 960).toLong(), 40_000.0 / 960)
        assertEquals(29, out.size)
        assertArrayEquals(frames[21], out[0].data)
        assertTrue("time ${out[0].timeUs}", kotlin.math.abs(out[0].timeUs - 21 * 32_000L) < 1_000)
        for (i in 1 until out.size) assertEquals(32_000L, out[i].timeUs - out[i - 1].timeUs)
    }

    @Test fun dependentSubstreamsStayWithTheirIndependentFrame() {
        val rnd = Random(4)
        val units = List(60) { eac3(rnd, 0, 400) + eac3(rnd, 1, 200) }
        val out = mutableListOf<Out>()
        feed(framer(out), units.reduce { a, b -> a + b }, 700, 0, 32_000.0 / 600)
        // the last unit waits for a possible dependent substream that never comes
        assertEquals(59, out.size)
        out.forEachIndexed { i, o ->
            assertArrayEquals("unit $i", units[i], o.data)
            assertEquals(i * 32_000L, o.timeUs)
        }
    }

    @Test fun garbageBeforeTheFirstFrameIsDropped() {
        val rnd = Random(5)
        val frames = List(30) { ac3(rnd) }
        val junk = ByteArray(1000).also { rnd.nextBytes(it); it[100] = 0x0B; it[101] = 0x77 }
        val out = mutableListOf<Out>()
        feed(framer(out), junk + frames.reduce { a, b -> a + b }, 960, 0, 40_000.0 / 960)
        assertEquals(30, out.size)
        out.forEachIndexed { i, o -> assertArrayEquals(frames[i], o.data) }
    }
}

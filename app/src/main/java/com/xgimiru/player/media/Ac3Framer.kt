package com.xgimiru.player.media

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Ac3Util
import androidx.media3.extractor.TrackOutput
import java.io.EOFException

/**
 * Cuts an AC-3 / E-AC-3 byte stream into whole sync frames with timestamps taken from the frames themselves.
 *
 * AVI stores compressed audio in chunks cut by size, usually one chunk per video frame: at 25 fps and 192 kbit/s
 * a chunk holds one AC-3 frame and a quarter of the next. Media3 passes every chunk on as one sample, and a sink
 * that gets the audio compressed (passthrough, offload) counts 1536 samples per sample, so it sees 0.8 s of audio
 * per second of timestamps, reports an unexpected discontinuity and resyncs once a second: the picture jumps.
 * Decoders get partial frames too. Dependent E-AC-3 substreams stay with their independent frame (one access unit).
 */
class Ac3Framer(private val sampleRate: Int, private val emit: (data: ByteArray, offset: Int, size: Int, timeUs: Long) -> Unit) {
    private var buf = ByteArray(64 * 1024)
    private var len = 0
    private var locked = false
    private var anchorUs = C.TIME_UNSET
    private var anchorSamples = 0L
    private val header = ByteArray(HEADER)

    /** A seek or a new stream: nothing carries over. */
    fun reset() {
        len = 0
        locked = false
        anchorUs = C.TIME_UNSET
    }

    fun append(src: ByteArray, off: Int, n: Int) {
        if (len + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + n))
        System.arraycopy(src, off, buf, len, n)
        len += n
    }

    /**
     * A container sample is complete: it is the [size] bytes that end [tail] bytes before the end of what was
     * appended, and it starts at [timeUs]. Emits every whole frame now available; a part frame waits for more data.
     */
    fun chunk(timeUs: Long, size: Int, tail: Int) {
        val end = (len - tail).coerceIn(0, len)
        val chunkStart = end - size
        var pos = 0
        var group = -1          // independent frame waiting for possible dependent substreams
        var groupEnd = -1
        var groupSamples = 0
        while (pos + HEADER <= end) {
            if (!isSync(pos)) {
                locked = false
                pos++
                continue
            }
            val frame = frameSize(pos)
            if (frame < HEADER) { locked = false; pos++; continue }
            if (pos + frame > end) break
            if (!locked) {
                // a random 0B 77 inside audio data: trust it only when the next frame starts right after
                if (pos + frame + 2 > end) break
                if (!isSync(pos + frame)) { pos++; continue }
                locked = true
            }
            if (isEac3(pos) && streamType(pos) == 1) {
                if (group >= 0) groupEnd = pos + frame
            } else {
                if (group >= 0) send(group, groupEnd, groupSamples, timeUs, chunkStart)
                group = pos
                groupEnd = pos + frame
                groupSamples = samples(pos)
            }
            pos += frame
        }
        // E-AC-3 may still get a dependent substream for the last group; plain AC-3 never does
        if (group >= 0 && !isEac3(group)) { send(group, groupEnd, groupSamples, timeUs, chunkStart); group = -1 }
        val keep = (if (group >= 0) group else pos).coerceAtMost(len)
        System.arraycopy(buf, keep, buf, 0, len - keep)
        len -= keep
    }

    private fun send(start: Int, end: Int, samples: Int, chunkUs: Long, chunkStart: Int) {
        // where the container puts this access unit, assuming a constant bitrate around the chunk
        val guess = chunkUs + (start - chunkStart).toLong() * (samples * 1_000_000L / sampleRate) / (end - start)
        var at = if (anchorUs == C.TIME_UNSET) C.TIME_UNSET else anchorUs + anchorSamples * 1_000_000L / sampleRate
        if (at == C.TIME_UNSET || kotlin.math.abs(at - guess) > RESYNC_US) {
            anchorUs = guess
            anchorSamples = 0
            at = guess
        }
        emit(buf, start, end - start, at)
        anchorSamples += samples
    }

    private fun isSync(p: Int) = buf[p] == 0x0B.toByte() && buf[p + 1] == 0x77.toByte()
    private fun isEac3(p: Int) = ((buf[p + 5].toInt() and 0xF8) shr 3) > 10
    private fun streamType(p: Int) = (buf[p + 2].toInt() and 0xC0) shr 6

    private fun frameSize(p: Int): Int {
        System.arraycopy(buf, p, header, 0, HEADER)
        return Ac3Util.parseAc3SyncframeSize(header)
    }

    private fun samples(p: Int): Int {
        if (!isEac3(p)) return AC3_SAMPLES
        val b4 = buf[p + 4].toInt()
        val fscod = (b4 and 0xC0) shr 6
        val blocks = if (fscod == 3) 6 else BLOCKS[(b4 and 0x30) shr 4]
        return blocks * 256
    }

    companion object {
        private const val HEADER = 6
        private const val AC3_SAMPLES = 1536
        private val BLOCKS = intArrayOf(1, 2, 3, 6)
        /** Further from the container than this, the container wins (a broken stretch of the file). */
        private const val RESYNC_US = 500_000L

        fun handles(format: Format) =
            (format.sampleMimeType == MimeTypes.AUDIO_AC3 || format.sampleMimeType == MimeTypes.AUDIO_E_AC3) && format.sampleRate > 0
    }
}

/** An AVI audio track: AC-3 and E-AC-3 go out as whole frames, everything else untouched. */
@OptIn(UnstableApi::class)
class FramedAudioTrack(private val inner: TrackOutput) : TrackOutput {
    private var framer: Ac3Framer? = null
    private val scratch = ByteArray(32 * 1024)
    private val view = ParsableByteArray()

    fun reset() { framer?.reset() }

    override fun format(format: Format) {
        framer = if (Ac3Framer.handles(format)) Ac3Framer(format.sampleRate) { d, o, n, t ->
            view.reset(d, o + n)
            view.position = o
            inner.sampleData(view, n, TrackOutput.SAMPLE_DATA_PART_MAIN)
            inner.sampleMetadata(t, C.BUFFER_FLAG_KEY_FRAME, n, 0, null)
        } else null
        inner.format(format)
    }

    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
        val f = framer ?: return inner.sampleData(input, length, allowEndOfInput, sampleDataPart)
        val n = input.read(scratch, 0, minOf(length, scratch.size))
        if (n == C.RESULT_END_OF_INPUT) {
            if (allowEndOfInput) return C.RESULT_END_OF_INPUT
            throw EOFException()
        }
        f.append(scratch, 0, n)
        return n
    }

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        val f = framer ?: return inner.sampleData(data, length, sampleDataPart)
        f.append(data.data, data.position, length)
        data.skipBytes(length)
    }

    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
        val f = framer ?: return inner.sampleMetadata(timeUs, flags, size, offset, cryptoData)
        f.chunk(timeUs, size, offset)
    }
}

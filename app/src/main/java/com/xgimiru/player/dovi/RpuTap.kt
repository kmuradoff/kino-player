package com.xgimiru.player.dovi

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import java.util.TreeMap

/**
 * Метаданные Dolby Vision профиля 5 по времени кадра. Заполняется при чтении файла,
 * читается GL-рендером в момент показа кадра.
 */
class DoviStore {
    @Volatile var active = false          // в файле DV профиля 5 — включён наш рендер
        private set
    @Volatile var onActivated: (() -> Unit)? = null

    private val frames = TreeMap<Long, DoviFrame>()
    private val parser = DoviRpuParser()

    /** Новый файл — всё с нуля. */
    fun reset() {
        active = false
        clear()
    }

    fun activate() {
        if (active) return
        active = true
        onActivated?.invoke()
    }

    fun put(timeUs: Long, nal: ByteArray, off: Int, len: Int) {
        val f = parser.parse(nal, off, len) ?: return
        synchronized(frames) {
            frames[timeUs] = f
            // держим окно ~10 с вокруг текущего чтения, чтобы не расти бесконечно
            while (frames.size > 600) frames.pollFirstEntry()
        }
    }

    /** Метаданные ближайшего кадра (±40 мс). */
    fun get(timeUs: Long): DoviFrame? = synchronized(frames) {
        val lo = frames.floorEntry(timeUs); val hi = frames.ceilingEntry(timeUs)
        val best = listOfNotNull(lo, hi).minByOrNull { kotlin.math.abs(it.key - timeUs) } ?: return null
        if (kotlin.math.abs(best.key - timeUs) <= 40_000) best.value else lo?.value
    }

    fun clear() = synchronized(frames) { frames.clear() }
}

/** Фабрика извлекателей: обычные извлекатели Media3, обёрнутые перехватчиком RPU. */
@OptIn(UnstableApi::class)
class RpuTapExtractorsFactory(private val store: DoviStore) : ExtractorsFactory {
    private val base = DefaultExtractorsFactory()
    override fun createExtractors(): Array<Extractor> = base.createExtractors().map { TapExtractor(it, store) }.toTypedArray()
    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        base.createExtractors(uri, responseHeaders).map { TapExtractor(it, store) }.toTypedArray()
}

@OptIn(UnstableApi::class)
private class TapExtractor(private val inner: Extractor, private val store: DoviStore) : Extractor {
    override fun sniff(input: ExtractorInput) = inner.sniff(input)
    override fun init(output: ExtractorOutput) = inner.init(TapOutput(output, store))
    override fun read(input: ExtractorInput, seekPosition: PositionHolder) = inner.read(input, seekPosition)
    override fun seek(position: Long, timeUs: Long) = inner.seek(position, timeUs)
    override fun release() = inner.release()
    override fun getUnderlyingImplementation(): Extractor = inner.underlyingImplementation
}

@OptIn(UnstableApi::class)
private class TapOutput(private val inner: ExtractorOutput, private val store: DoviStore) : ExtractorOutput {
    override fun track(id: Int, type: Int): TrackOutput {
        val t = inner.track(id, type)
        return if (type == C.TRACK_TYPE_VIDEO) TapTrack(t, store) else t
    }
    override fun endTracks() = inner.endTracks()
    override fun seekMap(seekMap: SeekMap) = inner.seekMap(seekMap)
}

/**
 * Видеодорожка: если это Dolby Vision профиля 5, подменяем формат на обычный HEVC
 * (аппаратный декодер его распакует) и вынимаем из каждого кадра NAL 62 (RPU).
 */
@OptIn(UnstableApi::class)
private class TapTrack(private val inner: TrackOutput, private val store: DoviStore) : TrackOutput {
    private var dv5 = false
    /** Профиль 8/7: основной слой — обычный HDR10. Вырезаем RPU/EL, чтобы декодер MediaTek
     *  не включал свою обработку DV (из-за неё первые кадры фиолетовые). */
    private var strip = false
    private var stripSupplemental = false
    private var buf = ByteArray(1 shl 20)
    private var len = 0
    private val scratch = ByteArray(64 * 1024)

    override fun format(format: Format) {
        val codecs = format.codecs.orEmpty()
        dv5 = format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION &&
            (codecs.startsWith("dvhe.05") || codecs.startsWith("dvh1.05"))
        strip = !dv5 && format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION &&
            (codecs.startsWith("dvhe.08") || codecs.startsWith("dvh1.08") || codecs.startsWith("dvhe.07"))
        if (dv5) {
            store.activate()
            inner.format(format.buildUpon().setSampleMimeType(MimeTypes.VIDEO_H265).setCodecs(null).build())
        } else if (strip) {
            inner.format(format.buildUpon().setSampleMimeType(MimeTypes.VIDEO_H265).setCodecs(null).build())
        } else inner.format(format)
    }

    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
        if (!dv5 && !strip) return inner.sampleData(input, length, allowEndOfInput, sampleDataPart)
        if (strip && sampleDataPart != TrackOutput.SAMPLE_DATA_PART_MAIN) {
            stripSupplemental = true
            return inner.sampleData(input, length, allowEndOfInput, sampleDataPart)
        }
        val n = input.read(scratch, 0, minOf(length, scratch.size))
        if (n == C.RESULT_END_OF_INPUT) {
            if (allowEndOfInput) return C.RESULT_END_OF_INPUT
            throw java.io.EOFException()
        }
        append(scratch, 0, n)
        if (dv5) inner.sampleData(ParsableByteArray(scratch, n), n, sampleDataPart)
        return n
    }

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        if (strip && sampleDataPart == TrackOutput.SAMPLE_DATA_PART_MAIN) {
            append(data.data, data.position, length)
            data.skipBytes(length)
            return
        }
        if (dv5) append(data.data, data.position, length)
        inner.sampleData(data, length, sampleDataPart)
    }

    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
        if (strip) {
            // Кадр целиком у нас: вырезаем NAL 62/63 и отдаём дальше уже без них.
            val end = len - offset
            val start = (end - size).coerceAtLeast(0)
            val newEnd = if (stripSupplemental || cryptoData != null) end else stripDvInPlace(start, end)
            val n = newEnd - start
            sampleView.reset(buf, newEnd)
            sampleView.setPosition(start)
            inner.sampleData(sampleView, n, TrackOutput.SAMPLE_DATA_PART_MAIN)
            inner.sampleMetadata(timeUs, flags, n, 0, cryptoData)
            if (offset > 0) System.arraycopy(buf, end, buf, 0, offset)
            len = offset.coerceAtLeast(0)
            stripSupplemental = false
            return
        }
        if (dv5) {
            val end = len - offset
            val start = (end - size).coerceAtLeast(0)
            if (end > start) findRpu(start, end)?.let { (o, l) -> store.put(timeUs, buf, o, l) }
            // оставляем «хвост», который уже относится к следующему кадру
            if (offset > 0) System.arraycopy(buf, end, buf, 0, offset)
            len = offset.coerceAtLeast(0)
        }
        inner.sampleMetadata(timeUs, flags, size, offset, cryptoData)
    }

    private fun append(src: ByteArray, off: Int, n: Int) {
        if (len + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + n))
        System.arraycopy(src, off, buf, len, n)
        len += n
    }

    private val sampleView = ParsableByteArray()

    /**
     * Вырезает из кадра NAL 62 (RPU) и 63 (слой улучшения DV) прямо в буфере —
     * без новых массивов, чтобы не нагружать сборщик мусора на 4K-потоке.
     * @return новый конец кадра.
     */
    private fun stripDvInPlace(start: Int, end: Int): Int {
        var write = start
        var nalStart = start          // начало текущего NAL вместе со стартовым кодом
        var keep = true
        var i = start
        fun flush(to: Int) {
            if (keep && to > nalStart) {
                if (write != nalStart) System.arraycopy(buf, nalStart, buf, write, to - nalStart)
                write += to - nalStart
            }
        }
        while (i + 3 < end) {
            if (buf[i].toInt() == 0 && buf[i + 1].toInt() == 0 && buf[i + 2].toInt() == 1) {
                val sc = if (i > start && buf[i - 1].toInt() == 0) i - 1 else i   // 4-байтовый старт-код
                flush(sc)
                nalStart = sc
                val type = (buf[i + 3].toInt() shr 1) and 0x3F
                keep = type != 62 && type != 63
                i += 3
            } else i++
        }
        flush(end)
        return write
    }

    /**
     * NAL 62 (RPU) — обычно последний в кадре. Ищем стартовый код 00 00 01 + заголовок 7C 01
     * с конца кадра; возвращаем (начало, длина) полезной нагрузки после заголовка.
     */
    private fun findRpu(start: Int, end: Int): Pair<Int, Int>? {
        var i = end - 5
        while (i >= start) {
            if (buf[i].toInt() == 0 && buf[i + 1].toInt() == 0 && buf[i + 2].toInt() == 1 &&
                (buf[i + 3].toInt() and 0x7E) == 0x7C && buf[i + 4].toInt() == 1) {
                val payload = i + 5
                // до следующего стартового кода или конца кадра
                var j = payload
                while (j + 2 < end && !(buf[j].toInt() == 0 && buf[j + 1].toInt() == 0 && buf[j + 2].toInt() == 1)) j++
                val stop = if (j + 2 < end) j else end
                return payload to (stop - payload)
            }
            i--
        }
        return null
    }
}

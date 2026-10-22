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
    /** Как играет видеодорожка текущего файла (для строки сведений). */
    @Volatile var plan: HdrPolicy.Plan? = null
    @Volatile var onActivated: (() -> Unit)? = null

    private val frames = TreeMap<Long, DoviFrame>()
    private val parser = DoviRpuParser()

    /** Новый файл — всё с нуля. */
    fun reset() {
        active = false
        plan = null
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

/** Фабрика извлекателей: обычные извлекатели Media3, обёрнутые перехватчиком видеодорожки. */
@OptIn(UnstableApi::class)
class RpuTapExtractorsFactory(
    private val store: DoviStore,
    private val config: () -> Pair<HdrPolicy.Caps, HdrPolicy.Prefs>,
) : ExtractorsFactory {
    private val base = DefaultExtractorsFactory()
    override fun createExtractors(): Array<Extractor> = wrap(base.createExtractors())
    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        wrap(base.createExtractors(uri, responseHeaders))
    private fun wrap(a: Array<Extractor>): Array<Extractor> {
        val (caps, prefs) = config()
        return a.map { TapExtractor(it, store, caps, prefs) }.toTypedArray()
    }
}

@OptIn(UnstableApi::class)
private class TapExtractor(
    private val inner: Extractor, private val store: DoviStore,
    private val caps: HdrPolicy.Caps, private val prefs: HdrPolicy.Prefs,
) : Extractor {
    override fun sniff(input: ExtractorInput) = inner.sniff(input)
    override fun init(output: ExtractorOutput) = inner.init(TapOutput(output, store, caps, prefs))
    override fun read(input: ExtractorInput, seekPosition: PositionHolder) = inner.read(input, seekPosition)
    override fun seek(position: Long, timeUs: Long) = inner.seek(position, timeUs)
    override fun release() = inner.release()
    override fun getUnderlyingImplementation(): Extractor = inner.underlyingImplementation
}

@OptIn(UnstableApi::class)
private class TapOutput(
    private val inner: ExtractorOutput, private val store: DoviStore,
    private val caps: HdrPolicy.Caps, private val prefs: HdrPolicy.Prefs,
) : ExtractorOutput {
    override fun track(id: Int, type: Int): TrackOutput {
        val t = inner.track(id, type)
        return if (type == C.TRACK_TYPE_VIDEO) TapTrack(t, store, caps, prefs) else t
    }
    override fun endTracks() = inner.endTracks()
    override fun seekMap(seekMap: SeekMap) = inner.seekMap(seekMap)
}

/**
 * Видеодорожка. Путь выбирает [HdrPolicy] (как в Kodi):
 *  - DV профиля 5 без аппаратного DV: формат → обычный HEVC, RPU из каждого кадра — нашему шейдеру;
 *  - DV 7/8 без аппаратного DV: вырезаем RPU и слой улучшения, играет HDR10-основа
 *    (иначе декодер MediaTek включает свою обработку DV, и первые кадры фиолетовые);
 *  - аппаратный DV: RPU правим (7 → 8.1, L5), слой улучшения убираем при переводе в 8.1;
 *  - по желанию убираем SEI с HDR10+.
 */
@OptIn(UnstableApi::class)
private class TapTrack(
    private val inner: TrackOutput, private val store: DoviStore,
    private val caps: HdrPolicy.Caps, private val prefs: HdrPolicy.Prefs,
) : TrackOutput {
    private var plan = HdrPolicy.Plan(HdrPolicy.Route.PASS)
    private var editor: RpuEditor? = null
    private val dv5 get() = plan.route == HdrPolicy.Route.SOFT5
    /** Кадр собирается у нас целиком и уходит дальше переписанным. */
    private val edit get() = plan.route == HdrPolicy.Route.STRIP || plan.route == HdrPolicy.Route.DV_EDIT ||
        plan.route == HdrPolicy.Route.HEVC_EDIT
    private var supplemental = false
    private var buf = ByteArray(1 shl 20)
    private var len = 0
    private var out = ByteArray(1 shl 20)
    private val scratch = ByteArray(64 * 1024)

    override fun format(format: Format) {
        plan = HdrPolicy.plan(format.sampleMimeType, format.codecs, caps, prefs)
        if (plan.profile != 0) store.plan = plan
        editor = if (plan.route == HdrPolicy.Route.DV_EDIT) RpuEditor(plan.toP81, plan.zeroL5) else null
        when (plan.route) {
            HdrPolicy.Route.SOFT5 -> {
                store.activate()
                inner.format(format.buildUpon().setSampleMimeType(MimeTypes.VIDEO_H265).setCodecs(null).build())
            }
            HdrPolicy.Route.STRIP ->
                inner.format(format.buildUpon().setSampleMimeType(MimeTypes.VIDEO_H265).setCodecs(null).build())
            HdrPolicy.Route.DV_EDIT ->
                inner.format(plan.codecs?.let { format.buildUpon().setCodecs(it).build() } ?: format)
            else -> inner.format(format)
        }
    }

    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
        if (!dv5 && !edit) return inner.sampleData(input, length, allowEndOfInput, sampleDataPart)
        if (edit && sampleDataPart != TrackOutput.SAMPLE_DATA_PART_MAIN) {
            supplemental = true
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
        if (edit && sampleDataPart == TrackOutput.SAMPLE_DATA_PART_MAIN) {
            append(data.data, data.position, length)
            data.skipBytes(length)
            return
        }
        if (dv5) append(data.data, data.position, length)
        inner.sampleData(data, length, sampleDataPart)
    }

    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
        if (edit) {
            // Кадр целиком у нас: переписываем и отдаём дальше.
            val end = len - offset
            val start = (end - size).coerceAtLeast(0)
            if (supplemental || cryptoData != null) {
                sampleView.reset(buf, end); sampleView.setPosition(start)
                inner.sampleData(sampleView, end - start, TrackOutput.SAMPLE_DATA_PART_MAIN)
                inner.sampleMetadata(timeUs, flags, end - start, 0, cryptoData)
            } else {
                val n = rewrite(start, end)
                sampleView.reset(out, n); sampleView.setPosition(0)
                inner.sampleData(sampleView, n, TrackOutput.SAMPLE_DATA_PART_MAIN)
                inner.sampleMetadata(timeUs, flags, n, 0, null)
            }
            if (offset > 0) System.arraycopy(buf, end, buf, 0, offset)
            len = offset.coerceAtLeast(0)
            supplemental = false
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
    private var outLen = 0

    private fun put(src: ByteArray, off: Int, n: Int) {
        if (outLen + n > out.size) out = out.copyOf(maxOf(out.size * 2, outLen + n))
        System.arraycopy(src, off, out, outLen, n)
        outLen += n
    }

    /**
     * Переписывает кадр buf[start, end) в [out] по NAL-блокам (со стартовыми кодами):
     * 62 (RPU) — вырезать или править, 63 (слой улучшения DV) — вырезать, 39 с HDR10+ — вырезать.
     * Остальное копируется как есть. @return длина кадра в [out].
     */
    private fun rewrite(start: Int, end: Int): Int {
        outLen = 0
        val strip = plan.route == HdrPolicy.Route.STRIP
        var i = start
        var nalStart = -1
        var bodyStart = 0
        fun flush(to: Int) {
            if (nalStart < 0) { if (to > start) put(buf, start, to - start); return }
            val type = if (bodyStart < to) (buf[bodyStart].toInt() shr 1) and 0x3F else -1
            when {
                type == 62 && strip -> Unit
                type == 62 && editor != null -> {
                    val e = editor!!
                    val edited = e.edit(buf, bodyStart + 2, to - bodyStart - 2)
                    when {
                        edited != null -> { put(buf, nalStart, bodyStart + 2 - nalStart); put(edited, 0, edited.size) }
                        plan.toP81 -> Unit                       // RPU 7 не перевести — лучше без него
                        else -> put(buf, nalStart, to - nalStart)
                    }
                }
                type == 63 && (strip || plan.toP81) -> Unit
                type == 39 && plan.dropHdr10Plus && Nal.isHdr10PlusSei(buf, bodyStart + 2, to) -> Unit
                else -> put(buf, nalStart, to - nalStart)
            }
        }
        while (i + 3 < end) {
            if (buf[i].toInt() == 0 && buf[i + 1].toInt() == 0 && buf[i + 2].toInt() == 1) {
                val sc = if (i > start && buf[i - 1].toInt() == 0) i - 1 else i   // 4-байтовый старт-код
                flush(sc)
                nalStart = sc
                bodyStart = i + 3
                i += 3
            } else i++
        }
        flush(end)
        return outLen
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

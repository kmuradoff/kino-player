package com.xgimiru.player.dovi  // SPDX-License-Identifier: Apache-2.0

/**
 * Правка RPU Dolby Vision (NAL 62) так же, как это делает Kodi через libdovi:
 *  - профиль 7 → 8.1 (режим 2 libdovi): выкидываем NLQ и слой улучшения, кривые перестройки
 *    становятся тождественными, цветовые матрицы DM — стандартные для 8.1;
 *  - обнуление L5 (поля кадра): некоторые экраны по L5 обрезают или масштабируют картинку.
 *
 * RPU переписывается побитно: всё, что не меняем, копируется как было, поэтому без правок
 * на выходе ровно исходные байты (это проверяет тест на настоящих RPU). Новая CRC32 считается заново.
 * Состояние заголовка хранится между RPU: в RPU без vdr_seq_info действуют прошлые параметры.
 */
class RpuEditor(private val toP81: Boolean, private val zeroL5: Boolean) {

    private var coefType = 0
    private var denom = 23
    private var blDepth = 10
    private var elDepth = 10
    private var residualIn = false   // во входном потоке есть NLQ (профиль 7)

    /** L5 последнего RPU (левое, правое, верхнее, нижнее поле) до правки, если был. */
    var l5: IntArray? = null
        private set

    /**
     * [nal] — полезная нагрузка NAL 62 после 2-байтового заголовка, с эмуляционными байтами.
     * @return новая полезная нагрузка (тоже с эмуляционными байтами) или null, если RPU не разобрать.
     */
    fun edit(nal: ByteArray, off: Int, len: Int): ByteArray? =
        runCatching { editRaw(Nal.unescape(nal, off, len)) }.getOrNull()?.let { Nal.escape(it) }

    private fun editRaw(raw: ByteArray): ByteArray? {
        var size = raw.size
        while (size > 0 && raw[size - 1].toInt() == 0) size--
        if (size < 7 || (raw[0].toInt() and 0xFF) != 25 || (raw[size - 1].toInt() and 0xFF) != 0x80) return null
        val crcAt = size - 5
        if (Nal.crc32(raw, 1, crcAt) != Nal.u32(raw, crcAt)) return null

        val r = BitReader(raw, 1, crcAt)
        val w = BitWriter()
        w.bits(25, 8)
        l5 = null
        fun cp(n: Int) = w.bitsLong(r.bitsLong(n), n)
        fun cpUe() = r.ueLong().also { w.ue(it) }

        if (r.bits(6).also { w.bits(it, 6) } != 2) return null       // rpu_type
        val format = r.bits(11).also { w.bits(it, 11) }
        if (format and 0x700 != 0) return null
        cp(4); cp(4)                                                   // vdr_rpu_profile, level
        val seqInfo = r.bit().also { w.bit(it) }
        if (seqInfo) {
            cp(1)                                                      // chroma_resampling_explicit_filter_flag
            coefType = r.bits(2).also { w.bits(it, 2) }
            if (coefType == 0) denom = cpUe().toInt() else denom = 32
            cp(2); cp(1)                                               // normalized_idc, bl_full_range
            blDepth = cpUe().toInt() + 8
            elDepth = (cpUe().toInt() and 0xFF) + 8                    // старшие биты — ext_mapping_idc
            cpUe()                                                     // vdr_bit_depth_minus8
            cp(1); cp(3)                                               // spatial_resampling, reserved
            val elSpatial = r.bit()
            val disableResidual = r.bit()
            residualIn = !disableResidual
            if (toP81 && residualIn) { w.bit(false); w.bit(true) } else { w.bit(elSpatial); w.bit(disableResidual) }
        }
        val convert = toP81 && residualIn
        val dmPresent = r.bit().also { w.bit(it) }
        val usePrev = r.bit().also { w.bit(it) }

        if (usePrev) cpUe()
        else {
            cpUe(); cpUe(); cpUe()                                     // vdr_rpu_id, mapping_color_space, chroma_format
            val nPiv = IntArray(3)
            val piv = Array(3) { IntArray(9) }
            for (c in 0 until 3) {
                nPiv[c] = r.ue() + 2
                if (nPiv[c] > 9) return null
                for (i in 0 until nPiv[c]) piv[c][i] = r.bits(blDepth)
            }
            var nlqMethod = 0
            var nlqPiv0 = 0; var nlqPiv1 = 0
            if (residualIn) {
                nlqMethod = r.bits(3)
                if (nlqMethod != 0) return null                        // известен только linear dead zone
                nlqPiv0 = r.bits(blDepth); nlqPiv1 = r.bits(blDepth)
            }
            val nx = r.ueLong(); val ny = r.ueLong()
            if (nx != 0L || ny != 0L) return null                      // разбиение кадра на части не встречается

            if (convert) {
                for (c in 0 until 3) { w.ue(0); w.bits(0, blDepth); w.bits((1 shl blDepth) - 1, blDepth) }
            } else {
                for (c in 0 until 3) { w.ue((nPiv[c] - 2).toLong()); for (i in 0 until nPiv[c]) w.bits(piv[c][i], blDepth) }
                if (residualIn) { w.bits(nlqMethod, 3); w.bits(nlqPiv0, blDepth); w.bits(nlqPiv1, blDepth) }
            }
            w.ue(nx); w.ue(ny)

            // кривые: при переводе в 8.1 читаем и выбрасываем, вместо них y = x
            val out = if (convert) null else w
            for (c in 0 until 3) for (i in 0 until nPiv[c] - 1) {
                val idc = r.ueLong(); out?.ue(idc)
                when (idc) {
                    0L -> {
                        val order = r.ueLong(); out?.ue(order)
                        if (order == 0L) { val lin = r.bit(); out?.bit(lin); if (lin) return null }
                        repeat(order.toInt() + 2) { seCoef(r, out) }
                    }
                    1L -> {
                        val order = r.bits(2); out?.bits(order, 2)
                        seCoef(r, out)
                        repeat((order + 1) * 7) { seCoef(r, out) }
                    }
                    else -> return null
                }
            }
            if (convert) for (c in 0 until 3) {
                w.ue(0); w.ue(0); w.bit(false)                         // полином 1-й степени
                writeCoef(w, 0, 0.0f); writeCoef(w, 1, 1.0f)
            }
            if (residualIn) for (c in 0 until 3) {
                val o = if (convert) null else w
                val nlqOffset = r.bits(elDepth); o?.bits(nlqOffset, elDepth)
                repeat(3) { ueCoef(r, o) }                              // vdr_in_max, slope, threshold
            }
        }

        if (dmPresent) {
            cpUe(); cpUe(); cpUe()                                     // affected/current id, scene_refresh
            if (convert) {
                r.skip(9 * 16 + 3 * 32 + 9 * 16)
                for (v in P81_YCC) w.bits(v and 0xFFFF, 16)
                for (v in P81_OFFSET) w.bitsLong(v, 32)
                for (v in P81_LMS) w.bits(v and 0xFFFF, 16)
            } else repeat(9 + 3 + 9) { cp(if (it in 9..11) 32 else 16) }
            cp(16); cp(16); cp(16); cp(32)                             // signal_eotf*
            cp(5)                                                      // signal_bit_depth
            if (convert) { r.skip(2); w.bits(0, 2) } else cp(2)        // signal_color_space: YCbCr
            cp(2); cp(2)                                               // chroma format, full range
            cp(12); cp(12); cp(10)                                     // source min/max PQ, diagonal
            copyExtBlocks(r, w)                                        // CM v2.9
            if (r.left() >= 8) copyExtBlocks(r, w)                     // CM v4.0
        }
        if (r.left() >= 8) return null                                 // что-то неизвестное — не трогаем
        w.alignZero()
        val body = w.toByteArray()
        val crc = Nal.crc32(body, 1, body.size)
        return body + byteArrayOf((crc ushr 24).toByte(), (crc ushr 16).toByte(), (crc ushr 8).toByte(), crc.toByte(), 0x80.toByte())
    }

    private fun copyExtBlocks(r: BitReader, w: BitWriter) {
        val n = r.ueLong(); w.ue(n)
        if (n > 0) { r.align(); w.alignZero() }
        repeat(n.toInt()) {
            val len = r.ueLong(); w.ue(len)
            val level = r.bits(8); w.bits(level, 8)
            var bits = len * 8
            if (level == 5 && bits >= 52) {                            // 4 смещения по 13 бит
                val v = IntArray(4) { r.bits(13) }
                l5 = v
                for (x in v) w.bits(if (zeroL5) 0 else x, 13)
                bits -= 52
            }
            while (bits > 0) { val k = minOf(bits, 32L).toInt(); w.bitsLong(r.bitsLong(k), k); bits -= k }
        }
    }

    /** Коэффициент со знаком (кривые): se(v) + дробь, либо float32. */
    private fun seCoef(r: BitReader, w: BitWriter?) {
        if (coefType == 0) { val i = r.seLong(); w?.se(i); val f = r.bitsLong(denom); w?.bitsLong(f, denom) }
        else { val f = r.bitsLong(32); w?.bitsLong(f, 32) }
    }

    /** Коэффициент без знака (NLQ): ue(v) + дробь, либо float32. */
    private fun ueCoef(r: BitReader, w: BitWriter?) {
        if (coefType == 0) { val i = r.ueLong(); w?.ue(i); val f = r.bitsLong(denom); w?.bitsLong(f, denom) }
        else { val f = r.bitsLong(32); w?.bitsLong(f, 32) }
    }

    private fun writeCoef(w: BitWriter, int: Int, float: Float) {
        if (coefType == 0) { w.se(int.toLong()); w.bitsLong(0, denom) }
        else w.bitsLong(java.lang.Float.floatToIntBits(float).toLong() and 0xFFFFFFFFL, 32)
    }

    companion object {
        // Стандартные матрицы профиля 8.1 (BT.2020, PQ), те же, что ставит libdovi
        val P81_YCC = intArrayOf(9574, 0, 13802, 9574, -1540, -5348, 9574, 17610, 0)
        val P81_OFFSET = longArrayOf(16777216, 134217728, 134217728)
        val P81_LMS = intArrayOf(7222, 8771, 390, 2654, 12430, 1300, 0, 422, 15962)
    }
}

/** Мелочи NAL: эмуляционные байты, CRC32 (MPEG-2), HDR10+ в SEI. */
object Nal {
    fun unescape(src: ByteArray, off: Int, len: Int): ByteArray {
        val out = ByteArray(len)
        var n = 0; var zeros = 0
        for (i in off until off + len) {
            val v = src[i].toInt() and 0xFF
            if (zeros >= 2 && v == 3) { zeros = 0; continue }
            out[n++] = v.toByte()
            zeros = if (v == 0) zeros + 1 else 0
        }
        return out.copyOf(n)
    }

    fun escape(src: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(src.size + 16)
        var zeros = 0
        for (b in src) {
            val v = b.toInt() and 0xFF
            if (zeros >= 2 && v <= 3) { out.write(3); zeros = 0 }
            out.write(v)
            zeros = if (v == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    private val CRC = IntArray(256).also { t ->
        for (i in 0 until 256) {
            var c = i shl 24
            repeat(8) { c = if (c and 0x80000000.toInt() != 0) (c shl 1) xor 0x04C11DB7 else c shl 1 }
            t[i] = c
        }
    }

    /** CRC-32/MPEG-2 по байтам [from, to). */
    fun crc32(d: ByteArray, from: Int, to: Int): Int {
        var c = -1
        for (i in from until to) c = (c shl 8) xor CRC[((c ushr 24) xor (d[i].toInt() and 0xFF)) and 0xFF]
        return c
    }

    fun u32(d: ByteArray, at: Int): Int =
        ((d[at].toInt() and 0xFF) shl 24) or ((d[at + 1].toInt() and 0xFF) shl 16) or
            ((d[at + 2].toInt() and 0xFF) shl 8) or (d[at + 3].toInt() and 0xFF)

    /**
     * SEI (NAL 39, [off] — сразу после 2-байтового заголовка) с динамическими метаданными HDR10+:
     * user_data_registered_itu_t_t35, страна B5, провайдер 003C, код 0001, приложение 4.
     */
    fun isHdr10PlusSei(d: ByteArray, off: Int, end: Int): Boolean {
        var i = off
        var type = 0
        while (i < end && d[i].toInt() and 0xFF == 0xFF) { type += 255; i++ }
        if (i >= end) return false
        type += d[i++].toInt() and 0xFF
        if (type != 4) return false
        while (i < end && d[i].toInt() and 0xFF == 0xFF) i++
        i++                                                            // последний байт размера
        if (i + 6 > end) return false
        return d[i].toInt() and 0xFF == 0xB5 && d[i + 1].toInt() == 0 && d[i + 2].toInt() == 0x3C &&
            d[i + 3].toInt() == 0 && d[i + 4].toInt() == 1 && d[i + 5].toInt() == 4
    }
}

/** Чтение битов (MSB first) до [end] байта. */
internal class BitReader(private val d: ByteArray, start: Int, end: Int) {
    private var pos = start * 8
    private val limit = end * 8
    fun left() = limit - pos
    fun bit(): Boolean {
        if (pos >= limit) throw IndexOutOfBoundsException()
        val v = (d[pos shr 3].toInt() shr (7 - (pos and 7))) and 1
        pos++
        return v == 1
    }
    fun bits(n: Int): Int = bitsLong(n).toInt()
    fun bitsLong(n: Int): Long { var v = 0L; repeat(n) { v = (v shl 1) or (if (bit()) 1L else 0L) }; return v }
    fun ueLong(): Long {
        var zeros = 0
        while (!bit()) { zeros++; if (zeros > 62) throw IllegalStateException() }
        return (1L shl zeros) - 1 + bitsLong(zeros)
    }
    fun ue(): Int = ueLong().toInt()
    fun seLong(): Long { val k = ueLong(); return if (k and 1L == 1L) (k + 1) / 2 else -(k / 2) }
    fun align() { pos = (pos + 7) and 7.inv() }
    fun skip(n: Int) { if (pos + n > limit) throw IndexOutOfBoundsException(); pos += n }
}

/** Запись битов (MSB first). */
internal class BitWriter {
    private var buf = ByteArray(512)
    private var pos = 0
    fun bit(b: Boolean) {
        if ((pos shr 3) >= buf.size) buf = buf.copyOf(buf.size * 2)
        if (b) buf[pos shr 3] = (buf[pos shr 3].toInt() or (0x80 ushr (pos and 7))).toByte()
        pos++
    }
    fun bits(v: Int, n: Int) = bitsLong(v.toLong(), n)
    fun bitsLong(v: Long, n: Int) { for (i in n - 1 downTo 0) bit((v ushr i) and 1L == 1L) }
    fun ue(v: Long) {
        val x = v + 1
        val len = 63 - java.lang.Long.numberOfLeadingZeros(x)
        repeat(len) { bit(false) }
        bitsLong(x, len + 1)
    }
    fun se(v: Long) = ue(if (v > 0) 2 * v - 1 else -2 * v)
    fun alignZero() { while (pos and 7 != 0) bit(false) }
    fun toByteArray(): ByteArray = buf.copyOf((pos + 7) shr 3)
}

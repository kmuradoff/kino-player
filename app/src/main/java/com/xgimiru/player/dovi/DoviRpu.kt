package com.xgimiru.player.dovi  // SPDX-License-Identifier: LGPL-2.1-or-later. Ported from FFmpeg (ff_dovi_rpu_parse) in 2026, modified; see NOTICE.

/**
 * Метаданные Dolby Vision одного кадра, уже приведённые к виду «для шейдера»
 * (как pl_dovi_metadata в libplacebo): кривые перестройки по компонентам и матрицы.
 */
class DoviFrame(
    /** Для каждого из 3 компонентов. */
    val numPivots: IntArray,                 // 2…9
    val pivots: Array<FloatArray>,           // [3][9], нормированы в 0…1
    val method: Array<IntArray>,             // [3][8]: 0 — полином, 1 — MMR
    val poly: Array<Array<FloatArray>>,      // [3][8][3]
    val mmrOrder: Array<IntArray>,           // [3][8]
    val mmrConst: Array<FloatArray>,         // [3][8]
    val mmr: Array<Array<Array<FloatArray>>>,// [3][8][3][7]
    val nonlinearOffset: FloatArray,         // [3]
    val nonlinear: FloatArray,               // 3×3, по строкам: ycc → L'M'S' (PQ)
    val linear: FloatArray,                  // 3×3, по строкам: LMS (RPU)
    val sourceMaxPq: Int,                    // 0…4095
    val sourceMinPq: Int,
    val l1MaxPq: Int,                        // -1 — нет L1
)

/**
 * Разбор RPU Dolby Vision (NAL 62 в HEVC). Порт логики ff_dovi_rpu_parse из FFmpeg
 * (LGPL-2.1): заголовок, кривые (полиномы/MMR), цветовые матрицы DM, блок L1.
 * Состояние между кадрами хранится: RPU могут ссылаться на предыдущие (use_prev_vdr_rpu).
 */
class DoviRpuParser {

    private class Mapping {
        val numPivots = IntArray(3)
        val pivots = Array(3) { IntArray(9) }
        val method = Array(3) { IntArray(8) }
        val polyOrder = Array(3) { IntArray(8) }
        val poly = Array(3) { Array(8) { LongArray(3) } }
        val mmrOrder = Array(3) { IntArray(8) }
        val mmrConst = Array(3) { LongArray(8) }
        val mmr = Array(3) { Array(8) { Array(3) { LongArray(7) } } }
    }

    private class Color {
        val yccToRgb = IntArray(9) { DEFAULT_YCC[it] }   // /8192
        val offset = LongArray(3)                         // /offsetDenom
        var offsetDenom = LongArray(3) { 1L shl 28 }
        val rgbToLms = IntArray(9) { DEFAULT_LMS[it] }    // /16384
        var sourceMinPq = 0
        var sourceMaxPq = 3079
        var l1MaxPq = -1
    }

    private val vdr = arrayOfNulls<Mapping>(16)
    private var color = Color()
    private var blBitDepth = 10
    private var coefLog2Denom = 23
    private var coefFloat = false

    /** [nal] — полезная нагрузка NAL 62 без 2-байтового заголовка, с эмуляционными байтами. */
    fun parse(nal: ByteArray, offset: Int, length: Int): DoviFrame? = runCatching {
        val rpu = unescape(nal, offset, length) ?: return null
        parseRpu(rpu)
    }.getOrNull()

    private fun parseRpu(raw: ByteArray): DoviFrame? {
        if (raw.size < 5 || (raw[0].toInt() and 0xFF) != 25) return null   // префикс NAL RPU
        var size = raw.size
        while (size > 1 && raw[size - 1].toInt() == 0) size--
        val b = Bits(raw, 1, size)

        val rpuType = b.bits(6)
        if (rpuType != 2) return null
        val rpuFormat = b.bits(11)
        b.bits(4); b.bits(4)                       // vdr_rpu_profile, vdr_rpu_level
        val seqInfo = b.bit()
        var dmCompression = 0
        var disableResidual = true
        if (seqInfo) {
            b.bit()                                 // chroma_resampling_explicit_filter_flag
            val coefType = b.bits(2)
            coefFloat = coefType == 1
            coefLog2Denom = if (coefType == 0) b.ue() else 32
            b.bits(2)                               // vdr_rpu_normalized_idc
            b.bit()                                 // bl_video_full_range_flag
            if (rpuFormat and 0x700 == 0) {
                blBitDepth = b.ue() + 8
                b.ue()                              // el_bit_depth_minus8 (+ ext_mapping_idc)
                b.ue()                              // vdr_bit_depth_minus8
                b.bit()                             // spatial_resampling_filter_flag
                dmCompression = b.bits(3)
                b.bit()                             // el_spatial_resampling_filter_flag
                disableResidual = b.bit()
            } else return null
        }
        val dmPresent = b.bit()
        if (dmCompression > 0) return null         // сжатие метаданных — не встречается в профиле 5
        val usePrev = b.bit()
        val useNlq = (rpuFormat and 0x700) == 0 && !disableResidual

        val mapping: Mapping
        if (usePrev) {
            val id = b.ue().coerceIn(0, 15)
            mapping = vdr[id] ?: vdr[0] ?: return null
        } else {
            val id = b.ue().coerceIn(0, 15)
            mapping = vdr[id] ?: Mapping().also { vdr[id] = it }
            b.ue(); b.ue()                          // mapping_color_space, mapping_chroma_format_idc
            for (c in 0 until 3) {
                val n = (b.ue() + 2).coerceAtMost(9)
                mapping.numPivots[c] = n
                var pivot = 0
                for (i in 0 until n) {
                    pivot += b.bits(blBitDepth)
                    mapping.pivots[c][i] = pivot.coerceAtMost(0xFFFF)
                }
            }
            if (useNlq) {
                b.bits(3)
                repeat(2) { b.bits(blBitDepth) }
            }
            b.ueLong(); b.ueLong()                  // num_x/y_partitions
            for (c in 0 until 3) {
                for (i in 0 until mapping.numPivots[c] - 1) {
                    val idc = b.ue()
                    mapping.method[c][i] = idc
                    if (idc == 0) {
                        val order = b.ue() + 1
                        mapping.polyOrder[c][i] = order
                        if (order == 1 && b.bit()) return null   // linear_interp — нет примеров
                        for (k in 0..order) mapping.poly[c][i][k] = seCoef(b)
                        for (k in order + 1 until 3) mapping.poly[c][i][k] = 0
                    } else {
                        val order = b.bits(2) + 1
                        mapping.mmrOrder[c][i] = order
                        mapping.mmrConst[c][i] = seCoef(b)
                        for (j in 0 until order) for (k in 0 until 7) mapping.mmr[c][i][j][k] = seCoef(b)
                    }
                }
            }
            if (useNlq) return null                 // профиль 5 без NLQ; двухслойные не поддерживаем
        }

        if (dmPresent) {
            val affected = b.ue(); b.ue()
            val col = color
            b.ue()                                  // scene_refresh_flag
            for (i in 0 until 9) col.yccToRgb[i] = b.sbits(16)
            for (i in 0 until 3) {
                var off = b.bitsLong(32)
                var den = 1L shl 28
                if (off > Int.MAX_VALUE) { off = off shr 1; den = den shr 1 }
                col.offset[i] = off; col.offsetDenom[i] = den
            }
            for (i in 0 until 9) col.rgbToLms[i] = b.sbits(16)
            b.bits(16); b.bits(16); b.bits(16); b.bitsLong(32)   // signal_eotf*
            b.bits(5); b.bits(2); b.bits(2); b.bits(2)           // bit depth, color space, chroma, range
            col.sourceMinPq = b.bits(12)
            col.sourceMaxPq = b.bits(12)
            b.bits(10)                              // source_diagonal
            col.l1MaxPq = -1
            runCatching { parseExtBlocks(b, col) }   // блоки расширений не критичны
            if (affected < 0) return null
        }

        return build(mapping, color)
    }

    /** Блоки расширений v1: нужен только L1 (яркость сцены) — для тон-маппинга. */
    private fun parseExtBlocks(b: Bits, col: Color) {
        val num = b.ue()
        b.align()
        repeat(num) {
            val len = b.ue()
            val level = b.bits(8)
            val start = b.pos
            if (level == 1 && len >= 5) {
                b.bits(12)                          // min_pq
                col.l1MaxPq = b.bits(12)
                b.bits(12)                          // avg_pq
            }
            b.skip(len * 8 - (b.pos - start))
        }
    }

    private fun build(m: Mapping, col: Color): DoviFrame {
        val pivScale = 1f / ((1 shl blBitDepth) - 1)
        val coef = 1.0 / (1L shl coefLog2Denom.coerceAtMost(62)).toDouble()
        return DoviFrame(
            numPivots = m.numPivots.copyOf(),
            pivots = Array(3) { c -> FloatArray(9) { i -> m.pivots[c][i] * pivScale } },
            method = Array(3) { c -> m.method[c].copyOf() },
            poly = Array(3) { c -> Array(8) { i -> FloatArray(3) { k -> (m.poly[c][i][k] * coef).toFloat() } } },
            mmrOrder = Array(3) { c -> m.mmrOrder[c].copyOf() },
            mmrConst = Array(3) { c -> FloatArray(8) { i -> (m.mmrConst[c][i] * coef).toFloat() } },
            mmr = Array(3) { c -> Array(8) { i -> Array(3) { j -> FloatArray(7) { k -> (m.mmr[c][i][j][k] * coef).toFloat() } } } },
            nonlinearOffset = FloatArray(3) { (col.offset[it].toDouble() / col.offsetDenom[it]).toFloat() },
            nonlinear = FloatArray(9) { col.yccToRgb[it] / 8192f },
            linear = FloatArray(9) { col.rgbToLms[it] / 16384f },
            sourceMaxPq = col.sourceMaxPq,
            sourceMinPq = col.sourceMinPq,
            l1MaxPq = col.l1MaxPq,
        )
    }

    /** Коэффициент со знаком: целая часть se(v) + дробная (coef_log2_denom бит) или float32. */
    private fun seCoef(b: Bits): Long {
        if (coefFloat) {
            val f = java.lang.Float.intBitsToFloat(b.bitsLong(32).toInt())
            return (f * (1L shl 32).toDouble()).toLong()
        }
        val ipart = b.seLong()
        val fpart = b.bitsLong(coefLog2Denom)
        return (ipart shl coefLog2Denom) or fpart
    }

    /** Убираем эмуляционные байты 00 00 03. */
    private fun unescape(src: ByteArray, off: Int, len: Int): ByteArray? {
        if (len <= 0) return null
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

    /** Чтение битов (MSB first) + экспоненциальный код Голомба. */
    private class Bits(private val d: ByteArray, start: Int, private val end: Int) {
        var pos = start * 8
            private set
        private val limit = end * 8

        fun bit(): Boolean {
            if (pos >= limit) throw IndexOutOfBoundsException()
            val v = (d[pos shr 3].toInt() shr (7 - (pos and 7))) and 1
            pos++
            return v == 1
        }
        fun bits(n: Int): Int { var v = 0; repeat(n) { v = (v shl 1) or (if (bit()) 1 else 0) }; return v }
        fun bitsLong(n: Int): Long { var v = 0L; repeat(n) { v = (v shl 1) or (if (bit()) 1L else 0L) }; return v }
        fun sbits(n: Int): Int { val v = bits(n); return if (v and (1 shl (n - 1)) != 0) v - (1 shl n) else v }
        fun ueLong(): Long {
            var zeros = 0
            while (!bit()) { zeros++; if (zeros > 62) throw IllegalStateException() }
            return (1L shl zeros) - 1 + bitsLong(zeros)
        }
        fun ue(): Int = ueLong().toInt()
        fun seLong(): Long { val k = ueLong(); return if (k and 1L == 1L) (k + 1) / 2 else -(k / 2) }
        fun align() { pos = (pos + 7) and 7.inv() }
        fun skip(n: Int) { if (n > 0) pos += n }
    }

    companion object {
        // ff_dovi_color_default
        private val DEFAULT_YCC = intArrayOf(9575, 0, 14742, 9575, 1754, 4383, 9575, 17372, 0)
        private val DEFAULT_LMS = intArrayOf(5845, 9702, 837, 2568, 12256, 1561, 0, 679, 16705)
    }
}

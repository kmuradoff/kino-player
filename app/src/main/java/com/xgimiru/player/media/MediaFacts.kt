package com.xgimiru.player.media

import java.util.Locale

/** What a video file holds: size of the picture, HDR kind, sound and subtitle languages. */
data class MediaFacts(
    val durationMs: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    /** "HDR10", "HLG" or null. */
    val hdr: String? = null,
    val dolbyVision: Boolean = false,
    val atmos: Boolean = false,
    /** Language codes of the sound tracks and subtitle tracks, in file order ("und" when unknown). */
    val audio: List<String> = emptyList(),
    val subs: List<String> = emptyList(),
    /** Only the quick probe ran (no tracks yet). */
    val quick: Boolean = true,
)

/**
 * Labels of the folder list, the file card and the player panel. Pure code, host tested.
 */
object Badges {
    private val Q4K = Regex("(?i)\\b(2160p|4k|uhd)\\b")
    private val Q1080 = Regex("(?i)\\b1080[pi]\\b")
    private val Q720 = Regex("(?i)\\b720p\\b")
    private val HDR = Regex("(?i)\\b(hdr10\\+?|hdr|dv|dovi|dolby\\s?vision|hlg)\\b")

    fun quality(width: Int, height: Int): String? = when {
        width <= 0 && height <= 0 -> null
        width >= 3200 || height >= 1600 -> "4K"
        width >= 1700 || height >= 1000 -> "1080p"
        width >= 1200 || height >= 700 -> "720p"
        else -> "SD"
    }

    /** Quality from the file name, before the file was read ("…2160p.HDR.mkv" -> 4K, HDR). */
    fun fromName(name: String): Pair<String?, Boolean> {
        val n = name.replace('.', ' ').replace('_', ' ')
        val q = when { Q4K.containsMatchIn(n) -> "4K"; Q1080.containsMatchIn(n) -> "1080p"; Q720.containsMatchIn(n) -> "720p"; else -> null }
        return q to HDR.containsMatchIn(n)
    }

    /** Small chips of the folder list: quality, then "HDR" for any HDR kind. */
    fun chips(f: MediaFacts?, name: String): List<String> {
        val (nq, nh) = fromName(name)
        val q = f?.let { quality(it.width, it.height) } ?: nq
        val hdr = if (f != null && !f.quick) f.hdr != null || f.dolbyVision else (f?.hdr != null || nh)
        return listOfNotNull(q, if (hdr) "HDR" else null)
    }

    /** Chips of the file card and the player: 4K, HDR10, Dolby Vision, Atmos. The name tells the quality when the file did not. */
    fun card(f: MediaFacts, name: String? = null): List<String> = listOfNotNull(
        quality(f.width, f.height) ?: name?.let { fromName(it).first },
        f.hdr,
        if (f.dolbyVision) "Dolby Vision" else null,
        if (f.atmos) "Atmos" else null,
    )

    /** "RU, EN" for the subtitle chip. */
    fun short(langs: List<String>): String? =
        langs.map { it.lowercase(Locale.ROOT) }.filter { it.isNotEmpty() && it != "und" }.map { two(it).uppercase(Locale.ROOT) }
            .distinct().joinToString(", ").ifEmpty { null }

    /** "Русский, английский" (sound) or "Русские, английские" (subtitles, [plural]). */
    fun names(langs: List<String>, ru: Boolean, plural: Boolean = false): String? {
        val out = langs.map { it.lowercase(Locale.ROOT) }.filter { it.isNotEmpty() && it != "und" }.map(::two).distinct().map { c ->
            if (ru) (if (plural) RU_PL[c] else RU[c]) ?: c.uppercase(Locale.ROOT) else Locale(c).getDisplayLanguage(Locale.ENGLISH).ifEmpty { c }
        }
        if (out.isEmpty()) return null
        return out.mapIndexed { i, s -> if (i == 0) s.replaceFirstChar { it.titlecase(Locale.ROOT) } else if (ru) s.lowercase(Locale.ROOT) else s }.joinToString(", ")
    }

    /** ISO 639-2 (rus, eng, ger) to two letters. */
    fun two(code: String): String = THREE[code] ?: code.take(2)

    private val THREE = mapOf("rus" to "ru", "eng" to "en", "ukr" to "uk", "kaz" to "kk", "ger" to "de", "deu" to "de",
        "fre" to "fr", "fra" to "fr", "spa" to "es", "ita" to "it", "por" to "pt", "pol" to "pl", "tur" to "tr",
        "jpn" to "ja", "kor" to "ko", "chi" to "zh", "zho" to "zh", "bel" to "be")
    private val RU = mapOf("ru" to "русский", "en" to "английский", "uk" to "украинский", "kk" to "казахский", "de" to "немецкий",
        "fr" to "французский", "es" to "испанский", "it" to "итальянский", "pt" to "португальский", "pl" to "польский",
        "tr" to "турецкий", "ja" to "японский", "ko" to "корейский", "zh" to "китайский", "be" to "белорусский")
    private val RU_PL = RU.mapValues { (_, v) -> v.removeSuffix("ий") + "ие" }
}

package com.xgimiru.player.torrents

import android.content.Context
import com.xgimiru.player.ui.tr
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln

/**
 * Результат поиска. [link]: magnet или ссылка на .torrent. Поля после [date] знают не все источники
 * (их отдают сервисы в духе Jackett с разбором раздач, поле info): если нет: всё берём из названия.
 */
data class Release(
    val title: String,
    val link: String,
    val size: Long,
    val seeders: Int,
    val peers: Int,
    val tracker: String?,
    val hash: String?,
    val date: String?,
    val time: Long = 0L,                       // дата раздачи, мс; 0: неизвестна
    val quality: Int = 0,                      // 2160, 1080, 720, 480; 0: неизвестно
    val hdrHint: Boolean = false,              // источник сам сказал «HDR»
    val voices: List<String> = emptyList(),    // озвучки от источника
    val seasons: List<Int> = emptyList(),      // сезоны от источника
) {
    /** Ключ для поиска повторов: hash, иначе название и размер. */
    val key: String get() = hash ?: (Tags.norm(title) + "|" + size)

    val tags: Tags by lazy { Tags.parse(this) }

    fun toJson(): JSONObject = JSONObject().put("t", title).put("l", link).put("s", size).put("se", seeders)
        .put("pe", peers).put("tr", tracker).put("h", hash).put("d", date).put("tm", time).put("q", quality)
        .put("hd", hdrHint).put("v", JSONArray(voices)).put("ss", JSONArray(seasons))

    companion object {
        fun fromJson(o: JSONObject) = Release(o.getString("t"), o.getString("l"), o.optLong("s"), o.optInt("se"),
            o.optInt("pe"), o.optString("tr").ifEmpty { null }, o.optString("h").ifEmpty { null },
            o.optString("d").ifEmpty { null }, o.optLong("tm"), o.optInt("q"), o.optBoolean("hd"),
            o.optJSONArray("v")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
            o.optJSONArray("ss")?.let { a -> (0 until a.length()).map { a.getInt(it) } } ?: emptyList())

        /** Info-hash из magnet-ссылки, в нижнем регистре hex (base32 тоже переводим). */
        fun hashOf(link: String?): String? {
            val m = Regex("(?i)btih:([0-9a-z]+)").find(link ?: return null)?.groupValues?.get(1) ?: return null
            return when (m.length) {
                40 -> m.lowercase(Locale.ROOT)
                32 -> base32ToHex(m.uppercase(Locale.ROOT))
                else -> null
            }
        }

        private fun base32ToHex(s: String): String? {
            val abc = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
            var bits = 0; var value = 0
            val out = StringBuilder()
            for (c in s) {
                val v = abc.indexOf(c).takeIf { it >= 0 } ?: return null
                value = (value shl 5) or v; bits += 5
                if (bits >= 8) { bits -= 8; out.append("%02x".format((value shr bits) and 0xFF)) }
            }
            return out.toString().takeIf { it.length == 40 }
        }
    }
}

/**
 * Что можно понять о раздаче по названию: качество, HDR, тип рипа, озвучки, сезоны, год. Плюс то,
 * что источник сообщил сам. Считается один раз на раздачу (lazy), регулярки собраны заранее.
 */
class Tags(
    val quality: Int, val dv: Boolean, val hdr10p: Boolean, val hdr: Boolean, val kind: String?,
    val voices: List<String>, val seasons: Set<Int>, val years: List<Int>, val cam: Boolean, val hevc: Boolean,
) {
    val anyHdr get() = dv || hdr10p || hdr

    /** Значки для строки раздачи: качество, HDR, тип рипа. Озвучки отдельно. */
    val badges: List<String> get() {
        val out = ArrayList<String>()
        when (quality) {
            2160 -> out += "4K"
            1080 -> out += "1080p"
            720 -> out += "720p"
            in 1..719 -> out += "SD"
        }
        if (dv) out += "DV"
        if (hdr10p) out += "HDR10+" else if (hdr) out += "HDR"
        if (kind != null) out += kind
        return out
    }

    companion object {
        private val Q2160 = Regex("2160p|\\b4k\\b|\\buhd\\b")
        private val DV = Regex("\\b(dv|dovi|dolby ?vision)\\b")
        private val HDR10P = Regex("hdr10\\+|hdr10plus|hdr10 plus")
        private val HDR = Regex("\\bhdr(10)?\\b")
        private val HEVC = Regex("\\b(hevc|h\\.?265|x265)\\b")
        private val CAM = Regex("\\b(cam-?rip|hdcam|camrip|telesync|hdts|ts-?rip)\\b")
        private val TS = Regex("\\bTS\\b")
        private val YEAR = Regex("\\b(19[2-9]\\d|20[0-4]\\d)\\b")
        private val KINDS = listOf(
            "Remux" to Regex("remux"),
            "WEB-DL" to Regex("web-?dl(?!-?rip)"),
            "WEBRip" to Regex("web-?(dl-?)?rip"),
            "BDRip" to Regex("bd-?rip|blu-?ray-?rip|brrip|bluray(?!.*remux)"),
            "HDRip" to Regex("hd-?rip"),
            "HDTV" to Regex("hdtv"),
            "DVDRip" to Regex("dvd-?rip"),
            "DVD" to Regex("\\bdvd(-?[59])?\\b"),
        )
        // озвучки: студии и виды перевода, как их пишут трекеры
        private val VOICES = listOf(
            "Дубляж" to Regex("(?i)дубляж|дублир|\\bdub\\b|\\bдб\\b|\\|\\s*d\\s*(\\||$)"),
            "LostFilm" to Regex("(?i)lost ?film"),
            "HDRezka" to Regex("(?i)hd ?rezka|резка"),
            "NewStudio" to Regex("(?i)new ?studio"),
            "Кубик в Кубе" to Regex("(?i)кубик в кубе|kubik"),
            "TVShows" to Regex("(?i)tv ?shows"),
            "Jaskier" to Regex("(?i)jaskier"),
            "Пифагор" to Regex("(?i)пифагор|pifagor"),
            "Red Head Sound" to Regex("(?i)red ?head ?sound|\\brhs\\b"),
            "ColdFilm" to Regex("(?i)cold ?film"),
            "AlexFilm" to Regex("(?i)alex ?film"),
            "BaibaKo" to Regex("(?i)baibako"),
            "Amedia" to Regex("(?i)amedia"),
            "Кураж-Бамбей" to Regex("(?i)кураж|kurazh"),
            "IdeaFilm" to Regex("(?i)idea ?film"),
            "AniLibria" to Regex("(?i)anilibria"),
            "AniDUB" to Regex("(?i)anidub"),
            "SHIZA" to Regex("(?i)shiza"),
            "Сербин" to Regex("(?i)сербин"),
            "Гаврилов" to Regex("(?i)гаврилов"),
            "Живов" to Regex("(?i)живов"),
            "Гоблин" to Regex("(?i)гоблин|пучков"),
            "MVO" to Regex("(?i)\\bmvo\\b|\\bпм\\b|многоголос"),
            "DVO" to Regex("(?i)\\bdvo\\b|двухголос"),
            "AVO" to Regex("(?i)\\bavo\\b|\\bап\\b|авторск"),
        )
        private val SEASON_S = Regex("\\bs(\\d{1,2})(?:\\s*-\\s*s?(\\d{1,2}))?(?=e\\d|\\b)")
        private val SEASON_WORD = Regex("(?:сезон[ыа]?|seasons?)\\s*:?\\s*(\\d{1,2})(?:\\s*-\\s*(\\d{1,2}))?")
        private val SEASON_BEFORE = Regex("\\b(\\d{1,2})(?:\\s*-\\s*(\\d{1,2}))?\\s*(?:-?й\\s*)?сезон")
        private val SEASON_X = Regex("\\[(\\d{1,2})x\\d{1,3}")

        fun norm(s: String) = s.lowercase(Locale.ROOT).replace('ё', 'е').replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

        fun parse(r: Release): Tags {
            val raw = r.title
            val t = raw.lowercase(Locale.ROOT)
            val quality = when {
                "2160p" in t -> 2160
                "1080p" in t || "1080i" in t -> 1080
                "720p" in t -> 720
                Q2160.containsMatchIn(t) -> 2160
                r.quality > 0 -> r.quality
                Regex("\\b(480p|576p|dvdrip|dvd-?[59]|\\bsd\\b)").containsMatchIn(t) -> 480
                else -> 0
            }
            val dv = DV.containsMatchIn(t)
            val hdr10p = HDR10P.containsMatchIn(t)
            val hdr = HDR.containsMatchIn(t) || (r.hdrHint && !dv && !hdr10p)
            val cam = CAM.containsMatchIn(t) || TS.containsMatchIn(raw)
            val kind = if (cam) "CAMRip" else KINDS.firstOrNull { it.second.containsMatchIn(t) }?.first
            val voices = LinkedHashSet<String>()
            for ((name, re) in VOICES) if (re.containsMatchIn(raw)) voices += name
            for (v in r.voices) if (voices.none { it.equals(v, true) }) voices += v
            val seasons = sortedSetOf<Int>()
            fun range(a: String, b: String?) {
                val x = a.toIntOrNull() ?: return
                val y = b?.toIntOrNull()?.takeIf { it >= x && it - x < 60 } ?: x
                if (x in 0..80) for (i in x..y) seasons += i
            }
            for (re in listOf(SEASON_S, SEASON_WORD, SEASON_BEFORE, SEASON_X))
                for (m in re.findAll(t)) range(m.groupValues[1], m.groupValues.getOrNull(2)?.ifEmpty { null })
            seasons += r.seasons
            val years = YEAR.findAll(t).map { it.value.toInt() }.toList()
            return Tags(quality, dv, hdr10p, hdr, kind, voices.toList(), seasons, years, cam, HEVC.containsMatchIn(t))
        }

        /**
         * Оценка «насколько это хорошая раздача»: сиды важнее всего (по логарифму), потом качество.
         * 1080p чуть выше 4K: на проекторе 4K-ремукс по торренту часто не успевает подгружаться.
         */
        fun score(r: Release): Double {
            val g = r.tags
            var s = ln(1.0 + r.seeders) * 12
            s += when (g.quality) { 2160 -> 16.0; 1080 -> 18.0; 720 -> 11.0; 0 -> 6.0; else -> 3.0 }
            if (g.dv || g.hdr10p) s += 2 else if (g.hdr) s += 1.5
            if (g.kind == "Remux") s += 2 else if (g.kind == "WEB-DL" || g.kind == "BDRip") s += 1.5
            if ("Дубляж" in g.voices) s += 3 else if (g.voices.isNotEmpty()) s += 1
            if (g.cam) s -= 60
            if (r.seeders == 0) s -= 30
            return s
        }

        /** Подходит ли год раздачи к году фильма (±1: премьера и выход на трекерах разнятся). */
        fun yearFits(g: Tags, year: Int?): Boolean = year == null || g.years.isEmpty() || g.years.any { abs(it - year) <= 1 }
    }
}

/** Какую раздачу выбирали для фильма или сериала: её предлагаем первой («Продолжить с этой раздачей»). */
object Picks {
    private const val PREFS = "torrent_picks"
    private const val LIMIT = 300

    class Pick(val release: Release, val season: Int, val at: Long)

    fun get(ctx: Context, key: String): Pick? {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, null) ?: return null
        return runCatching { JSONObject(raw).let { Pick(Release.fromJson(it.getJSONObject("r")), it.optInt("s"), it.optLong("at")) } }.getOrNull()
    }

    fun save(ctx: Context, key: String, r: Release, season: Int) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val e = p.edit().putString(key, JSONObject().put("r", r.toJson()).put("s", season).put("at", System.currentTimeMillis()).toString())
        // старые выборы не копятся бесконечно: сверх лимита убираем самые давние
        val all = p.all
        if (all.size > LIMIT) all.entries.sortedBy { runCatching { JSONObject(it.value as String).optLong("at") }.getOrDefault(0L) }
            .take(all.size - LIMIT).forEach { e.remove(it.key) }
        e.apply()
    }
}

/** Человеческий размер: «10.7 ГБ», «700 МБ». */
fun humanSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f %s", bytes / (1L shl 30).toDouble(), tr("ГБ", "GB"))
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.0f %s", bytes / (1L shl 20).toDouble(), tr("МБ", "MB"))
    else -> ""
}

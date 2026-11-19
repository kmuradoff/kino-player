package com.xgimiru.player.settings

/** Storage behind the player settings: SharedPreferences on the device, a map in tests. */
interface Store {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

class MapStore(val map: MutableMap<String, String> = HashMap()) : Store {
    override fun get(key: String) = map[key]
    override fun put(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
}

enum class Section(val ru: String, val en: String) {
    VIDEO("Видео", "Video"),
    AUDIO("Звук", "Audio"),
    SUBTITLES("Субтитры", "Subtitles"),
    INTERFACE("Интерфейс", "Interface"),
}

class Option(val value: String, val ru: String = value, val en: String = ru)

/** One row of the settings screen. [shownIf] hides rows that make no sense now (formats without passthrough). */
sealed class Setting(val key: String, val section: Section, val ru: String, val en: String, val shownIf: ((Settings) -> Boolean)?)

class Choice(key: String, section: Section, ru: String, en: String, val options: List<Option>, val default: String,
             shownIf: ((Settings) -> Boolean)? = null) : Setting(key, section, ru, en, shownIf) {
    init { require(options.any { it.value == default }) { "default of $key is not an option" } }
    fun option(value: String) = options.first { it.value == value }
}

class Toggle(key: String, section: Section, ru: String, en: String, val default: Boolean,
             shownIf: ((Settings) -> Boolean)? = null) : Setting(key, section, ru, en, shownIf)

/** A number changed with Left / Right. [unit] "ms" or "s100" (shown in seconds, stored in ms). */
class Stepper(key: String, section: Section, ru: String, en: String, val min: Int, val max: Int, val step: Int,
              val default: Int, val unit: String, shownIf: ((Settings) -> Boolean)? = null) : Setting(key, section, ru, en, shownIf) {
    fun clamp(v: Int) = (v.coerceIn(min, max) - min) / step * step + min
}

/**
 * Every player setting, Kodi style (Videos > Playback, Subtitles, Audio, Player > Videos), limited to what
 * Media3 can really do: Media3 is always the engine, so there is no engine choice and no libVLC-only row
 * (deinterlacing: the hardware decoder does it, the libVLC fallback deinterlaces by itself). Keys of Lumen Play 1.3 (tunneling, doviHdr, scaleMode) stay the same so a
 * user keeps his choice.
 */
object K {
    private val LANGS = listOf(
        Option("ru", "Русский", "Russian"), Option("en", "Английский", "English"), Option("uk", "Украинский", "Ukrainian"),
        Option("kk", "Казахский", "Kazakh"), Option("de", "Немецкий", "German"), Option("fr", "Французский", "French"),
        Option("es", "Испанский", "Spanish"), Option("it", "Итальянский", "Italian"), Option("pt", "Португальский", "Portuguese"),
        Option("pl", "Польский", "Polish"), Option("tr", "Турецкий", "Turkish"), Option("ja", "Японский", "Japanese"),
        Option("ko", "Корейский", "Korean"), Option("zh", "Китайский", "Chinese"),
    )
    private val V = Section.VIDEO
    private val A = Section.AUDIO
    private val S = Section.SUBTITLES
    private val I = Section.INTERFACE
    private fun ptOn(s: Settings) = s.bool(PASSTHROUGH)

    // ── Video ──
    val DECODER = Choice("decoder", V, "Декодер видео", "Video decoder", listOf(
        Option("auto", "Аппаратный, при ошибке программный", "Hardware, software on error"),
        Option("hw", "Только аппаратный", "Hardware only"),
        Option("sw", "Программный", "Software")), "auto")
    val TUNNELING = Toggle("tunneling", V, "Туннельный режим", "Tunnelled playback", false)
    val DV5 = Choice("doviHdr", V, "Dolby Vision профиль 5", "Dolby Vision profile 5", listOf(
        Option("false", "SDR, свой тон-маппинг", "SDR, own tone mapping"),
        Option("true", "HDR10 на проектор", "HDR10 to the display")), "false")
    val SCALE = Choice("scaleMode", V, "Масштаб", "Zoom", listOf(
        Option("0", "Целиком", "Fit"), Option("1", "Заполнить, обрезать края", "Fill, crop edges"),
        Option("2", "Растянуть", "Stretch")), "0")
    val ASPECT = Choice("aspect", V, "Соотношение сторон", "Aspect ratio", listOf(
        Option("auto", "Как в файле", "From the file"), Option("16:9", "16:9"), Option("4:3", "4:3"),
        Option("21:9", "21:9"), Option("1.85", "1,85:1", "1.85:1"), Option("2.35", "2,35:1", "2.35:1")), "auto")
    val RESUME = Choice("resume", V, "Продолжать с места остановки", "Resume where you stopped", listOf(
        Option("resume", "Да", "Yes"), Option("ask", "Спрашивать", "Ask"), Option("start", "Нет, с начала", "No, from the start")), "resume")
    val SEEK_STEP = Choice("seekStep", V, "Шаг перемотки", "Seek step", listOf(
        Option("5", "5 с", "5 s"), Option("10", "10 с", "10 s"), Option("15", "15 с", "15 s"), Option("20", "20 с", "20 s"),
        Option("30", "30 с", "30 s"), Option("60", "1 мин", "1 min")), "10")
    val SEEK_ACCEL = Toggle("seekAccel", V, "Ускорять перемотку при удержании", "Faster seeking while held", true)
    val BIG_STEP = Choice("bigStep", V, "Большой шаг", "Big step", listOf(
        Option("60", "1 мин", "1 min"), Option("300", "5 мин", "5 min"), Option("600", "10 мин", "10 min")), "300")
    val SPEED = Choice("speedStart", V, "Скорость при запуске", "Speed at start", listOf(
        Option("normal", "Обычная", "Normal"), Option("last", "Как в прошлый раз", "As last time")), "normal")
    val AUTO_NEXT = Toggle("autoNext", V, "Следующая серия сама", "Play the next episode", true)

    // ── Audio ──
    val AUDIO_LANG = Choice("audioLang", A, "Язык звука", "Audio language",
        listOf(Option("system", "Как в системе", "System language"), Option("original", "Оригинал (как в файле)", "Original (from the file)")) + LANGS, "system")
    val PASSTHROUGH = Toggle("passthrough", A, "Передавать звук на ресивер", "Passthrough to the receiver", true)
    val PT_AC3 = Toggle("ptAc3", A, "Dolby Digital (AC3)", "Dolby Digital (AC3)", true, ::ptOn)
    val PT_EAC3 = Toggle("ptEac3", A, "Dolby Digital Plus (E-AC3)", "Dolby Digital Plus (E-AC3)", true, ::ptOn)
    val PT_ATMOS = Toggle("ptAtmos", A, "Dolby Atmos в E-AC3", "Dolby Atmos in E-AC3", true, ::ptOn)
    val PT_TRUEHD = Toggle("ptTrueHd", A, "Dolby TrueHD и Atmos", "Dolby TrueHD and Atmos", true, ::ptOn)
    val PT_DTS = Toggle("ptDts", A, "DTS", "DTS", true, ::ptOn)
    val PT_DTSHD = Toggle("ptDtsHd", A, "DTS-HD", "DTS-HD", true, ::ptOn)
    val DOWNMIX = Toggle("downmix", A, "Сводить в стерео", "Downmix to stereo", false)
    val AUDIO_DELAY = Stepper("audioDelay", A, "Задержка звука", "Audio delay", -2000, 2000, 25, 0, "ms")
    val BOOST = Choice("boost", A, "Усиление громкости", "Volume boost", listOf(
        Option("0", "Выключено", "Off"), Option("3", "+3 дБ", "+3 dB"), Option("6", "+6 дБ", "+6 dB"),
        Option("9", "+9 дБ", "+9 dB"), Option("12", "+12 дБ", "+12 dB")), "0")
    val NIGHT = Toggle("night", A, "Ночной режим", "Night mode", false)

    // ── Subtitles ──
    val SUB_LANG = Choice("subLang", S, "Показывать субтитры", "Show subtitles",
        listOf(Option("file", "Как отмечено в файле", "As marked in the file"), Option("forced", "Только надписи", "Forced only"),
            Option("off", "Не показывать", "Off"), Option("system", "На языке системы", "In the system language")) + LANGS, "file")
    val SUB_LANG2 = Choice("subLang2", S, "Запасной язык", "Second language",
        listOf(Option("none", "Нет", "None")) + LANGS, "none") { it.string(SUB_LANG) !in setOf("file", "forced", "off") }
    val SUB_AUTO = Toggle("subAuto", S, "Внешние субтитры рядом с файлом", "External subtitles next to the file", true)
    val SUB_ENCODING = Choice("subEncoding", S, "Кодировка", "Character set", listOf(
        Option("auto", "Определять сама", "Detect"), Option("UTF-8"), Option("windows-1251", "Кириллица (Windows-1251)", "Cyrillic (Windows-1251)"),
        Option("KOI8-R", "Кириллица (KOI8-R)", "Cyrillic (KOI8-R)"), Option("IBM866", "Кириллица (DOS 866)", "Cyrillic (DOS 866)"),
        Option("windows-1252", "Западная (Windows-1252)", "Western (Windows-1252)"),
        Option("windows-1250", "Центральная Европа (Windows-1250)", "Central European (Windows-1250)"),
        Option("windows-1253", "Греческая (Windows-1253)", "Greek (Windows-1253)"),
        Option("windows-1254", "Турецкая (Windows-1254)", "Turkish (Windows-1254)"),
        Option("windows-1255", "Иврит (Windows-1255)", "Hebrew (Windows-1255)"),
        Option("windows-1256", "Арабская (Windows-1256)", "Arabic (Windows-1256)"),
        Option("GB18030", "Китайская упрощённая (GB18030)", "Chinese simplified (GB18030)"),
        Option("Big5", "Китайская традиционная (Big5)", "Chinese traditional (Big5)"),
        Option("Shift_JIS", "Японская (Shift_JIS)", "Japanese (Shift_JIS)"),
        Option("EUC-KR", "Корейская (EUC-KR)", "Korean (EUC-KR)")), "auto")
    val SUB_SIZE = Choice("subSize", S, "Размер", "Size", listOf(
        Option("70", "Очень мелкий", "Smallest"), Option("85", "Мелкий", "Small"), Option("100", "Обычный", "Normal"),
        Option("115", "Крупный", "Large"), Option("135", "Очень крупный", "Larger"), Option("160", "Огромный", "Largest")), "115")
    val SUB_COLOR = Choice("subColor", S, "Цвет", "Colour", listOf(
        Option("white", "Белый", "White"), Option("yellow", "Жёлтый", "Yellow"), Option("grey", "Светло-серый", "Light grey"),
        Option("cyan", "Голубой", "Cyan"), Option("green", "Зелёный", "Green")), "white")
    val SUB_BACK = Choice("subBack", S, "Фон", "Background", listOf(
        Option("none", "Без фона", "None"), Option("half", "Полупрозрачный", "Translucent"), Option("solid", "Чёрный", "Black")), "none")
    val SUB_EDGE = Choice("subEdge", S, "Обводка", "Edge", listOf(
        Option("outline", "Контур", "Outline"), Option("shadow", "Тень", "Drop shadow"), Option("raised", "Объём", "Raised"),
        Option("none", "Без обводки", "None")), "outline")
    val SUB_BOLD = Toggle("subBold", S, "Жирный шрифт", "Bold text", true)
    val SUB_POSITION = Choice("subPosition", S, "Отступ снизу", "Bottom margin", listOf(
        Option("2", "2 %"), Option("4", "4 %"), Option("6", "6 %"), Option("8", "8 %"), Option("10", "10 %"),
        Option("15", "15 %"), Option("20", "20 %")), "6")
    val SUB_DELAY = Stepper("subDelay", S, "Задержка субтитров", "Subtitle delay", -10000, 10000, 100, 0, "s100")
    val SUB_STYLES = Toggle("subStyles", S, "Стиль из файла (ASS)", "Styles from the file (ASS)", false)

    // ── Interface ──
    val OSD_TIMEOUT = Choice("osdTimeout", I, "Скрывать панель через", "Hide the bar after", listOf(
        Option("2000", "2 с", "2 s"), Option("3500", "3,5 с", "3.5 s"), Option("5000", "5 с", "5 s"),
        Option("8000", "8 с", "8 s"), Option("15000", "15 с", "15 s")), "3500")
    val INFO = Toggle("infoChips", I, "Сведения о видео и звуке", "Video and audio details", true)
    val CLOCK = Toggle("osdClock", I, "Часы и время окончания", "Clock and end time", true)
    val KEY_OK = Choice("keyOk", I, "Кнопка OK", "OK button", listOf(
        Option("pause", "Пауза и продолжение", "Pause and play"), Option("menu", "Открыть меню", "Open the menu")), "pause")
    /** Dark by default: films are watched in the dark, and the player must not look like the light Home. */
    val THEME = Choice("theme", I, "Тема", "Theme", listOf(
        Option("dark", "Тёмная", "Dark"), Option("light", "Светлая", "Light")), "dark")
    val KEY_UPDOWN = Choice("keyUpDown", I, "Кнопки вверх и вниз", "Up and Down buttons", listOf(
        Option("menu", "Вниз: меню, вверх: панель", "Down: menu, Up: bar"),
        Option("seek", "Большой шаг перемотки", "Big seek step")), "menu")

    val ALL: List<Setting> = listOf(
        DECODER, TUNNELING, DV5, SCALE, ASPECT, RESUME, SEEK_STEP, SEEK_ACCEL, BIG_STEP, SPEED, AUTO_NEXT,
        AUDIO_LANG, PASSTHROUGH, PT_AC3, PT_EAC3, PT_ATMOS, PT_TRUEHD, PT_DTS, PT_DTSHD, DOWNMIX, AUDIO_DELAY, BOOST, NIGHT,
        SUB_LANG, SUB_LANG2, SUB_AUTO, SUB_ENCODING, SUB_SIZE, SUB_COLOR, SUB_BACK, SUB_EDGE, SUB_BOLD, SUB_POSITION, SUB_DELAY, SUB_STYLES,
        THEME, OSD_TIMEOUT, INFO, CLOCK, KEY_OK, KEY_UPDOWN,
    )

    fun of(section: Section) = ALL.filter { it.section == section }
}

/** Typed view of the stored values. Unknown or broken values read as the default, never as a crash. */
class Settings(private val store: Store) {
    fun string(c: Choice): String = store.get(c.key)?.takeIf { v -> c.options.any { it.value == v } } ?: c.default
    fun bool(t: Toggle): Boolean = when (store.get(t.key)) { "true" -> true; "false" -> false; else -> t.default }
    fun int(s: Stepper): Int = store.get(s.key)?.toIntOrNull()?.let(s::clamp) ?: s.default

    fun set(c: Choice, v: String) { require(c.options.any { it.value == v }) { "$v is not an option of ${c.key}" }; store.put(c.key, v) }
    fun set(t: Toggle, v: Boolean) = store.put(t.key, v.toString())
    fun set(s: Stepper, v: Int) = store.put(s.key, s.clamp(v).toString())

    /** The stored text of a setting, null when nothing is stored. */
    fun raw(x: Setting): String? = store.get(x.key)

    fun visible(x: Setting) = x.shownIf?.invoke(this) ?: true
    fun reset() = K.ALL.forEach { store.put(it.key, null) }

    /** Text of the current value for the row. */
    fun label(x: Setting, ru: Boolean): String = when (x) {
        is Choice -> x.option(string(x)).let { if (ru) it.ru else it.en }
        is Toggle -> if (bool(x)) (if (ru) "Вкл." else "On") else (if (ru) "Выкл." else "Off")
        is Stepper -> stepLabel(x, int(x), ru)
    }

    // typed shortcuts used by the player
    val seekStepMs get() = string(K.SEEK_STEP).toLong() * 1000
    val bigStepMs get() = string(K.BIG_STEP).toLong() * 1000
    val osdTimeoutMs get() = string(K.OSD_TIMEOUT).toLong()
    val scale get() = string(K.SCALE).toInt()
    val subSize get() = string(K.SUB_SIZE).toInt()
    val subPosition get() = string(K.SUB_POSITION).toInt()
    val boostDb get() = string(K.BOOST).toInt()
    /** Forced aspect as width / height, null = from the file. */
    val aspect: Float? get() = aspectOf(string(K.ASPECT))

    companion object {
        fun aspectOf(a: String): Float? = when {
            a == "auto" -> null
            ':' in a -> a.substringBefore(':').toFloat() / a.substringAfter(':').toFloat()
            else -> a.toFloatOrNull()
        }

        fun stepLabel(s: Stepper, v: Int, ru: Boolean): String {
            val sign = if (v > 0) "+" else if (v < 0) "\u2212" else ""
            val a = kotlin.math.abs(v)
            return if (s.unit == "s100") {
                val txt = "%d%s%d".format(a / 1000, if (ru) "," else ".", a % 1000 / 100)
                sign + txt + (if (ru) " с" else " s")
            } else sign + a + (if (ru) " мс" else " ms")
        }
    }
}

/**
 * Which compressed formats go to the receiver untouched. Media3 asks the audio sink per format: a format
 * switched off here is decoded to PCM instead (MediaCodec or FFmpeg).
 */
object Passthrough {
    private val KEYS = mapOf(
        "audio/ac3" to K.PT_AC3, "audio/eac3" to K.PT_EAC3, "audio/eac3-joc" to K.PT_ATMOS,
        "audio/true-hd" to K.PT_TRUEHD, "audio/vnd.dts" to K.PT_DTS, "audio/vnd.dts.hd" to K.PT_DTSHD,
        "audio/vnd.dts.uhd;profile=p2" to K.PT_DTSHD, "audio/ac4" to null,
    )

    fun isCompressed(mime: String?) = mime != null && mime in KEYS

    fun allowed(mime: String?, s: Settings): Boolean {
        if (!isCompressed(mime)) return true
        if (!s.bool(K.PASSTHROUGH)) return false
        return KEYS[mime]?.let(s::bool) ?: true
    }
}

/**
 * Auto frame rate switching is not part of the basic player. The hook stays so a module can switch it on;
 * nothing in the app sets it and there is no menu row.
 */
object FrameRateHook {
    @Volatile var enabled: (() -> Boolean)? = null
    fun active() = enabled?.invoke() == true
}

package com.xgimiru.player.media

import java.util.Calendar
import java.util.Locale

/** Numbers and dates the way the screens print them, in Russian or English. Pure code, host tested. */
object Fmt {
    /** "58,4 ГБ", "940 МБ". */
    fun size(bytes: Long, ru: Boolean): String {
        val gb = bytes / 1073741824.0
        val mb = bytes / 1048576.0
        return when {
            gb >= 1 -> dec(gb, ru) + if (ru) " ГБ" else " GB"
            mb >= 1 -> "%d".format(Math.round(mb)) + if (ru) " МБ" else " MB"
            else -> "%d".format(maxOf(1L, bytes / 1024)) + if (ru) " КБ" else " KB"
        }
    }

    /** "Свободно 12 из 58 ГБ". */
    fun free(free: Long, total: Long, ru: Boolean): String {
        val f = Math.round(free / 1073741824.0); val t = Math.round(total / 1073741824.0)
        return if (t >= 1) (if (ru) "Свободно $f из $t ГБ" else "$f of $t GB free")
        else (if (ru) "Свободно ${free / 1048576} из ${total / 1048576} МБ" else "${free / 1048576} of ${total / 1048576} MB free")
    }

    /** "2 ч 07 мин", "47 мин". */
    fun duration(ms: Long, ru: Boolean): String {
        val min = Math.round(ms / 60000.0).toInt()
        val h = min / 60; val m = min % 60
        return if (h > 0) (if (ru) "%d ч %02d мин" else "%d h %02d min").format(h, m) else (if (ru) "$m мин" else "$m min")
    }

    /** "3 файла", "7 объектов" ([forms]: one, few, many). */
    fun count(n: Int, ru: Boolean, forms: Array<String>, en: Array<String>): String {
        if (!ru) return "$n " + (if (n == 1) en[0] else en[1])
        val d = n % 10; val dd = n % 100
        val f = when { d == 1 && dd != 11 -> forms[0]; d in 2..4 && dd !in 12..14 -> forms[1]; else -> forms[2] }
        return "$n $f"
    }

    fun files(n: Int, ru: Boolean) = count(n, ru, arrayOf("файл", "файла", "файлов"), arrayOf("file", "files"))
    fun objects(n: Int, ru: Boolean) = count(n, ru, arrayOf("объект", "объекта", "объектов"), arrayOf("item", "items"))

    private val RU_MONTHS = arrayOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря")
    private val EN_MONTHS = arrayOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
    private val RU_DAYS = arrayOf("Воскресенье", "Понедельник", "Вторник", "Среда", "Четверг", "Пятница", "Суббота")
    private val EN_DAYS = arrayOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")

    /** "Сегодня, 21:40", "Вчера, 23:10", "Понедельник" (this week), "12 ноября". */
    fun whenWatched(at: Long, now: Long, ru: Boolean): String {
        val c = Calendar.getInstance().apply { timeInMillis = at }
        val today = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val days = Math.floorDiv(today.timeInMillis - startOfDay(at) + 3_600_000L, 86_400_000L)
        val hm = "%d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
        return when {
            at >= today.timeInMillis -> (if (ru) "Сегодня, " else "Today, ") + hm
            days <= 1 -> (if (ru) "Вчера, " else "Yesterday, ") + hm
            days < 7 -> (if (ru) RU_DAYS else EN_DAYS)[c.get(Calendar.DAY_OF_WEEK) - 1]
            ru -> "${c.get(Calendar.DAY_OF_MONTH)} ${RU_MONTHS[c.get(Calendar.MONTH)]}"
            else -> "${EN_MONTHS[c.get(Calendar.MONTH)]} ${c.get(Calendar.DAY_OF_MONTH)}"
        }
    }

    private fun startOfDay(t: Long) = Calendar.getInstance().apply {
        timeInMillis = t; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun dec(v: Double, ru: Boolean): String {
        val s = String.format(Locale.US, "%.1f", v)
        return if (ru) s.replace('.', ',') else s
    }

    /** Natural order: "Серия 2" before "Серия 10", folders first. */
    fun natural(a: String, b: String): Int {
        val ra = Regex("\\d+|\\D+").findAll(a.lowercase(Locale.ROOT)).map { it.value }.toList()
        val rb = Regex("\\d+|\\D+").findAll(b.lowercase(Locale.ROOT)).map { it.value }.toList()
        for (i in 0 until minOf(ra.size, rb.size)) {
            val x = ra[i]; val y = rb[i]
            val c = if (x[0].isDigit() && y[0].isDigit()) compareValues(x.trimStart('0').length, y.trimStart('0').length)
                .takeIf { it != 0 } ?: x.trimStart('0').compareTo(y.trimStart('0')) else x.compareTo(y)
            if (c != 0) return c
        }
        return ra.size - rb.size
    }
}

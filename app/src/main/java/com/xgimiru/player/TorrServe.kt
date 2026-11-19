package com.xgimiru.player

import android.net.Uri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Скорость и пиры торрента — показываем, пока идёт загрузка. */
object TorrServe {
    /** [fileBytes] — размер играющего файла (по index ссылки), 0 — неизвестен. */
    data class Stat(val speedMbit: Double, val peers: Int, val seeders: Int, val complete: Boolean, val fileBytes: Long = 0)

    /** Ссылка TorrServe: /stream и link= с magnet, btih или hash торрента (40 hex), а не любая ссылка с «link». */
    fun isTorrServe(uri: Uri): Boolean {
        val link = uri.getQueryParameter("link") ?: return false
        if (uri.path?.contains("/stream") != true) return false
        return link.startsWith("magnet:", true) || link.contains("btih:", true) || Regex("^[0-9a-fA-F]{40}$").matches(link)
    }

    /** Блокирующий запрос — вызывать не из главного потока. */
    fun stat(uri: Uri): Stat? = runCatching {
        val hash = uri.getQueryParameter("link")?.substringAfter("btih:")?.take(40) ?: return null
        val base = "${uri.scheme}://${uri.authority}"
        val c = URL("$base/torrents").openConnection() as HttpURLConnection
        c.connectTimeout = 1500; c.readTimeout = 1500
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write("""{"action":"get","hash":"$hash"}""".toByteArray()) }
        val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        c.disconnect()
        val loaded = o.optLong("loaded_size"); val size = o.optLong("torrent_size")
        val index = uri.getQueryParameter("index")?.toIntOrNull()
        val files = o.optJSONArray("file_stats")
        val fileBytes = if (index == null || files == null) 0L
            else (0 until files.length()).map { files.getJSONObject(it) }.firstOrNull { it.optInt("id") == index }?.optLong("length") ?: 0L
        Stat(o.optDouble("download_speed", 0.0) * 8 / 1e6, o.optInt("active_peers"), o.optInt("connected_seeders"),
            complete = size > 0 && loaded >= size * 0.99, fileBytes = fileBytes)
    }.getOrNull()
}

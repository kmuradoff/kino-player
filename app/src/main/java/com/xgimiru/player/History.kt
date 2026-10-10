package com.xgimiru.player

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Где остановились: по одной записи на видео, новые сверху. */
object History {
    data class Entry(val key: String, val uri: String, val title: String, val position: Long, val duration: Long, val updated: Long) {
        val progress get() = if (duration > 0) position.toFloat() / duration else 0f
        val finished get() = duration > 0 && (position > duration * 0.95 || duration - position < 90_000)
    }

    private const val PREFS = "history"
    private const val KEY = "items"
    private const val LIMIT = 40

    /**
     * Ключ видео. У ссылок TorrServe меняется только «мусор» (play, stat…),
     * а hash торрента и номер файла постоянны — по ним и узнаём фильм.
     */
    fun key(uri: Uri): String {
        val link = uri.getQueryParameter("link")
        val index = uri.getQueryParameter("index")
        return if (link != null) "ts:${link.substringAfter("btih:").take(40).lowercase()}:$index"
        else uri.buildUpon().clearQuery().build().toString()
    }

    fun all(ctx: Context): List<Entry> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]")
        val arr = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Entry(o.getString("k"), o.getString("u"), o.optString("t"), o.optLong("p"), o.optLong("d"), o.optLong("w"))
        }
    }

    fun get(ctx: Context, key: String) = all(ctx).firstOrNull { it.key == key }

    fun save(ctx: Context, e: Entry) {
        val list = listOf(e) + all(ctx).filter { it.key != e.key }
        val arr = JSONArray()
        for (x in list.take(LIMIT)) arr.put(JSONObject()
            .put("k", x.key).put("u", x.uri).put("t", x.title).put("p", x.position).put("d", x.duration).put("w", x.updated))
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    fun remove(ctx: Context, key: String) {
        val arr = JSONArray()
        for (x in all(ctx).filter { it.key != key }) arr.put(JSONObject()
            .put("k", x.key).put("u", x.uri).put("t", x.title).put("p", x.position).put("d", x.duration).put("w", x.updated))
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }
}

/** Скорость и пиры торрента — показываем, пока идёт загрузка. */
object TorrServe {
    data class Stat(val speedMbit: Double, val peers: Int, val seeders: Int, val complete: Boolean)

    fun isTorrServe(uri: Uri) = uri.getQueryParameter("link") != null && uri.path?.contains("/stream") == true

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
        Stat(o.optDouble("download_speed", 0.0) * 8 / 1e6, o.optInt("active_peers"), o.optInt("connected_seeders"),
            complete = size > 0 && loaded >= size * 0.99)
    }.getOrNull()
}

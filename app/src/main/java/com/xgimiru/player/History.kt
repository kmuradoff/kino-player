package com.xgimiru.player

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** Где остановились: по одной записи на видео, новые сверху. */
object History {
    /** [playlist] — id списка серий в [Playlists], из которого открыта эта серия (null — одиночное видео). */
    data class Entry(val key: String, val uri: String, val title: String, val position: Long, val duration: Long, val updated: Long,
                     val playlist: String? = null) {
        val progress get() = if (duration > 0) position.toFloat() / duration else 0f
        val finished get() = duration > 0 && (position > duration * 0.95 || duration - position < 90_000)
    }

    private const val PREFS = "history"
    private const val KEY = "items"
    private const val WATCHED = "watched"
    private const val LIMIT = 40

    /**
     * Ключ видео: ссылка без параметров. Ссылки с link= и index= (потоковые серверы других приложений)
     * узнаются по hash и номеру файла, так старая история остаётся действительной.
     */
    fun key(uri: Uri): String {
        val link = uri.getQueryParameter("link")
        val index = uri.getQueryParameter("index")
        return if (link != null) "ts:${link.substringAfter("btih:").take(40).lowercase()}:$index"
        else uri.buildUpon().clearQuery().build().toString()
    }

    fun all(ctx: Context): List<Entry> {
        return read(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]"))
    }

    fun get(ctx: Context, key: String) = all(ctx).firstOrNull { it.key == key }

    fun save(ctx: Context, e: Entry) {
        write(ctx, (listOf(e) + all(ctx).filter { it.key != e.key }).take(LIMIT))
        WatchNext.publish(ctx, e)
    }

    fun remove(ctx: Context, key: String) {
        write(ctx, all(ctx).filter { it.key != key })
        WatchNext.remove(ctx, key)
    }

    /** Films and episodes watched to the end: "Недавно смотрели" and the check mark in the folder list. */
    fun markWatched(ctx: Context, e: Entry) {
        val done = e.copy(position = e.duration, playlist = null)
        writeWatched(ctx, (listOf(done) + watched(ctx).filter { it.key != e.key }).take(LIMIT))
    }

    fun watched(ctx: Context): List<Entry> = read(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(WATCHED, "[]"))

    /** Started and watched, newest first, one entry per video. */
    fun recent(ctx: Context): List<Entry> = (all(ctx) + watched(ctx)).sortedByDescending { it.updated }.distinctBy { it.key }

    /** Long OK in a folder: from the start, and gone from Continue and from the watched list. */
    fun forget(ctx: Context, key: String) {
        remove(ctx, key)
        writeWatched(ctx, watched(ctx).filter { it.key != key })
    }

    private fun writeWatched(ctx: Context, list: List<Entry>) {
        val arr = JSONArray()
        for (x in list) arr.put(JSONObject().put("k", x.key).put("u", x.uri).put("t", x.title).put("p", x.position).put("d", x.duration).put("w", x.updated))
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(WATCHED, arr.toString()).apply()
    }

    private fun read(raw: String?): List<Entry> {
        val arr = runCatching { JSONArray(raw ?: "[]") }.getOrDefault(JSONArray())
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Entry(o.getString("k"), o.getString("u"), o.optString("t"), o.optLong("p"), o.optLong("d"), o.optLong("w"),
                o.optString("l").ifEmpty { null })
        }
    }

    private fun write(ctx: Context, list: List<Entry>) {
        val arr = JSONArray()
        for (x in list) arr.put(JSONObject()
            .put("k", x.key).put("u", x.uri).put("t", x.title).put("p", x.position).put("d", x.duration).put("w", x.updated)
            .apply { x.playlist?.let { put("l", it) } })
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
        Playlists.prune(ctx, list.mapNotNullTo(HashSet()) { it.playlist })
    }
}

/**
 * Списки серий, из которых открывали видео (Лампа передаёт сериал списком video_list). «Продолжить просмотр»
 * в Lumen Play и в лаунчере открывает одну ссылку; по [History.Entry.playlist] плеер находит весь список и
 * после этой серии играет следующую. Отдельный файл: позиция пишется часто, а список не меняется.
 */
object Playlists {
    data class Item(val uri: String, val name: String, val subs: List<String>)

    private const val PREFS = "playlists"

    /** Id списка: по ключам серий, так что тот же сериал из другого запуска Лампы — тот же id. */
    fun id(keys: List<String>): String {
        val md = java.security.MessageDigest.getInstance("SHA-1")
        for (k in keys) { md.update(k.toByteArray()); md.update(0) }
        return md.digest().take(10).joinToString("") { "%02x".format(it) }
    }

    fun put(ctx: Context, id: String, items: List<Item>) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.contains(id)) return
        val arr = JSONArray()
        for (i in items) arr.put(JSONObject().put("u", i.uri).put("n", i.name).put("s", JSONArray(i.subs)))
        p.edit().putString(id, arr.toString()).apply()
    }

    fun get(ctx: Context, id: String): List<Item>? {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(id, null) ?: return null
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val s = o.optJSONArray("s")
                Item(o.getString("u"), o.optString("n"), (0 until (s?.length() ?: 0)).map { s!!.getString(it) })
            }
        }.getOrNull()
    }

    /** Убрать списки, на которые не ссылается ни одна запись истории. */
    fun prune(ctx: Context, keep: Set<String>) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val gone = p.all.keys.filter { it !in keep }
        if (gone.isNotEmpty()) p.edit().apply { gone.forEach { remove(it) } }.apply()
    }
}

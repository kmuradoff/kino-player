package com.xgimiru.player.settings

import android.content.Context
import android.content.SharedPreferences

/** Settings in the app's "player" preferences. Values of any stored type are read as text (1.3 stored booleans and ints). */
class PrefsStore(private val p: SharedPreferences) : Store {
    override fun get(key: String): String? = p.all[key]?.toString()
    override fun put(key: String, value: String?) {
        p.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }
}

fun Context.playerSettings() = Settings(PrefsStore(getSharedPreferences("player", Context.MODE_PRIVATE)))

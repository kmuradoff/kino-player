package com.xgimiru.player

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Old entry to the player settings (notifications of casting and TorrServer, the player panel): the settings
 * now live on the main screen next to the left list, this only opens them there.
 */
class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("open", "set")
            .putExtra("section", intent.getStringExtra("section") ?: "video")
            .putExtra("focus", intent.getStringExtra("focus")))
        finish()
    }
}

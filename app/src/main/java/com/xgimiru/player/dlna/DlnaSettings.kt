package com.xgimiru.player.dlna

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.xgimiru.player.ui.tr
import java.util.UUID

/** Настройка «Трансляция с телефона» (DLNA) и постоянный идентификатор устройства. */
object DlnaSettings {

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("dlna", Context.MODE_PRIVATE)

    fun enabled(ctx: Context) = prefs(ctx).getBoolean("enabled", true)

    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("enabled", on).apply()
        DlnaService.sync(ctx)
    }

    /** UDN не меняется между запусками: иначе контроллеры видят каждый раз новый телевизор. */
    fun uuid(ctx: Context): String {
        val p = prefs(ctx)
        p.getString("uuid", null)?.let { return it }
        val u = UUID.randomUUID().toString()
        p.edit().putString("uuid", u).apply()
        return u
    }

    /** Имя устройства из системных настроек, иначе модель. */
    fun deviceName(ctx: Context): String =
        runCatching { Settings.Global.getString(ctx.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()
            ?.trim()?.takeIf { it.isNotEmpty() } ?: Build.MODEL

    fun friendlyName(ctx: Context): String {
        val d = deviceName(ctx).take(48)
        return tr("Lumen Play на $d", "Lumen Play on $d")
    }

    /** Без разрешения «поверх других окон» Android не даёт открыть плеер, пока Lumen Play в фоне. */
    fun canOpenFromBackground(ctx: Context) = Settings.canDrawOverlays(ctx)

    /** Screen where the user allows opening over other apps. */
    fun overlayIntent(a: Activity): Intent? = listOf(
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${a.packageName}")),
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
    ).firstOrNull { it.resolveActivity(a.packageManager) != null }
}

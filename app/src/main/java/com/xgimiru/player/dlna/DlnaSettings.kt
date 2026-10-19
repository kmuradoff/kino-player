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

    /**
     * Раздел в настройках. header и row: функции SettingsActivity (row: текст, подсказка,
     * отмечен ли пункт, действие), чтобы раздел выглядел как остальные.
     */
    fun section(a: Activity, header: (String) -> Unit, row: (String, String?, Boolean?, () -> Unit) -> Unit) {
        val on = enabled(a)
        header(tr("Трансляция с телефона и компьютера", "Casting from phones and computers"))
        row(tr("Включено", "On"), tr(
            "Lumen Play виден как телевизор «${friendlyName(a)}» в приложениях с кнопкой «На телевизор» (DLNA): " +
                "BubbleUPnP, Web Video Cast, VLC, «Передать на устройство» в Windows",
            "Lumen Play shows up as the TV \"${friendlyName(a)}\" in apps that send to a TV over DLNA: " +
                "BubbleUPnP, Web Video Cast, VLC, Windows \"Cast to device\""), on) { setEnabled(a, true) }
        row(tr("Выключено", "Off"), null, !on) { setEnabled(a, false) }
        if (on && !canOpenFromBackground(a)) {
            row(tr("Разрешить открываться поверх других приложений…", "Allow opening over other apps…"),
                tr("Нужно, чтобы видео с телефона открывалось, даже когда Lumen Play не на экране",
                    "Needed so videos from a phone open even when Lumen Play is not on screen"), null) {
                runCatching {
                    a.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${a.packageName}")))
                }
            }
        }
    }
}

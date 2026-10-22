package com.xgimiru.player.dovi  // SPDX-License-Identifier: Apache-2.0

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.os.Build
import android.view.Display
import androidx.media3.common.MimeTypes

/**
 * Как играть HDR-видео, по образцу Kodi: если экран и декодер умеют Dolby Vision, всё делает
 * «железо» (профиль 7 переводится в 8.1), иначе — программно (профиль 5, наш шейдер) или как HDR10.
 */
object HdrPolicy {

    /** Что умеет устройство. [dvProfiles] — профили DV, которые знает аппаратный декодер. */
    data class Caps(val dvDisplay: Boolean, val hdr10PlusDisplay: Boolean, val dvProfiles: Set<Int>) {
        val dvHardware get() = dvDisplay && dvProfiles.isNotEmpty()
    }

    /** Настройки пользователя (Настройки → Видео). */
    data class Prefs(
        val dvHardware: Boolean = true,   // DV через экран, если он умеет
        val p7to81: Boolean = true,       // профиль 7 → 8.1 (почти ни один декодер не знает 7)
        val zeroL5: Boolean = false,      // обнулить L5 (поля кадра)
        val hdr10Plus: Boolean = true,    // динамические метаданные HDR10+
    )

    enum class Route {
        PASS,       // как есть
        SOFT5,      // DV профиля 5: свой GL-рендер
        STRIP,      // DV → HDR10: убрать RPU и слой улучшения, отдать обычному HEVC-декодеру
        DV_EDIT,    // DV в аппаратный декодер, RPU правим (7 → 8.1, L5, без HDR10+)
        HEVC_EDIT,  // обычный HEVC: убрать HDR10+
    }

    data class Plan(
        val route: Route,
        val profile: Int = 0,
        val toP81: Boolean = false,
        val zeroL5: Boolean = false,
        val dropHdr10Plus: Boolean = false,
        val codecs: String? = null,       // новая строка codecs для декодера (7 → 8.1)
    ) {
        /** Подпись для строки сведений о видео. */
        val label: String? get() = when (route) {
            Route.SOFT5 -> "Dolby Vision 5"
            Route.STRIP -> "Dolby Vision $profile → HDR10"
            Route.DV_EDIT, Route.PASS -> if (profile == 0) null
                else if (toP81) "Dolby Vision 7 → 8.1" else "Dolby Vision $profile"
            Route.HEVC_EDIT -> null
        }
    }

    fun plan(mime: String?, codecs: String?, caps: Caps, prefs: Prefs): Plan {
        val dropH = !prefs.hdr10Plus
        val c = codecs.orEmpty()
        if (mime == MimeTypes.VIDEO_DOLBY_VISION && (c.startsWith("dvhe.") || c.startsWith("dvh1."))) {
            val parts = c.split('.')
            val profile = parts.getOrNull(1)?.toIntOrNull() ?: 0
            if (caps.dvHardware && prefs.dvHardware) {
                fun hw(p: Int, toP81: Boolean = false, codecs: String? = null): Plan {
                    val edit = toP81 || prefs.zeroL5 || dropH
                    return Plan(if (edit) Route.DV_EDIT else Route.PASS, p, toP81, prefs.zeroL5, dropH, codecs)
                }
                when {
                    profile == 7 && prefs.p7to81 && 8 in caps.dvProfiles ->
                        return hw(7, toP81 = true, codecs = "${parts[0]}.08.${parts.getOrElse(2) { "06" }}")
                    profile in caps.dvProfiles -> return hw(profile)
                }
            }
            return when (profile) {
                5 -> Plan(Route.SOFT5, 5)
                7, 8 -> Plan(Route.STRIP, profile, dropHdr10Plus = dropH)
                else -> Plan(Route.PASS, profile)
            }
        }
        if (dropH && mime == MimeTypes.VIDEO_H265) return Plan(Route.HEVC_EDIT, dropHdr10Plus = true)
        return Plan(Route.PASS)
    }

    @Volatile private var cached: Caps? = null

    fun caps(ctx: Context): Caps = cached ?: detect(ctx).also { cached = it }

    private fun detect(ctx: Context): Caps {
        val types = HashSet<Int>()
        runCatching {
            val d = ctx.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            @Suppress("DEPRECATION")
            d.hdrCapabilities?.supportedHdrTypes?.forEach { types += it }
            if (Build.VERSION.SDK_INT >= 34) d.mode.supportedHdrTypes.forEach { types += it }
        }
        val profiles = HashSet<Int>()
        runCatching {
            for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
                if (info.isEncoder || MimeTypes.VIDEO_DOLBY_VISION !in info.supportedTypes.map { it.lowercase() }) continue
                for (pl in info.getCapabilitiesForType(MimeTypes.VIDEO_DOLBY_VISION).profileLevels) {
                    when (pl.profile) {
                        CodecProfileLevel.DolbyVisionProfileDvheDtr -> profiles += 4
                        CodecProfileLevel.DolbyVisionProfileDvheStn -> profiles += 5
                        CodecProfileLevel.DolbyVisionProfileDvheDtb -> profiles += 7
                        CodecProfileLevel.DolbyVisionProfileDvheSt -> profiles += 8
                    }
                }
            }
        }
        return Caps(
            dvDisplay = Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION in types,
            hdr10PlusDisplay = Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS in types,
            dvProfiles = profiles,
        )
    }

    fun prefs(ctx: Context): Prefs {
        val p = ctx.getSharedPreferences("player", Context.MODE_PRIVATE)
        return Prefs(
            dvHardware = p.getBoolean("dvHardware", true),
            p7to81 = p.getBoolean("dvP7to81", true),
            zeroL5 = p.getBoolean("dvZeroL5", false),
            hdr10Plus = p.getBoolean("hdr10Plus", true),
        )
    }

    fun set(ctx: Context, key: String, v: Boolean) =
        ctx.getSharedPreferences("player", Context.MODE_PRIVATE).edit().putBoolean(key, v).apply()
}

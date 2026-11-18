package com.xgimiru.player.settings

import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView

/**
 * Subtitle look from the settings, shared by the player and the settings preview, so the preview shows
 * exactly what the film will show (same view, same colours, same size in pixels).
 */
object SubStyle {
    fun foreground(cfg: Settings) = when (cfg.string(K.SUB_COLOR)) {
        "yellow" -> 0xFFFFE14D.toInt(); "grey" -> 0xFFD2D2D2.toInt(); "cyan" -> 0xFF7FE3FF.toInt(); "green" -> 0xFF8DEB8D.toInt()
        else -> Color.WHITE
    }

    /** Text size in pixels on a screen [screenHeightPx] tall, as the player draws it. */
    fun textPx(cfg: Settings, screenHeightPx: Int) = SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * cfg.subSize / 100f * screenHeightPx

    /** [fixedPx]: the preview passes the player's real size, the player itself scales with its full-screen view. */
    fun apply(v: SubtitleView, cfg: Settings, fixedPx: Float? = null) {
        val bg = when (cfg.string(K.SUB_BACK)) { "half" -> 0x99000000.toInt(); "solid" -> Color.BLACK; else -> Color.TRANSPARENT }
        val edge = when (cfg.string(K.SUB_EDGE)) {
            "shadow" -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW; "raised" -> CaptionStyleCompat.EDGE_TYPE_RAISED
            "none" -> CaptionStyleCompat.EDGE_TYPE_NONE; else -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
        }
        val face = Typeface.create("sans-serif-medium", if (cfg.bool(K.SUB_BOLD)) Typeface.BOLD else Typeface.NORMAL)
        v.setStyle(CaptionStyleCompat(foreground(cfg), bg, Color.TRANSPARENT, edge, Color.argb(230, 0, 0, 0), face))
        if (fixedPx != null) v.setFixedTextSize(TypedValue.COMPLEX_UNIT_PX, fixedPx)
        else v.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * cfg.subSize / 100f)
        v.setBottomPaddingFraction(cfg.subPosition / 100f)
        val own = cfg.bool(K.SUB_STYLES)
        v.setApplyEmbeddedStyles(own)
        v.setApplyEmbeddedFontSizes(own)
    }
}

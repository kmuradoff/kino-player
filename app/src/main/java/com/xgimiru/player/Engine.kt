package com.xgimiru.player

import android.net.Uri

/**
 * Движок воспроизведения. Интерфейс «Кино» один, движков два:
 *  - [ExoEngine] (Media3) — современные MKV/MP4/TS: правильный Dolby Vision и плавный 4K HDR;
 *  - [VlcEngine] (libVLC) — старьё вроде AVI/XviD и запасной вариант, если Media3 не справился.
 */
interface Engine {

    interface Listener {
        /** Играет / пауза — поменялось. */
        fun onStateChanged()
        /** Загрузка: [percent] 0…100 или -1, если движок процентов не знает. */
        fun onBuffering(active: Boolean, percent: Int)
        fun onTracksChanged()
        fun onEnded()
        /** [fallback] — стоит попробовать другой движок (формат/кодек не пошёл). */
        fun onError(message: String, fallback: Boolean)
    }

    data class Track(val id: String, val label: String, val selected: Boolean)

    val position: Long
    val duration: Long
    val buffered: Long
    val isPlaying: Boolean
    var speed: Float

    fun open(uri: Uri, startMs: Long, subs: List<Uri>)
    fun play()
    fun pause()
    fun seekTo(ms: Long)

    fun audioTracks(): List<Track>
    fun textTracks(): List<Track>
    fun selectAudio(id: String)
    /** null — выключить субтитры. */
    fun selectText(id: String?)

    /** «4K · HEVC · Dolby Vision» */
    fun videoInfo(): String?
    fun audioInfo(): String?
    /** Язык включённых субтитров или null, если выключены. */
    fun textInfo(): String?

    /** 0 — целиком, 1 — заполнить с обрезкой, 2 — растянуть. */
    fun setScale(mode: Int)

    fun release()

    companion object {
        /** Форматы, которые Media3 играет плохо или не играет вовсе. */
        private val LEGACY = setOf("avi", "divx", "wmv", "asf", "flv", "vob", "mpg", "mpeg", "rm", "rmvb", "3gp", "ogm")

        fun prefersVlc(uri: Uri): Boolean =
            uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase() in LEGACY
    }
}

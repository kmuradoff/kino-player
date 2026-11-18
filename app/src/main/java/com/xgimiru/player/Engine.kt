package com.xgimiru.player

import android.net.Uri

/**
 * Движок воспроизведения. Интерфейс «Кино» один, движков два:
 *  - [ExoEngine] (Media3) — современные MKV/MP4/TS: правильный Dolby Vision и плавный 4K HDR;
 *  - [VlcEngine] (libVLC) — старьё вроде AVI/XviD, сетевые папки (SMB, NFS, FTP) и запасной вариант.
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
        /** Движок сам встал на паузу: звук ушёл с Bluetooth или наушников (AUDIO_BECOMING_NOISY). */
        fun onPausedBySystem() {}
    }

    data class Track(val id: String, val label: String, val selected: Boolean)

    val position: Long
    val duration: Long
    val buffered: Long
    val isPlaying: Boolean
    var speed: Float

    fun open(uri: Uri, startMs: Long, subs: List<Uri>)

    /**
     * Перед [open]: заголовки запроса (Referer, Origin, User-Agent, Cookie, ...) и тип потока из интента
     * (HLS/DASH или null). Действуют до следующего вызова; пустая карта — свой User-Agent и больше ничего.
     */
    fun setRequest(headers: Map<String, String>, mimeHint: String?) {}
    fun play()
    fun pause()
    fun seekTo(ms: Long)

    fun audioTracks(): List<Track>
    fun textTracks(): List<Track>
    fun selectAudio(id: String)
    /** null — выключить субтитры. */
    fun selectText(id: String?)

    /** Качество (варианты HLS/DASH). Пусто — выбирать нечего. selected — только при ручном выборе. */
    fun videoTracks(): List<Track> = emptyList()
    /** null — «Авто», адаптивный выбор по скорости сети. */
    fun selectVideo(id: String?) {}

    /** «4K · HEVC · Dolby Vision» */
    fun videoInfo(): String?
    fun audioInfo(): String?
    /** Язык включённых субтитров или null, если выключены. */
    fun textInfo(): String?

    /** 0 — целиком, 1 — заполнить с обрезкой, 2 — растянуть. */
    fun setScale(mode: Int)

    /** Соотношение сторон из настроек: "auto", "16:9", "2.35"... */
    fun setAspect(value: String) {}
    /** Задержка звука, мс: плюс — звук позже. */
    fun setAudioDelay(ms: Int) {}
    /** Задержка субтитров, мс: плюс — позже. */
    fun setSubtitleDelay(ms: Int) {}
    /** Кодировка внешних субтитров для движка, который читает их сам (VLC). null — как решит движок. */
    fun setSubtitleEncoding(charset: String?) {}

    fun release()

    companion object {
        /** Форматы, которые Media3 играет плохо или не играет вовсе. */
        val LEGACY = setOf("avi", "divx", "wmv", "asf", "flv", "vob", "mpg", "mpeg", "rm", "rmvb", "3gp", "ogm")

        /** Сетевые папки, которые умеет только libVLC. */
        val VLC_SCHEMES = setOf("smb", "nfs", "ftp", "ftps", "sftp")

        fun prefersVlc(uri: Uri): Boolean = uri.scheme?.lowercase() in VLC_SCHEMES ||
            uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase() in LEGACY
    }
}

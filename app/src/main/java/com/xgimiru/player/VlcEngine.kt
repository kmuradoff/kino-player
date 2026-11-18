package com.xgimiru.player

import android.content.Context
import android.net.Uri
import android.widget.FrameLayout
import com.xgimiru.player.settings.K
import com.xgimiru.player.settings.Settings
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout
import java.util.Locale
import kotlin.math.pow

/** Движок libVLC: играет всё, включая AVI/XviD и сетевые папки. Картинка — на SurfaceView, без OpenGL. */
class VlcEngine(ctx: Context, private val host: FrameLayout, private val listener: Engine.Listener,
                private val cfg: Settings) : Engine {

    // Незнакомый ключ ломает запуск libVLC: тогда запасной набор, с которым играла 1.3.
    private val vlc = runCatching { LibVLC(ctx, options()) }.getOrElse {
        android.util.Log.w("VlcEngine", "options refused, minimal set", it)
        LibVLC(ctx, arrayListOf("--audio-time-stretch", "--no-stats"))
    }
    private val player = MediaPlayer(vlc)
    private val layout = VLCVideoLayout(ctx)
    private var length = 0L
    private var audioDelayMs = cfg.int(K.AUDIO_DELAY)
    private var subDelayMs = cfg.int(K.SUB_DELAY)
    private var subCharset: String? = null
    private var started = false

    init {
        host.addView(layout, FrameLayout.LayoutParams(-1, -1))
        player.attachViews(layout, null, true, false)
        player.setEventListener { e ->
            when (e.type) {
                MediaPlayer.Event.Playing -> { onFirstPlay(); listener.onStateChanged() }
                MediaPlayer.Event.Paused -> listener.onStateChanged()
                MediaPlayer.Event.Buffering -> listener.onBuffering(e.buffering < 100f, e.buffering.toInt())
                MediaPlayer.Event.LengthChanged -> length = e.lengthChanged
                MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESSelected -> listener.onTracksChanged()
                MediaPlayer.Event.EndReached -> listener.onEnded()
                MediaPlayer.Event.EncounteredError -> listener.onError("Источник недоступен или формат не поддерживается.", false)
            }
        }
    }

    /** Настройки, которые libVLC берёт только при создании. */
    private fun options(): ArrayList<String> {
        val o = arrayListOf("--audio-time-stretch", "--no-stats")
        // субтитры
        o += "--freetype-rel-fontsize=" + when (cfg.subSize) { in 0..75 -> 20; in 76..90 -> 18; in 91..105 -> 16; in 106..125 -> 14; in 126..145 -> 12; else -> 10 }
        if (cfg.bool(K.SUB_BOLD)) o += "--freetype-bold"
        o += "--freetype-color=" + when (cfg.string(K.SUB_COLOR)) {
            "yellow" -> 0xFFE14D; "grey" -> 0xD2D2D2; "cyan" -> 0x7FE3FF; "green" -> 0x8DEB8D; else -> 0xFFFFFF
        }
        when (cfg.string(K.SUB_EDGE)) {
            "none" -> o += "--freetype-outline-thickness=0"
            "shadow" -> { o += "--freetype-outline-thickness=0"; o += "--freetype-shadow-opacity=200" }
            "raised" -> { o += "--freetype-outline-thickness=2"; o += "--freetype-shadow-opacity=160" }
            else -> o += "--freetype-outline-thickness=4"
        }
        when (cfg.string(K.SUB_BACK)) {
            "half" -> { o += "--freetype-background-opacity=150"; o += "--freetype-background-color=0" }
            "solid" -> { o += "--freetype-background-opacity=255"; o += "--freetype-background-color=0" }
        }
        o += "--sub-margin=" + cfg.subPosition * 7
        if (!cfg.bool(K.SUB_AUTO)) o += "--no-sub-autodetect-file"
        cfg.string(K.SUB_ENCODING).takeIf { it != "auto" }?.let { o += "--subsdec-encoding=$it" }
        val sys = Locale.getDefault().language
        when (val a = cfg.string(K.AUDIO_LANG)) { "original" -> Unit; "system" -> o += "--audio-language=$sys"; else -> o += "--audio-language=$a" }
        when (val t = cfg.string(K.SUB_LANG)) {
            "file", "forced", "off" -> Unit
            else -> o += "--sub-language=" + listOfNotNull(if (t == "system") sys else t, cfg.string(K.SUB_LANG2).takeIf { it != "none" }).joinToString(",")
        }
        // звук
        if (cfg.bool(K.PASSTHROUGH)) o += "--spdif"
        if (cfg.boostDb > 0) o += "--gain=" + "%.2f".format(Locale.US, 10.0.pow(cfg.boostDb / 20.0))
        if (cfg.bool(K.NIGHT)) o += "--audio-filter=compressor"
        // видео
        when (val d = cfg.string(K.DEINTERLACE)) {
            "auto" -> o += "--deinterlace=-1"
            "off" -> o += "--deinterlace=0"
            else -> { o += "--deinterlace=1"; o += "--deinterlace-mode=$d" }
        }
        return o
    }

    private fun onFirstPlay() {
        if (started) return
        started = true
        player.setAudioDelay(audioDelayMs * 1000L)
        player.setSpuDelay(subDelayMs * 1000L)
        if (cfg.string(K.SUB_LANG) == "off") player.spuTrack = -1
    }

    override val position get() = player.time.coerceAtLeast(0)
    override val duration get() = length.takeIf { it > 0 } ?: player.length.coerceAtLeast(0)
    override val buffered get() = 0L
    override val isPlaying get() = player.isPlaying
    override var speed: Float
        get() = player.rate
        set(v) { player.rate = v }

    private var headers: Map<String, String> = emptyMap()

    override fun setRequest(headers: Map<String, String>, mimeHint: String?) { this.headers = headers }
    override fun setSubtitleEncoding(charset: String?) { subCharset = charset }

    override fun open(uri: Uri, startMs: Long, subs: List<Uri>) {
        length = 0
        started = false
        val media = Media(vlc, uri)
        media.addOption(":network-caching=4000")
        // libVLC умеет только User-Agent и Referer; Cookie и Origin на запасном движке теряются.
        media.addOption(":http-user-agent=" + StreamRequest.userAgent(headers))
        StreamRequest.referer(headers)?.let { media.addOption(":http-referrer=$it") }
        if (startMs > 0) media.addOption(":start-time=%.1f".format(Locale.US, startMs / 1000.0))
        subCharset?.let { media.addOption(":subsdec-encoding=$it") }
        // Старый XviD/DivX — программным декодером: он лёгкий, а аппаратный с ним капризничает.
        val legacy = uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase() in Engine.LEGACY
        when (cfg.string(K.DECODER)) {
            "sw" -> media.setHWDecoderEnabled(false, false)
            "hw" -> media.setHWDecoderEnabled(true, true)
            else -> media.setHWDecoderEnabled(!legacy, false)
        }
        player.media = media
        media.release()
        for (s in subs) player.addSlave(IMedia.Slave.Type.Subtitle, s, false)
        player.play()
    }

    override fun play() = player.play()
    override fun pause() = player.pause()
    override fun seekTo(ms: Long) { player.time = ms }

    /** Описания дорожек из медиа: язык, кодек, каналы. */
    private fun mediaTracks(): List<IMedia.Track> {
        val m = player.media ?: return emptyList()
        return try { (0 until m.trackCount).mapNotNull { m.getTrack(it) } } finally { m.release() }
    }

    override fun audioTracks(): List<Engine.Track> {
        val info = mediaTracks().filterIsInstance<IMedia.AudioTrack>()
        return player.audioTracks.orEmpty().filter { it.id >= 0 }.map { t ->
            Engine.Track(t.id.toString(), Formats.audio(info.firstOrNull { it.id == t.id }, t.name), player.audioTrack == t.id)
        }
    }

    override fun textTracks(): List<Engine.Track> {
        val info = mediaTracks().filterIsInstance<IMedia.SubtitleTrack>()
        return player.spuTracks.orEmpty().filter { it.id >= 0 }.map { t ->
            Engine.Track(t.id.toString(), Formats.text(info.firstOrNull { it.id == t.id }, t.name), player.spuTrack == t.id)
        }
    }

    override fun selectAudio(id: String) { player.audioTrack = id.toInt() }
    override fun selectText(id: String?) { player.spuTrack = id?.toInt() ?: -1 }

    override fun videoInfo() = Formats.video(mediaTracks().filterIsInstance<IMedia.VideoTrack>().firstOrNull())

    override fun audioInfo(): String? {
        val id = player.audioTrack
        return mediaTracks().filterIsInstance<IMedia.AudioTrack>().firstOrNull { it.id == id }
            ?.let { Formats.audio(it, "") }?.takeIf { it.isNotBlank() }
    }

    override fun textInfo(): String? {
        val id = player.spuTrack
        if (id < 0) return null
        return mediaTracks().filterIsInstance<IMedia.SubtitleTrack>().firstOrNull { it.id == id }
            ?.let { Formats.language(it.language) } ?: "вкл"
    }

    override fun setScale(mode: Int) {
        player.videoScale = when (mode) {
            1 -> MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
            2 -> MediaPlayer.ScaleType.SURFACE_FILL
            else -> MediaPlayer.ScaleType.SURFACE_BEST_FIT
        }
    }

    override fun setAspect(value: String) {
        player.aspectRatio = when (value) {
            "auto" -> null
            "1.85" -> "185:100"
            "2.35" -> "235:100"
            else -> value
        }
    }

    override fun setAudioDelay(ms: Int) { audioDelayMs = ms; player.setAudioDelay(ms * 1000L) }
    override fun setSubtitleDelay(ms: Int) { subDelayMs = ms; player.setSpuDelay(ms * 1000L) }

    override fun release() {
        player.setEventListener(null)
        player.stop()
        player.detachViews()
        player.release()
        vlc.release()
        host.removeView(layout)
    }
}

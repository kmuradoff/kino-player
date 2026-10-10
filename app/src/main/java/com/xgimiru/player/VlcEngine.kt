package com.xgimiru.player

import android.content.Context
import android.net.Uri
import android.widget.FrameLayout
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout
import java.util.Locale

/** Движок libVLC: играет всё, включая AVI/XviD. Картинка — на SurfaceView, без OpenGL. */
class VlcEngine(ctx: Context, private val host: FrameLayout, private val listener: Engine.Listener) : Engine {

    private val vlc = LibVLC(ctx, arrayListOf(
        "--audio-time-stretch",              // скорость ×1.5 без «бурундуков»
        "--freetype-rel-fontsize=14",        // субтитры крупнее обычного
        "--freetype-bold",
        "--freetype-outline-thickness=4",
        "--freetype-color=16777215",
        "--sub-margin=40",
        "--no-stats",
    ))
    private val player = MediaPlayer(vlc)
    private val layout = VLCVideoLayout(ctx)
    private var length = 0L

    init {
        host.addView(layout, FrameLayout.LayoutParams(-1, -1))
        player.attachViews(layout, null, true, false)
        player.setEventListener { e ->
            when (e.type) {
                MediaPlayer.Event.Playing, MediaPlayer.Event.Paused -> listener.onStateChanged()
                MediaPlayer.Event.Buffering -> listener.onBuffering(e.buffering < 100f, e.buffering.toInt())
                MediaPlayer.Event.LengthChanged -> length = e.lengthChanged
                MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESSelected -> listener.onTracksChanged()
                MediaPlayer.Event.EndReached -> listener.onEnded()
                MediaPlayer.Event.EncounteredError -> listener.onError("Источник недоступен или формат не поддерживается.", false)
            }
        }
    }

    override val position get() = player.time.coerceAtLeast(0)
    override val duration get() = length.takeIf { it > 0 } ?: player.length.coerceAtLeast(0)
    override val buffered get() = 0L
    override val isPlaying get() = player.isPlaying
    override var speed: Float
        get() = player.rate
        set(v) { player.rate = v }

    override fun open(uri: Uri, startMs: Long, subs: List<Uri>) {
        length = 0
        val media = Media(vlc, uri)
        media.addOption(":network-caching=4000")
        media.addOption(":http-user-agent=Kino/1.0 (Android TV)")
        if (startMs > 0) media.addOption(":start-time=%.1f".format(Locale.US, startMs / 1000.0))
        // Старый XviD/DivX — программным декодером: он лёгкий, а аппаратный с ним капризничает.
        media.setHWDecoderEnabled(!Engine.prefersVlc(uri), false)
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

    override fun release() {
        player.setEventListener(null)
        player.stop()
        player.detachViews()
        player.release()
        vlc.release()
        host.removeView(layout)
    }
}

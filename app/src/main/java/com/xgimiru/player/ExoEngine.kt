package com.xgimiru.player

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import android.os.Handler
import android.os.Looper
import android.view.SurfaceView
import android.view.View
import androidx.media3.common.VideoSize
import com.xgimiru.player.dovi.DoviRenderer
import com.xgimiru.player.dovi.DoviStore
import com.xgimiru.player.dovi.RpuTapExtractorsFactory
import java.util.Locale

/**
 * Движок Media3: аппаратные декодеры чипа напрямую, включая Dolby Vision
 * (c2.mtk.dvhe.*), и точная синхронизация кадров для 4K HDR.
 */
@OptIn(UnstableApi::class)
class ExoEngine(private val ctx: Context, private val host: FrameLayout, private val listener: Engine.Listener) : Engine {

    /** Dolby Vision профиля 5: метаданные из файла и свой GL-рендер (см. [DoviRenderer]). */
    private val dovi = DoviStore()
    private var doviRenderer: DoviRenderer? = null
    private var doviView: SurfaceView? = null
    private val main = Handler(Looper.getMainLooper())
    /** Вывод DV5: true — HDR10 (слой помечен BT.2020 PQ), false — свой тон-маппинг в SDR. */
    private lateinit var trackSelector: DefaultTrackSelector
    // Туннель выключен по умолчанию: в туннеле звук идёт в аудиотракт MediaTek мимо микшера Android,
    // и громкость системы к нему не применяется (кнопки громкости «не работали»). С ним же второе видео
    // подряд падало в MediaCodecVideoRenderer.onEnabled. Включается только вручную (pref "tunneling").
    private val tunneling = ctx.getSharedPreferences("player", Context.MODE_PRIVATE).getBoolean("tunneling", false)

    var doviHdr = ctx.getSharedPreferences("player", Context.MODE_PRIVATE).getBoolean("doviHdr", false)
        set(v) {
            field = v
            ctx.getSharedPreferences("player", Context.MODE_PRIVATE).edit().putBoolean("doviHdr", v).apply()
        }

    private val view = PlayerView(ctx).apply {
        useController = false
        setShutterBackgroundColor(Color.BLACK)
        setKeepContentOnPlayerReset(true)
        subtitleView?.apply {
            setStyle(CaptionStyleCompat(Color.WHITE, Color.TRANSPARENT, Color.TRANSPARENT,
                CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.argb(230, 0, 0, 0),
                Typeface.create("sans-serif-medium", Typeface.BOLD)))
            setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 1.15f)
            setBottomPaddingFraction(0.06f)
            setApplyEmbeddedFontSizes(false)
        }
    }

    private val player: ExoPlayer

    init {
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(30_000)
            .setReadTimeoutMs(60_000)          // TorrServe может долго искать пиров
            .setUserAgent("Kino/1.0 (Android TV)")
        val renderers = DefaultRenderersFactory(ctx)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON) // FFmpeg — если чип не умеет звук
            .setEnableDecoderFallback(true)
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(20_000, 50_000, 2_000, 5_000)
            .setTargetBufferBytes(96 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        val selector = DefaultTrackSelector(ctx).apply {
            setParameters(buildUponParameters()
                .setPreferredAudioLanguages("ru", "rus")
                .setSelectUndeterminedTextLanguage(false)
                // Туннель: кадры выводит и сверяет со звуком само «железо» видеоплоскости —
                // так играют Netflix/YouTube на ТВ. Если связка не умеет, Media3 выключит сам.
                .setTunnelingEnabled(tunneling))
        }
        trackSelector = selector
        player = ExoPlayer.Builder(ctx, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(ctx, http), RpuTapExtractorsFactory(dovi)))
            .setLoadControl(load)
            .setTrackSelector(selector)
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS)
        view.player = player
        host.addView(view, FrameLayout.LayoutParams(-1, -1))
        dovi.onActivated = {
            // DV5 рисует наш шейдер — кадры нужны приложению, туннель тут невозможен.
            trackSelector.setParameters(trackSelector.buildUponParameters().setTunnelingEnabled(false))
            main.post { enableDovi() }
        }

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = listener.onStateChanged()
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> listener.onBuffering(true, -1)
                    Player.STATE_READY -> listener.onBuffering(false, 100)
                    Player.STATE_ENDED -> listener.onEnded()
                    else -> Unit
                }
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                doviRenderer?.setVideoSize(videoSize.width, videoSize.height, videoSize.pixelWidthHeightRatio)
            }
            override fun onTracksChanged(tracks: Tracks) {
                // Dolby Vision профиля 5 без HDR10-основы: в прошивке XGIMI декодер DV выключен —
                // видео не пойдёт ни в одном плеере. Честно говорим об этом вместо чёрного экрана.
                val dvOnly = tracks.groups.any { g ->
                    g.type == C.TRACK_TYPE_VIDEO && (0 until g.length).any { i ->
                        val f = g.getTrackFormat(i)
                        f.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION && !g.isTrackSupported(i, true)
                    }
                } && tracks.groups.none { g -> g.type == C.TRACK_TYPE_VIDEO && g.isSupported }
                if (dvOnly) {
                    player.stop()
                    listener.onError(DV5_MESSAGE, false)
                    return
                }
                listener.onTracksChanged()
            }
            override fun onPlayerError(e: PlaybackException) = listener.onError(describe(e), isFormatProblem(e))
        })
    }

    override val position get() = player.currentPosition.coerceAtLeast(0)
    override val duration get() = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
    override val buffered get() = player.bufferedPosition
    override val isPlaying get() = player.isPlaying
    override var speed: Float
        get() = player.playbackParameters.speed
        set(v) { player.playbackParameters = PlaybackParameters(v) }

    override fun open(uri: Uri, startMs: Long, subs: List<Uri>) {
        disableDovi()
        dovi.reset()
        trackSelector.setParameters(trackSelector.buildUponParameters().setTunnelingEnabled(tunneling))
        val subConfigs = subs.map {
            MediaItem.SubtitleConfiguration.Builder(it).setMimeType(subtitleMime(it)).setLanguage(subtitleLanguage(it)).build()
        }
        player.setMediaItem(MediaItem.Builder().setUri(uri).setSubtitleConfigurations(subConfigs).build(), startMs)
        player.prepare()
        player.play()
    }

    override fun play() = player.play()
    override fun pause() = player.pause()
    override fun seekTo(ms: Long) = player.seekTo(ms)

    private fun tracks(type: Int): List<Pair<String, Pair<Format, Boolean>>> =
        player.currentTracks.groups.withIndex().filter { it.value.type == type }.flatMap { (gi, g) ->
            (0 until g.length).filter { g.isTrackSupported(it, true) }
                .map { "$gi:$it" to (g.getTrackFormat(it) to g.isTrackSelected(it)) }
        }

    override fun audioTracks() = tracks(C.TRACK_TYPE_AUDIO).map { (id, f) -> Engine.Track(id, audioLabel(f.first), f.second) }
    override fun textTracks() = tracks(C.TRACK_TYPE_TEXT).map { (id, f) -> Engine.Track(id, textLabel(f.first), f.second) }

    override fun selectAudio(id: String) = select(C.TRACK_TYPE_AUDIO, id)

    override fun selectText(id: String?) {
        if (id == null) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        } else select(C.TRACK_TYPE_TEXT, id)
    }

    private fun select(type: Int, id: String) {
        val (gi, ti) = id.split(':').map { it.toInt() }
        val group = player.currentTracks.groups.getOrNull(gi) ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(type, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, ti))
            .build()
    }

    private fun selected(type: Int) = tracks(type).firstOrNull { it.second.second }?.second?.first

    override fun videoInfo(): String? {
        val f = selected(C.TRACK_TYPE_VIDEO) ?: return null
        val w = f.width; val h = f.height
        val res = when {
            w >= 3500 || h >= 2000 -> "4K"
            w >= 2400 -> "1440p"
            w >= 1800 || h >= 1000 -> "1080p"
            w >= 1200 || h >= 700 -> "720p"
            w > 0 -> "SD"
            else -> null
        }
        val hdr = when {
            dovi.active -> "Dolby Vision 5"
            f.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION || f.codecs?.startsWith("dvh") == true -> "Dolby Vision"
            f.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 -> "HDR10"
            f.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG -> "HLG"
            else -> null
        }
        return listOfNotNull(res, codec(f.sampleMimeType), hdr).distinct().joinToString(" · ").ifBlank { null }
    }

    override fun audioInfo() = selected(C.TRACK_TYPE_AUDIO)?.let { audioLabel(it) }
    override fun textInfo() = selected(C.TRACK_TYPE_TEXT)?.let { Formats.language(it.language) ?: it.label ?: "вкл" }

    override fun setScale(mode: Int) {
        currentScale = mode
        doviRenderer?.scaleMode = mode
        view.resizeMode = when (mode) {
            1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    override fun release() {
        doviRenderer?.let { player.clearVideoFrameMetadataListener(it) }
        player.release()
        doviRenderer?.release()
        doviView?.let { host.removeView(it) }
        host.removeView(view)
    }

    /**
     * В файле Dolby Vision профиля 5: видео идёт в наш GL-рендер (шейдер DV → HDR10),
     * а PlayerView остаётся только ради субтитров.
     */
    private fun enableDovi() {
        if (doviRenderer != null) return
        val sv = SurfaceView(ctx)
        host.addView(sv, 0, FrameLayout.LayoutParams(-1, -1))
        view.videoSurfaceView?.visibility = View.GONE
        doviView = sv
        val r = DoviRenderer(sv, dovi, doviHdr) { surface -> player.setVideoSurface(surface) }
        r.scaleMode = currentScale
        player.videoSize.let { r.setVideoSize(it.width, it.height, it.pixelWidthHeightRatio) }
        player.setVideoFrameMetadataListener(r)
        doviRenderer = r
        listener.onTracksChanged()
    }

    /** Обратно к обычному выводу видео (следующий файл — не DV5). */
    private fun disableDovi() {
        val r = doviRenderer ?: return
        player.clearVideoFrameMetadataListener(r)
        player.clearVideoSurface()
        r.release()
        doviView?.let { host.removeView(it) }
        doviView = null
        doviRenderer = null
        view.videoSurfaceView?.visibility = View.VISIBLE
        view.player = null
        view.player = player
    }

    /** Счётчики кадров для журнала: показано / выброшено / опоздания. */
    fun videoStats(): String {
        val c = player.videoDecoderCounters ?: return "нет данных"
        c.ensureUpdated()
        val avgOffset = if (c.videoFrameProcessingOffsetCount > 0)
            c.totalVideoFrameProcessingOffsetUs / c.videoFrameProcessingOffsetCount / 1000 else 0
        return "показано ${c.renderedOutputBufferCount}, выброшено ${c.droppedBufferCount} " +
            "(подряд до ${c.maxConsecutiveDroppedBufferCount}), пропущено ${c.skippedOutputBufferCount}, " +
            "до ключевого ${c.droppedToKeyframeCount}, запас ${avgOffset} мс, туннель ${isTunneled()}"
    }

    fun isTunneled() = runCatching {
        player.currentTracks.groups.isNotEmpty() && trackSelector.parameters.tunnelingEnabled && !dovi.active
    }.getOrDefault(false)

    val isDovi get() = dovi.active
    fun doviStats() = doviRenderer?.stats()
    private var currentScale = 0

    companion object {
        const val DV5_MESSAGE = "Это Dolby Vision «профиль 5». В прошивке проектора декодер Dolby Vision выключен, " +
            "поэтому картинку такого файла не покажет ни один плеер.\n\n" +
            "Выберите в Лампе другую раздачу этого фильма: HDR10, HDR, SDR или «HDR.DV» — они идут нормально."
    }

    // ── Подписи ──────────────────────────────────────────────────────────────
    private fun audioLabel(f: Format) = listOfNotNull(
        Formats.language(f.language),
        f.label?.takeIf { it.isNotBlank() && !it.equals(Formats.language(f.language), true) },
        when (f.channelCount) { 1 -> "моно"; 2 -> "2.0"; 6 -> "5.1"; 8 -> "7.1"; Format.NO_VALUE, 0 -> null; else -> "${f.channelCount} кан." },
        codec(f.sampleMimeType),
    ).distinct().joinToString(" · ").ifBlank { "Дорожка" }

    private fun textLabel(f: Format) = listOfNotNull(
        Formats.language(f.language),
        f.label?.takeIf { it.isNotBlank() && !it.equals(Formats.language(f.language), true) },
        if (f.selectionFlags and C.SELECTION_FLAG_FORCED != 0) "надписи" else null,
        codec(f.sampleMimeType),
    ).distinct().joinToString(" · ").ifBlank { "Субтитры" }

    private fun codec(mime: String?): String? = when (mime) {
        MimeTypes.AUDIO_AC3 -> "AC3"
        MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "E-AC3"
        MimeTypes.AUDIO_TRUEHD -> "TrueHD"
        MimeTypes.AUDIO_DTS -> "DTS"
        MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
        MimeTypes.AUDIO_AAC -> "AAC"
        MimeTypes.AUDIO_MPEG -> "MP3"
        MimeTypes.AUDIO_OPUS -> "Opus"
        MimeTypes.AUDIO_FLAC -> "FLAC"
        MimeTypes.VIDEO_H265, MimeTypes.VIDEO_DOLBY_VISION -> "HEVC"
        MimeTypes.VIDEO_H264 -> "AVC"
        MimeTypes.VIDEO_AV1 -> "AV1"
        MimeTypes.VIDEO_VP9 -> "VP9"
        MimeTypes.APPLICATION_SUBRIP -> "SRT"
        MimeTypes.TEXT_SSA -> "ASS"
        MimeTypes.TEXT_VTT -> "VTT"
        MimeTypes.APPLICATION_PGS -> "PGS"
        MimeTypes.APPLICATION_VOBSUB -> "VobSub"
        else -> null
    }

    /** «Фильм.ru.srt» → «ru»: язык внешних субтитров из имени файла, если он там есть. */
    private fun subtitleLanguage(u: Uri): String? {
        val parts = u.lastPathSegment?.split('.') ?: return null
        return parts.getOrNull(parts.size - 2)?.lowercase(Locale.ROOT)?.takeIf { parts.size >= 3 && it.length in 2..3 && it.all(Char::isLetter) }
    }

    private fun subtitleMime(u: Uri) = when (u.lastPathSegment?.substringAfterLast('.')?.lowercase(Locale.ROOT)) {
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        "vtt" -> MimeTypes.TEXT_VTT
        else -> MimeTypes.APPLICATION_SUBRIP
    }

    /** Ошибки формата/декодера — повод перейти на VLC; сетевые — нет. */
    private fun isFormatProblem(e: PlaybackException) = e.errorCode in listOf(
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
    )

    private fun describe(e: PlaybackException): String = when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
            "Источник не отвечает. Если это торрент — проверьте, что TorrServe запущен и у раздачи есть сиды."
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "Сервер отказал в доступе к видео."
        in 2000..2999 -> "Видео не загружается: источник отвечает слишком медленно или оборвал связь."
        else -> "Формат не поддерживается (${e.errorCodeName.removePrefix("ERROR_CODE_").lowercase()})."
    }
}

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
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import com.xgimiru.player.settings.FrameRateHook
import com.xgimiru.player.settings.K
import com.xgimiru.player.settings.Passthrough
import com.xgimiru.player.settings.Settings
import com.xgimiru.player.dovi.DoviRenderer
import com.xgimiru.player.dovi.DoviStore
import com.xgimiru.player.dovi.HdrPolicy
import com.xgimiru.player.dovi.RpuTapExtractorsFactory
import java.util.Locale

/**
 * Движок Media3: аппаратные декодеры чипа напрямую, включая Dolby Vision
 * (c2.mtk.dvhe.*), и точная синхронизация кадров для 4K HDR.
 */
@OptIn(UnstableApi::class)
class ExoEngine(private val ctx: Context, private val host: FrameLayout, private val listener: Engine.Listener,
                private val cfg: Settings) : Engine {

    /** Dolby Vision профиля 5: метаданные из файла и свой GL-рендер (см. [DoviRenderer]). */
    private val dovi = DoviStore()
    private var doviRenderer: DoviRenderer? = null
    private var doviView: SurfaceView? = null
    private val main = Handler(Looper.getMainLooper())
    /** Вывод DV5: true — HDR10 (слой помечен BT.2020 PQ), false — свой тон-маппинг в SDR. */
    private lateinit var trackSelector: DefaultTrackSelector
    // Туннель выключен по умолчанию: в туннеле звук идёт в аудиотракт MediaTek мимо микшера Android,
    // и громкость системы к нему не применяется. Включается в настройках (Видео > Туннельный режим).
    private val tunneling = cfg.bool(K.TUNNELING)

    var doviHdr: Boolean
        get() = cfg.string(K.DV5) == "true"
        set(v) = cfg.set(K.DV5, v.toString())

    /** Задержка звука: сдвиг часов звука, по которым Media3 выводит кадры (в туннеле не действует). */
    @Volatile private var audioDelayUs = cfg.int(K.AUDIO_DELAY) * 1000L
    /** Задержка субтитров вперёд (позже). Раньше умеют только внешние файлы: их сдвигает сам экран плеера. */
    private var subDelayUs = cfg.int(K.SUB_DELAY).coerceAtLeast(0) * 1000L
    private var aspect = cfg.string(K.ASPECT)
    private var loudness: LoudnessEnhancer? = null
    private var dynamics: DynamicsProcessing? = null

    private val view = PlayerView(ctx).apply {
        useController = false
        setShutterBackgroundColor(Color.BLACK)
        setKeepContentOnPlayerReset(true)
        // свои субтитры поверх (ниже): так их можно задержать; у PlayerView свои скрыты
        subtitleView?.visibility = View.GONE
    }

    /** Субтитры: стиль из настроек, задержка через очередь [pending]. */
    private val subView = SubtitleView(ctx).also { styleSubtitles(it) }
    private val pending = ArrayDeque<CueGroup>()
    private val pump = object : Runnable {
        override fun run() {
            val now = player.currentPosition * 1000
            var shown: CueGroup? = null
            while (pending.isNotEmpty() && pending.first().presentationTimeUs + subDelayUs <= now) shown = pending.removeFirst()
            shown?.let { subView.setCues(it.cues) }
            if (pending.isNotEmpty()) main.postDelayed(this, 40)
        }
    }

    private val player: ExoPlayer

    /**
     * HTTP для всего: файл, манифест HLS/DASH, сегменты, ключи, внешние субтитры. Заголовки из интента
     * ([setRequest]) кладутся сюда до [open]; источники данных берут их при каждом запросе.
     */
    private val http = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(30_000)
        .setReadTimeoutMs(60_000)
        .setUserAgent(StreamRequest.DEFAULT_USER_AGENT)
    private var mimeHint: String? = null

    init {
        val renderers = Renderers()
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(20_000, 50_000, 2_000, 5_000)
            .setTargetBufferBytes(96 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        val selector = DefaultTrackSelector(ctx).apply { setParameters(trackParams(buildUponParameters())) }
        trackSelector = selector
        player = ExoPlayer.Builder(ctx, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(ctx, http), RpuTapExtractorsFactory(dovi) {
                HdrPolicy.caps(ctx) to HdrPolicy.prefs(ctx)
            }))
            .setLoadControl(load)
            .setTrackSelector(selector)
            .setHandleAudioBecomingNoisy(true)
            .build()
        // смена частоты кадров экрана под фильм в базовом плеере выключена (см. FrameRateHook)
        player.setVideoChangeFrameRateStrategy(
            if (FrameRateHook.active()) C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS else C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
        view.player = player
        host.addView(view, FrameLayout.LayoutParams(-1, -1))
        host.addView(subView, FrameLayout.LayoutParams(-1, -1))
        applyEffects()
        dovi.onActivated = {
            // DV5 рисует наш шейдер — кадры нужны приложению, туннель тут невозможен.
            trackSelector.setParameters(trackSelector.buildUponParameters().setTunnelingEnabled(false))
            main.post { enableDovi() }
        }

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = listener.onStateChanged()
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // Bluetooth-колонка отключилась: Media3 сам ставит паузу, а экран об этом должен знать,
                // иначе кадр просто застывает (Z9X, 2026-10-23)
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY) listener.onPausedBySystem()
            }
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> listener.onBuffering(true, -1)
                    Player.STATE_READY -> listener.onBuffering(false, 100)
                    Player.STATE_ENDED -> listener.onEnded()
                    else -> Unit
                }
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                doviRenderer?.setVideoSize(videoSize.width, videoSize.height, forcedPar(videoSize))
                applyAspect()
            }
            override fun onRenderedFirstFrame() = applyAspect()
            override fun onAudioSessionIdChanged(audioSessionId: Int) = applyEffects()
            override fun onCues(cueGroup: CueGroup) {
                if (subDelayUs <= 0) { pending.clear(); subView.setCues(cueGroup.cues); return }
                pending.addLast(cueGroup)
                main.removeCallbacks(pump)
                main.post(pump)
            }
            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) { pending.clear(); subView.setCues(emptyList()) }
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

    override fun setRequest(headers: Map<String, String>, mimeHint: String?) {
        http.setDefaultRequestProperties(StreamRequest.withoutUserAgent(headers))
        http.setUserAgent(StreamRequest.userAgent(headers))
        this.mimeHint = mimeHint
    }

    override fun open(uri: Uri, startMs: Long, subs: List<Uri>) {
        try {
            disableDovi()
            dovi.reset()
            // ручной выбор качества прошлого фильма к новому не относится
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_VIDEO).build()
            trackSelector.setParameters(trackParams(trackSelector.buildUponParameters()))
            pending.clear(); subView.setCues(emptyList())
            val subConfigs = subs.map {
                MediaItem.SubtitleConfiguration.Builder(it).setMimeType(subtitleMime(it)).setLanguage(subtitleLanguage(it)).build()
            }
            val item = MediaItem.Builder().setUri(uri).setSubtitleConfigurations(subConfigs)
            StreamRequest.streamMime(mimeHint, uri.path, uri.query)?.let { item.setMimeType(it) }
            player.setMediaItem(item.build(), startMs)
            player.prepare()
            player.play()
        } catch (e: Exception) {
            failOpen(e)
        } catch (e: LinkageError) {
            // нет класса модуля (так падал HLS до 1.2) — сообщение вместо вылета
            failOpen(e)
        }
    }

    /** Источник не собрался: сообщить после возврата из open, чтобы экран мог сменить движок. */
    private fun failOpen(e: Throwable) {
        android.util.Log.w("ExoEngine", "open failed", e)
        runCatching { player.stop() }
        main.post { listener.onError("Этот поток не открывается (${e.javaClass.simpleName}).", true) }
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

    override fun videoTracks(): List<Engine.Track> {
        val all = player.currentTracks.groups.withIndex().filter { it.value.type == C.TRACK_TYPE_VIDEO }.flatMap { (gi, g) ->
            (0 until g.length).filter { g.isTrackSupported(it, true) }.map { Triple(gi, it, g) }
        }
        if (all.size < 2) return emptyList()
        val manual = player.trackSelectionParameters.overrides.values.firstOrNull { it.type == C.TRACK_TYPE_VIDEO }
        return all.sortedWith(compareByDescending<Triple<Int, Int, Tracks.Group>> { it.third.getTrackFormat(it.second).height }
            .thenByDescending { it.third.getTrackFormat(it.second).bitrate })
            .map { (gi, ti, g) ->
                val chosen = manual != null && manual.mediaTrackGroup == g.mediaTrackGroup && ti in manual.trackIndices
                Engine.Track("$gi:$ti", qualityLabel(g.getTrackFormat(ti)), chosen)
            }
    }

    override fun selectVideo(id: String?) {
        if (id == null) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_VIDEO).build()
        } else select(C.TRACK_TYPE_VIDEO, id)
    }

    override fun selectText(id: String?) {
        if (id == null) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        } else select(C.TRACK_TYPE_TEXT, id)
    }

    private fun select(type: Int, id: String) {
        val (gi, ti) = id.split(':').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 2 } ?: return
        val group = player.currentTracks.groups.getOrNull(gi)?.takeIf { it.type == type && ti < it.length } ?: return
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
            dovi.plan?.label != null -> dovi.plan?.label
            f.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION || f.codecs?.startsWith("dvh") == true -> "Dolby Vision"
            f.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 -> "HDR10"
            f.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG -> "HLG"
            else -> null
        }
        return listOfNotNull(res, codec(f.sampleMimeType), hdr).distinct().joinToString(" · ").ifBlank { null }
    }

    override fun audioInfo() = selected(C.TRACK_TYPE_AUDIO)?.let { audioLabel(it) }
    override fun textInfo() = selected(C.TRACK_TYPE_TEXT)?.let { Formats.language(it.language) ?: it.label ?: "вкл" }

    override fun setAudioDelay(ms: Int) { audioDelayUs = ms * 1000L }

    override fun setSubtitleDelay(ms: Int) {
        subDelayUs = ms.coerceAtLeast(0) * 1000L
        pending.clear()
    }

    override fun setAspect(value: String) {
        aspect = value
        applyAspect()
        player.videoSize.let { doviRenderer?.setVideoSize(it.width, it.height, forcedPar(it)) }
    }

    /** Пропорции из настроек вместо пропорций файла: рамка PlayerView получает своё соотношение. */
    private fun applyAspect() {
        val ratio = Settings.aspectOf(aspect) ?: return
        main.post { view.findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)?.setAspectRatio(ratio) }
    }

    private fun forcedPar(v: VideoSize): Float {
        val ratio = Settings.aspectOf(aspect) ?: return v.pixelWidthHeightRatio
        return if (v.width > 0 && v.height > 0) ratio / (v.width.toFloat() / v.height) else v.pixelWidthHeightRatio
    }

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
        main.removeCallbacks(pump)
        runCatching { loudness?.release() }
        runCatching { dynamics?.release() }
        doviRenderer?.let { player.clearVideoFrameMetadataListener(it) }
        player.release()
        doviRenderer?.release()
        doviView?.let { host.removeView(it) }
        host.removeView(view)
        host.removeView(subView)
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

    // ── Настройки ────────────────────────────────────────────────────────────
    /** Декодеры, звук на ресивер, сведение в стерео и задержка звука. */
    private inner class Renderers : DefaultRenderersFactory(ctx) {
        init {
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON) // FFmpeg, если чип не умеет звук
            val mode = cfg.string(K.DECODER)
            setEnableDecoderFallback(mode != "hw")
            setMediaCodecSelector(codecSelector(mode))
        }

        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink {
            val b = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            if (cfg.bool(K.DOWNMIX)) b.setAudioProcessors(arrayOf(stereoMixer()))
            return Sink(b.build())
        }
    }

    /** Форматы, выключенные в «Передавать звук на ресивер», декодируются в PCM. Часы звука сдвинуты на задержку. */
    private inner class Sink(sink: AudioSink) : ForwardingAudioSink(sink) {
        override fun supportsFormat(format: Format) = getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED
        override fun getFormatSupport(format: Format): Int =
            if (!Passthrough.allowed(format.sampleMimeType, cfg)) AudioSink.SINK_FORMAT_UNSUPPORTED else super.getFormatSupport(format)
        override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
            val p = super.getCurrentPositionUs(sourceEnded)
            return if (p == AudioSink.CURRENT_POSITION_NOT_SET) p else (p + audioDelayUs).coerceAtLeast(0)
        }
    }

    private fun codecSelector(mode: String) = MediaCodecSelector { mime, secure, tunnel ->
        val all = MediaCodecUtil.getDecoderInfos(mime, secure, tunnel)
        if (!MimeTypes.isVideo(mime)) all
        else when (mode) {
            "hw" -> all.filter { !it.softwareOnly }
            "sw" -> all.sortedBy { if (it.softwareOnly) 0 else 1 }
            else -> all
        }
    }

    /** Любое число каналов в два: готовые матрицы Media3, для 7 и 8 каналов своя. */
    private fun stereoMixer() = ChannelMixingAudioProcessor().apply {
        for (n in 1..8) {
            val m = runCatching { ChannelMixingMatrix.create(n, 2) }.getOrNull() ?: fallbackMatrix(n)
            putChannelMixingMatrix(m)
        }
    }

    private fun fallbackMatrix(n: Int): ChannelMixingMatrix {
        // порядок Android: FL FR FC LFE затем тыл и бока парами; LFE не сводим
        val c = FloatArray(n * 2)
        for (i in 0 until n) {
            val (l, r) = when (i) { 0 -> 1f to 0f; 1 -> 0f to 1f; 2 -> 0.707f to 0.707f; 3 -> 0f to 0f
                else -> if (i % 2 == 0) 0.707f to 0f else 0f to 0.707f }
            c[i * 2] = l; c[i * 2 + 1] = r
        }
        val norm = maxOf((0 until n).sumOf { c[it * 2].toDouble() }, 1.0).toFloat()
        for (i in c.indices) c[i] /= norm
        return ChannelMixingMatrix(n, 2, c)
    }

    private fun trackParams(b: DefaultTrackSelector.Parameters.Builder): DefaultTrackSelector.Parameters.Builder {
        val sys = Locale.getDefault().language
        when (val a = cfg.string(K.AUDIO_LANG)) {
            "original" -> b.setPreferredAudioLanguages()
            "system" -> b.setPreferredAudioLanguages(sys)
            else -> b.setPreferredAudioLanguages(a)
        }
        b.setSelectUndeterminedTextLanguage(false).setIgnoredTextSelectionFlags(0).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        when (val t = cfg.string(K.SUB_LANG)) {
            "file" -> b.setPreferredTextLanguages()
            "forced" -> b.setPreferredTextLanguages().setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            "off" -> b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            else -> b.setPreferredTextLanguages(*listOfNotNull(if (t == "system") sys else t,
                cfg.string(K.SUB_LANG2).takeIf { it != "none" }).toTypedArray())
        }
        return b.setTunnelingEnabled(tunneling)
    }

    private fun styleSubtitles(v: SubtitleView) {
        val fg = when (cfg.string(K.SUB_COLOR)) {
            "yellow" -> 0xFFFFE14D.toInt(); "grey" -> 0xFFD2D2D2.toInt(); "cyan" -> 0xFF7FE3FF.toInt(); "green" -> 0xFF8DEB8D.toInt()
            else -> Color.WHITE
        }
        val bg = when (cfg.string(K.SUB_BACK)) { "half" -> 0x99000000.toInt(); "solid" -> Color.BLACK; else -> Color.TRANSPARENT }
        val edge = when (cfg.string(K.SUB_EDGE)) {
            "shadow" -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW; "raised" -> CaptionStyleCompat.EDGE_TYPE_RAISED
            "none" -> CaptionStyleCompat.EDGE_TYPE_NONE; else -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
        }
        val face = Typeface.create("sans-serif-medium", if (cfg.bool(K.SUB_BOLD)) Typeface.BOLD else Typeface.NORMAL)
        v.setStyle(CaptionStyleCompat(fg, bg, Color.TRANSPARENT, edge, Color.argb(230, 0, 0, 0), face))
        v.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * cfg.subSize / 100f)
        v.setBottomPaddingFraction(cfg.subPosition / 100f)
        val own = cfg.bool(K.SUB_STYLES)
        v.setApplyEmbeddedStyles(own)
        v.setApplyEmbeddedFontSizes(own)
    }

    /** Усиление громкости и ночной режим: эффекты Android на сессии звука плеера (не для звука на ресивер). */
    private fun applyEffects() {
        runCatching { loudness?.release() }; loudness = null
        runCatching { dynamics?.release() }; dynamics = null
        val sid = player.audioSessionId
        if (sid == C.AUDIO_SESSION_ID_UNSET) return
        if (cfg.boostDb > 0) loudness = runCatching {
            LoudnessEnhancer(sid).apply { setTargetGain(cfg.boostDb * 100); setEnabled(true) }
        }.onFailure { android.util.Log.w("ExoEngine", "boost", it) }.getOrNull()
        if (cfg.bool(K.NIGHT) && Build.VERSION.SDK_INT >= 28) dynamics = runCatching { night(sid) }
            .onFailure { android.util.Log.w("ExoEngine", "night", it) }.getOrNull()
    }

    @androidx.annotation.RequiresApi(28)
    private fun night(sid: Int): DynamicsProcessing {
        val c = DynamicsProcessing.Config.Builder(DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
            false, 0, true, 1, false, 0, true).build()
        // громкие места сжимаются 4:1 выше -30 дБ, тихие поднимаются на 6 дБ, сверху ограничитель
        c.setMbcBandAllChannelsTo(0, DynamicsProcessing.MbcBand(true, 20000f, 10f, 250f, 4f, -30f, 6f, -90f, 1f, 0f, 6f))
        c.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -3f, 0f))
        return DynamicsProcessing(0, sid, c).apply { setEnabled(true) }
    }

    companion object {
        const val DV5_MESSAGE = "Это Dolby Vision «профиль 5». В прошивке проектора декодер Dolby Vision выключен, " +
            "поэтому картинку такого файла не покажет ни один плеер.\n\n" +
            "Нужен другой файл этого фильма: HDR10, HDR, SDR или «HDR.DV», они идут нормально."
    }

    // ── Подписи ──────────────────────────────────────────────────────────────
    private fun audioLabel(f: Format) = listOfNotNull(
        Formats.language(f.language),
        f.label?.takeIf { it.isNotBlank() && !it.equals(Formats.language(f.language), true) },
        when (f.channelCount) { 1 -> "моно"; 2 -> "2.0"; 6 -> "5.1"; 8 -> "7.1"; Format.NO_VALUE, 0 -> null; else -> "${f.channelCount} кан." },
        codec(f.sampleMimeType),
    ).distinct().joinToString(" · ").ifBlank { "Дорожка" }

    private fun qualityLabel(f: Format) = listOfNotNull(
        if (f.height > 0) "${f.height}p" else if (f.width > 0) "${f.width} px" else null,
        f.bitrate.takeIf { it > 0 }?.let { String.format(Locale("ru"), "%.1f Мбит/с", it / 1_000_000.0) },
        codec(f.sampleMimeType) ?: f.codecs?.substringBefore('.')?.uppercase(Locale.ROOT),
    ).joinToString(" · ").ifBlank { "Вариант" }

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
            "Источник не отвечает. Проверьте сеть и что папка или сервер доступны."
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "Сервер отказал в доступе к видео."
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> "Список потока (HLS/DASH) не читается."
        PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED,
        PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR,
        PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED,
        PlaybackException.ERROR_CODE_DRM_DISALLOWED_OPERATION,
        PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR,
        PlaybackException.ERROR_CODE_DRM_DEVICE_REVOKED,
        PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED -> "Поток защищён (DRM), такой Lumen Play не показывает."
        in 2000..2999 -> "Видео не загружается: источник отвечает слишком медленно или оборвал связь."
        else -> "Формат не поддерживается (${e.errorCodeName.removePrefix("ERROR_CODE_").lowercase()})."
    }
}

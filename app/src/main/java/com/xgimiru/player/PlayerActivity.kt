package com.xgimiru.player

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import com.xgimiru.player.net.NetShares
import com.xgimiru.player.settings.K
import com.xgimiru.player.settings.Settings
import com.xgimiru.player.settings.Stepper
import com.xgimiru.player.settings.playerSettings
import com.xgimiru.player.ui.tr
import android.app.AlertDialog
import android.graphics.drawable.GradientDrawable

/**
 * Плеер Lumen Play. Интерфейс свой, движков два (см. [Engine]): Media3 для современных форматов и
 * Dolby Vision, VLC для старья, сетевых папок и как запасной. Понимает параметры, которые другие
 * приложения передают внешним плеерам (MX Player, VLC), и возвращает, где остановились.
 * Поведение задают настройки плеера ([K]): перемотка, кнопки, продолжение, звук, субтитры.
 */
class PlayerActivity : Activity(), Engine.Listener {

    private data class Item(val uri: Uri, val title: String, val subs: List<Uri>)

    private var engine: Engine? = null
    private var engineIsVlc = false
    private lateinit var video: FrameLayout
    private lateinit var controls: View
    private lateinit var dim: View
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var clock: TextView
    private lateinit var net: TextView
    private lateinit var positionText: TextView
    private lateinit var remainingText: TextView
    private lateinit var progress: ProgressView
    private lateinit var chips: LinearLayout
    private lateinit var badge: StateBadge
    private lateinit var buffering: View
    private lateinit var bufferText: TextView
    private lateinit var bufferDetail: TextView
    private lateinit var toast: View
    private lateinit var toastTitle: TextView
    private lateinit var toastHint: TextView
    private lateinit var next: View
    private lateinit var nextTitle: TextView
    private lateinit var nextProgress: ProgressView
    private lateinit var error: View
    private lateinit var errorText: TextView
    private lateinit var panel: View
    private lateinit var panelList: LinearLayout

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val clockFormat = SimpleDateFormat("HH:mm", Locale("ru"))

    private var items: List<Item> = emptyList()
    /** Id списка серий в [Playlists], если видео открыто из списка (или список восстановлен по истории). */
    private var playlist: String? = null
    private var index = 0
    /** Заголовки запроса из интента (расширение браузера: Referer, Origin, User-Agent), на весь список. */
    private var headers: Map<String, String> = emptyMap()
    /** Тип из интента (application/x-mpegURL, application/dash+xml, ...) — только для ссылки из data. */
    private var streamUri: Uri? = null
    private var streamType: String? = null
    private var controlsShown = false
    private var panelOpen = false
    private var scrubTarget: Long? = null
    private var nextDismissed = false
    private var resumedFrom: Long? = null
    private var lastSave = 0L
    private lateinit var cfg: Settings
    /** Задержки этого сеанса: из настроек, меняются в панели (только до выхода). */
    private var audioDelay = 0
    private var subDelay = 0
    private var completed = false
    private var paused = false          // пауза по нашей команде (а не буферизация)
    private var loading = false
    private var loadingPct = -1
    private var fellBack = false        // уже переключались на запасной движок для этого файла
    private var startFrom = 0L          // с какой позиции открывали текущий файл (для «попробовать ещё раз»)
    private var chapters: List<Chapters.Chapter> = emptyList()  // главы текущего файла: где титры
    private var chaptersOf: Uri? = null // чьи главы прочитаны или читаются
    private var nextFrom = 0L           // позиция, на которой появилась карточка следующей серии

    // ── Жизненный цикл ───────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        cfg = playerSettings()
        audioDelay = cfg.int(K.AUDIO_DELAY)
        subDelay = cfg.int(K.SUB_DELAY)
        setContentView(R.layout.activity_player)
        bindViews()
        startSession()
        open(intent)
        handler.post(tick)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        save()
        setIntent(intent)
        open(intent)
    }

    override fun onStart() {
        super.onStart()
        // вернулись после сна или из другого приложения: открываем видео заново с того же места, на паузе
        stoppedAt?.let { pos ->
            stoppedAt = null
            if (items.isNotEmpty() && engine == null) {
                play(index, pos, forceVlc = engineIsVlc)
                engine?.pause()
                paused = true
                showControls(0)
            }
        }
    }

    /**
     * Позиция, с которой плеер ушёл в onStop (null — не уходил). При выключенном экране аппаратный декодер
     * не может отдать кадры и после включения падал (Z9X 2026-10-24: 786 неудачных dequeue за 12 с, ошибка
     * c2.mtk.avc.decoder, переход на VLC, чёрный экран), поэтому движок закрываем и открываем заново.
     */
    private var stoppedAt: Long? = null

    override fun onStop() {
        super.onStop()
        save()
        engine?.let {
            if (it.isPlaying) { it.pause(); paused = true }
            if (!completed && error.visibility != View.VISIBLE && !isFinishing) {
                stoppedAt = it.position.takeIf { p -> p > 0 } ?: startFrom
                it.release()
                engine = null
            }
        }
    }

    override fun onDestroy() {
        session?.release()
        session = null
        handler.removeCallbacksAndMessages(null)
        io.shutdownNow()
        engine?.release()
        engine = null
        super.onDestroy()
    }

    private fun bindViews() {
        video = findViewById(R.id.video)
        controls = findViewById(R.id.controls)
        dim = findViewById(R.id.dim)
        title = findViewById(R.id.title)
        subtitle = findViewById(R.id.subtitle)
        clock = findViewById(R.id.clock)
        net = findViewById(R.id.net)
        positionText = findViewById(R.id.position)
        remainingText = findViewById(R.id.remaining)
        progress = findViewById(R.id.progress)
        chips = findViewById(R.id.chips)
        badge = findViewById(R.id.badge)
        buffering = findViewById(R.id.buffering)
        bufferText = findViewById(R.id.bufferText)
        bufferDetail = findViewById(R.id.bufferDetail)
        toast = findViewById(R.id.toast)
        toastTitle = findViewById(R.id.toastTitle)
        toastHint = findViewById(R.id.toastHint)
        next = findViewById(R.id.next)
        nextTitle = findViewById(R.id.nextTitle)
        nextProgress = findViewById(R.id.nextProgress)
        error = findViewById(R.id.error)
        errorText = findViewById(R.id.errorText)
        panel = findViewById(R.id.panel)
        panelList = findViewById(R.id.panelList)
        net.visibility = View.GONE
        findViewById<TextView>(R.id.hint).text = tr("Вниз: звук, субтитры, экран", "Down: audio, subtitles, picture")
    }

    private fun prefs() = getSharedPreferences("player", MODE_PRIVATE)

    // ── Открытие ─────────────────────────────────────────────────────────────
    private fun open(intent: Intent) {
        val uri = intent.data ?: run { finish(); return }
        val baseTitle = intent.getStringExtra("title") ?: intent.getStringExtra("forcename")
            ?: intent.getStringExtra("name") ?: Formats.titleFromPath(uri.lastPathSegment ?: uri.path)

        @Suppress("DEPRECATION")
        val subs = intent.getParcelableArrayExtra("subs")?.mapNotNull { it as? Uri }.orEmpty()
        @Suppress("DEPRECATION")
        val list = intent.getParcelableArrayExtra("video_list")?.mapNotNull { it as? Uri }.orEmpty()
        val names = intent.getStringArrayExtra("video_list.name")
        // субтитры каждой серии (так передаёт папка в сети): по строке на серию, ссылки через \n
        val listSubs = intent.getStringArrayExtra("video_list.subs")
        headers = readHeaders(intent)
        streamUri = uri
        streamType = intent.type
        index = 0
        playlist = null
        items = if (list.size > 1) {
            index = list.indexOf(uri).coerceAtLeast(0)
            list.mapIndexed { i, u ->
                val itemSubs = listSubs?.getOrNull(i)?.lines()?.filter { it.isNotBlank() }?.map(Uri::parse)
                Item(u, names?.getOrNull(i) ?: Formats.titleFromPath(u.lastPathSegment), itemSubs ?: if (u == uri) subs else emptyList())
            }.also { rememberPlaylist(it) }
        } else restorePlaylist(uri, subs) ?: listOf(Item(uri, baseTitle, subs))
        @Suppress("DEPRECATION")
        val explicit = (intent.extras?.get("position") as? Number)?.toLong()
            ?: (intent.extras?.get("extra_position") as? Number)?.toLong()
        val saved = savedPosition(items[index]) ?: 0L
        val fromStart = intent.getBooleanExtra("from_start", false)
        val mode = cfg.string(K.RESUME)
        if (!fromStart && (explicit == null || explicit <= 0) && saved > 0 && mode == "ask") { askResume(saved); return }
        val startPos = when {
            fromStart -> 0L
            explicit != null && explicit > 0 -> explicit
            mode == "start" -> 0L
            else -> saved
        }
        resumedFrom = startPos.takeIf { it > 0 && explicit == null }
        play(index, startPos)
        resumedFrom?.let { showToast(tr("Продолжаем с ${Time.format(it)}", "Resuming from ${Time.format(it)}"),
            tr("Вверх: смотреть с начала", "Up: watch from the start"), 5000) }
    }

    /** «Спрашивать»: продолжить или с начала, до запуска видео. */
    private fun askResume(pos: Long) {
        AlertDialog.Builder(this, R.style.Lumen_Dialog)
            .setTitle(current()?.title)
            .setPositiveButton(tr("Продолжить с ${Time.format(pos)}", "Resume from ${Time.format(pos)}")) { _, _ -> play(index, pos) }
            .setNegativeButton(tr("С начала", "From the start")) { _, _ -> play(index, 0) }
            .setOnCancelListener { finishWithResult() }
            .show()
    }

    private fun readHeaders(intent: Intent): Map<String, String> = runCatching {
        @Suppress("DEPRECATION")
        val bundle = intent.getBundleExtra(StreamRequest.EXTRA_HEADERS_BUNDLE)
        val map = bundle?.keySet()?.associateWith { bundle.get(it)?.toString() }
        StreamRequest.headers(intent.getStringArrayExtra(StreamRequest.EXTRA_HEADERS), map)
    }.getOrDefault(emptyMap())

    private fun rememberPlaylist(list: List<Item>) {
        val id = Playlists.id(list.map { History.key(it.uri) })
        Playlists.put(this, id, list.map { Playlists.Item(it.uri.toString(), it.title, it.subs.map(Uri::toString)) })
        playlist = id
    }

    /**
     * Открыли одну серию («Продолжить просмотр» в Lumen Play или в лаунчере): если в истории она из списка,
     * берём весь список, чтобы дальше шли следующие серии. Ссылка самой серии — та, с которой открыли.
     */
    private fun restorePlaylist(uri: Uri, subs: List<Uri>): List<Item>? {
        val key = History.key(uri)
        val id = History.get(this, key)?.playlist ?: return null
        val saved = Playlists.get(this, id)?.takeIf { it.size > 1 } ?: return null
        val at = saved.indexOfFirst { History.key(Uri.parse(it.uri)) == key }.takeIf { it >= 0 } ?: return null
        index = at
        playlist = id
        return saved.mapIndexed { i, it ->
            if (i == at) Item(uri, it.name, subs.ifEmpty { it.subs.map(Uri::parse) })
            else Item(Uri.parse(it.uri), it.name, it.subs.map(Uri::parse))
        }
    }

    /** Запуск серии [i]. [forceVlc] — запасной движок после сбоя Media3. */
    private fun play(i: Int, startMs: Long, forceVlc: Boolean = false) {
        index = i
        startFrom = startMs
        val item = items[i]
        completed = false
        nextDismissed = false
        if (item.uri != chaptersOf) { chapters = emptyList(); chaptersOf = null }
        handler.removeCallbacks(loadChapters)
        if (i + 1 < items.size && cfg.bool(K.AUTO_NEXT)) handler.postDelayed(loadChapters, 5000)   // когда фильм уже пошёл
        paused = false
        if (!forceVlc) fellBack = false
        error.visibility = View.GONE
        next.visibility = View.GONE
        badge.hide()

        val wantVlc = forceVlc || cfg.string(K.ENGINE) == "vlc" || Engine.prefersVlc(item.uri)
        if (engine == null || engineIsVlc != wantVlc) {
            engine?.release()
            engine = if (wantVlc) VlcEngine(this, video, this, cfg) else ExoEngine(this, video, this, cfg)
            engineIsVlc = wantVlc
            engine!!.setScale(cfg.scale)
            engine!!.setAspect(cfg.string(K.ASPECT))
        }
        val e = engine!!
        val subs = SubPrep.prepare(this, item.uri, item.subs, cfg, wantVlc, subDelay)
        e.setRequest(headers, streamType.takeIf { item.uri == streamUri })
        e.setSubtitleEncoding(subs.charset)
        e.setAudioDelay(audioDelay)
        e.setSubtitleDelay(subDelay)
        e.open(NetShares.authorize(this, item.uri), startMs, subs.subs)
        if (cfg.string(K.SPEED) == "last") prefs().getFloat("lastSpeed", 1f).takeIf { it != 1f }?.let { e.speed = it }

        showTitles()
        showControls(cfg.osdTimeoutMs)
        onBuffering(true, -1)
    }

    private fun current(): Item? = items.getOrNull(index)

    /** Главы MKV текущей серии, чтобы показать следующую с начала титров. В фоне: это сеть. */
    private val loadChapters = Runnable {
        val uri = current()?.uri ?: return@Runnable
        if (chaptersOf == uri) return@Runnable
        chaptersOf = uri
        Thread {
            val list = Chapters.read(applicationContext, uri)
            handler.post { if (current()?.uri == uri) chapters = list }
        }.start()
    }

    private fun savedPosition(item: Item): Long? {
        val e = History.get(this, History.key(item.uri)) ?: return null
        return e.position.takeIf { it > 30_000 && !e.finished }
    }

    // ── События движка ───────────────────────────────────────────────────────
    override fun onStateChanged() {
        reportSession()
        val playing = engine?.isPlaying == true
        if (playing) paused = false
        dim.animate().alpha(if (playing || loading) 0f else if (paused) 1f else 0f).setDuration(250).start()
        if (playing) scheduleHide() else if (paused) showControls(0)
    }

    override fun onPausedBySystem() {
        if (paused) return
        paused = true
        showControls(0)
        onStateChanged()
        showToast(tr("Пауза: звук ушёл с Bluetooth", "Paused: the sound left Bluetooth"), tr("OK: продолжить", "OK: continue"), 5000)
    }

    override fun onBuffering(active: Boolean, percent: Int) {
        loading = active
        loadingPct = percent
        if (active) {
            badge.hide()
            handler.removeCallbacks(showBuffering)
            handler.postDelayed(showBuffering, 400)
        } else {
            handler.removeCallbacks(showBuffering)
            buffering.visibility = View.GONE
        }
    }

    private val showBuffering = Runnable {
        if (loading) {
            buffering.visibility = View.VISIBLE
            updateBufferText()
        }
    }

    private fun updateBufferText() {
        bufferText.text = if (loadingPct in 1..99) "Загрузка  $loadingPct%" else "Загрузка"
    }

    override fun onTracksChanged() = updateChips()

    override fun onEnded() {
        onBuffering(false, 100)
        if (index + 1 < items.size && cfg.bool(K.AUTO_NEXT)) playNext()
        else { completed = true; finishWithResult() }
    }

    override fun onError(message: String, fallback: Boolean) {
        onBuffering(false, 100)
        if (fallback && !engineIsVlc && !fellBack) {
            // Media3 не справился с форматом — пробуем тот же файл на VLC с того же места.
            fellBack = true
            val pos = engine?.position ?: 0L
            play(index, pos, forceVlc = true)
            showToast(tr("Включил запасной движок", "Switched to the backup engine"), tr("этот файл лучше играет через VLC", "this file plays better with VLC"), 3500)
            return
        }
        errorText.text = message
        error.visibility = View.VISIBLE
    }

    // ── Отрисовка ────────────────────────────────────────────────────────────
    private val tick = object : Runnable {
        override fun run() {
            reportSession()
            updateTimes()
            handler.postDelayed(this, 500)
        }
    }

    private fun updateTimes() {
        val e = engine ?: return
        val now = Date()
        clock.text = clockFormat.format(now)
        val dur = e.duration
        val pos = e.position
        val shown = scrubTarget ?: pos
        progress.update(dur, pos, e.buffered, scrubTarget)
        positionText.text = Time.format(shown)
        clock.visibility = if (cfg.bool(K.CLOCK)) View.VISIBLE else View.GONE
        remainingText.text = if (dur > 0 && cfg.bool(K.CLOCK)) {
            val left = ((dur - shown) / e.speed.coerceAtLeast(0.1f)).toLong()
            "−${Time.format(dur - shown)}   ·   до ${clockFormat.format(Date(now.time + left))}"
        } else ""
        if (buffering.visibility == View.VISIBLE) updateBufferText()

        // Следующая серия: с начала титров, если в файле есть такая глава, иначе за 30 секунд до конца
        val hasNext = index + 1 < items.size && cfg.bool(K.AUTO_NEXT)
        val credits = if (dur > 0) Chapters.creditsStart(chapters, dur) else null
        val from = credits ?: (dur - 30_000)
        if (hasNext && dur > 5 * 60_000 && pos in from until dur && !nextDismissed && !panelOpen) {
            if (next.visibility != View.VISIBLE) {
                nextTitle.text = items[index + 1].title
                next.visibility = View.VISIBLE
                next.alpha = 0f; next.translationY = 40f
                next.animate().alpha(1f).translationY(0f).setDuration(300).start()
                nextFrom = pos
            }
            if (credits != null) {
                // как в Кинопоиске: отсчёт, и следующая серия сама, если не нажали «Назад»
                val done = (pos - nextFrom).coerceAtLeast(0)
                nextProgress.update(CREDITS_COUNTDOWN_MS, done, 0, null)
                if (done >= CREDITS_COUNTDOWN_MS && e.isPlaying) {
                    playNext()
                    return
                }
            } else {
                nextProgress.update(30_000, 30_000 - (dur - pos), 0, null)
            }
        } else if (next.visibility == View.VISIBLE && (pos < from || nextDismissed)) {
            next.visibility = View.GONE
        }

        if (System.currentTimeMillis() - lastSave > 15_000 && e.isPlaying) save()

        // Журнал кадров — раз в 5 секунд (logcat на прошивке закрыт)
        if (e is ExoEngine && System.currentTimeMillis() - lastDoviLog > 5000) {
            lastDoviLog = System.currentTimeMillis()
            runCatching {
                val line = "${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())} ${Time.format(pos)} " +
                    e.videoStats() + (if (e.isDovi) " | DV5: ${e.doviStats()}" else "")
                java.io.File(getExternalFilesDir(null), "video.txt").appendText(line + "\n")
            }
        }
    }
    private var lastDoviLog = 0L

    private fun showTitles() {
        val item = current() ?: return
        val base = intent.getStringExtra("title")
        if (items.size > 1 && base != null && base != item.title) {
            title.text = base
            subtitle.text = "${item.title}   ·   ${index + 1} из ${items.size}"
        } else {
            title.text = item.title
            subtitle.text = if (items.size > 1) "${index + 1} из ${items.size}" else ""
        }
        subtitle.visibility = if (subtitle.text.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    private fun updateChips() {
        val e = engine ?: return
        while (chips.childCount > 1) chips.removeViewAt(0)
        val labels = if (!cfg.bool(K.INFO)) emptyList() else listOfNotNull(
            e.videoInfo(),
            e.audioInfo(),
            e.textInfo()?.let { "Субтитры: $it" },
        )
        labels.forEachIndexed { i, text ->
            chips.addView(TextView(this).apply {
                this.text = text
                textSize = 14f
                setTextColor(Color.argb(220, 255, 255, 255))
                background = getDrawable(R.drawable.chip_bg)
                setPadding(dp(10), dp(4), dp(10), dp(4))
            }, i, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ── Панели управления ────────────────────────────────────────────────────
    private val hideControls = Runnable { setControls(false) }

    private fun showControls(autoHideMs: Long) {
        setControls(true)
        handler.removeCallbacks(hideControls)
        if (autoHideMs > 0) handler.postDelayed(hideControls, autoHideMs)
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideControls)
        if (engine?.isPlaying == true && !panelOpen && scrubTarget == null) handler.postDelayed(hideControls, cfg.osdTimeoutMs)
    }

    private fun setControls(show: Boolean) {
        if (!show && (paused || panelOpen)) return   // на паузе панели не прячем
        controlsShown = show
        controls.animate().alpha(if (show) 1f else 0f).setDuration(if (show) 180 else 400)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    private fun showToast(text: String, hint: String, ms: Long) {
        toastTitle.text = text
        toastHint.text = hint
        toastHint.visibility = if (hint.isBlank()) View.GONE else View.VISIBLE
        toast.animate().cancel()
        toast.alpha = 0f; toast.translationY = -20f
        toast.animate().alpha(1f).translationY(0f).setDuration(250).start()
        handler.removeCallbacks(hideToast)
        handler.postDelayed(hideToast, ms)
    }

    private val hideToast = Runnable {
        toast.animate().alpha(0f).setDuration(300).start()
        resumedFrom = null
    }

    // ── Медиасессия: кнопки Bluetooth-колонки и наушников (AVRCP) ────────────
    // Пульт присылает клавиши прямо в окно (onKeyDown), а кнопки колонки система отдаёт только активной
    // медиасессии: без неё пауза на колонке ничего не делала.
    private var session: android.media.session.MediaSession? = null
    private var sessionTitle: String? = null

    private fun startSession() {
        session = runCatching {
            android.media.session.MediaSession(this, "LumenPlay").apply {
                setCallback(object : android.media.session.MediaSession.Callback() {
                    override fun onPlay() = remotePlay()
                    override fun onPause() = remotePause()
                    override fun onSkipToNext() { if (index + 1 < items.size) playNext() }
                    override fun onSkipToPrevious() {
                        if (index > 0) { save(); play(index - 1, savedPosition(items[index - 1]) ?: 0) }
                    }
                    override fun onSeekTo(pos: Long) = remoteSeek(pos)
                    override fun onFastForward() { engine?.let { it.seekTo(it.position + 10_000) } }
                    override fun onRewind() { engine?.let { it.seekTo((it.position - 10_000).coerceAtLeast(0)) } }
                    override fun onStop() = remotePause()
                })
                isActive = true
            }
        }.getOrNull()
    }

    private fun reportSession() {
        val s = session ?: return
        val e = engine
        val st = when {
            error.visibility == View.VISIBLE || completed -> android.media.session.PlaybackState.STATE_STOPPED
            e?.isPlaying == true -> android.media.session.PlaybackState.STATE_PLAYING
            paused -> android.media.session.PlaybackState.STATE_PAUSED
            else -> android.media.session.PlaybackState.STATE_BUFFERING
        }
        var actions = android.media.session.PlaybackState.ACTION_PLAY or android.media.session.PlaybackState.ACTION_PAUSE or
            android.media.session.PlaybackState.ACTION_PLAY_PAUSE or android.media.session.PlaybackState.ACTION_SEEK_TO or
            android.media.session.PlaybackState.ACTION_FAST_FORWARD or android.media.session.PlaybackState.ACTION_REWIND or
            android.media.session.PlaybackState.ACTION_STOP
        if (index + 1 < items.size) actions = actions or android.media.session.PlaybackState.ACTION_SKIP_TO_NEXT
        if (index > 0) actions = actions or android.media.session.PlaybackState.ACTION_SKIP_TO_PREVIOUS
        s.setPlaybackState(android.media.session.PlaybackState.Builder()
            .setActions(actions)
            .setState(st, e?.position ?: 0L, if (st == android.media.session.PlaybackState.STATE_PLAYING) (e?.speed ?: 1f) else 0f)
            .build())
        val title = current()?.title
        if (title != null && title != sessionTitle) {
            sessionTitle = title
            s.setMetadata(android.media.MediaMetadata.Builder()
                .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, title)
                .putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, e?.duration ?: 0L)
                .build())
        }
    }

    private fun remotePlay() { if (paused && error.visibility != View.VISIBLE) togglePlay() }
    private fun remotePause() { if (!paused && engine != null) togglePlay() }
    private fun remoteSeek(ms: Long) {
        val e = engine ?: return
        val dur = e.duration
        e.seekTo(if (dur > 0) ms.coerceIn(0, dur - 1000) else ms)
        showControls(3000)
        updateTimes()
    }

    // ── Пульт ────────────────────────────────────────────────────────────────
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (panelOpen) {
            val stepper = currentFocus?.tag == "stepper"
            val close = e.keyCode == KeyEvent.KEYCODE_BACK || e.keyCode == KeyEvent.KEYCODE_MENU || (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && !stepper)
            if (close) { if (e.action == KeyEvent.ACTION_DOWN) closePanel(); return true }
            return super.dispatchKeyEvent(e)
        }
        if (e.keyCode !in HANDLED) return super.dispatchKeyEvent(e)
        if (e.action == KeyEvent.ACTION_UP) {
            if (e.keyCode in SCRUB_KEYS) commitScrubSoon()
            return true
        }
        if (e.action != KeyEvent.ACTION_DOWN) return true

        if (error.visibility == View.VISIBLE) {
            when (e.keyCode) {
                KeyEvent.KEYCODE_BACK -> finishWithResult()
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER ->
                    play(index, engine?.position?.takeIf { it > 0 } ?: startFrom, forceVlc = engineIsVlc)
            }
            return true
        }
        // движка ещё нет (запускается торрент-движок): выйти можно всегда
        val eng = engine ?: run { if (e.keyCode == KeyEvent.KEYCODE_BACK) finishWithResult(); return true }

        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> {
                val ok = e.keyCode != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE && e.keyCode != KeyEvent.KEYCODE_SPACE
                if (next.visibility == View.VISIBLE && ok) playNext()
                else if (e.repeatCount == 0 && ok && cfg.string(K.KEY_OK) == "menu") openPanel()
                else if (e.repeatCount == 0) togglePlay()
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> if (paused) togglePlay()
            KeyEvent.KEYCODE_MEDIA_PAUSE -> if (!paused) togglePlay()
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> scrub(-step(e.repeatCount))
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> scrub(step(e.repeatCount))
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (resumedFrom != null && toast.alpha > 0f) {
                    eng.seekTo(0); hideToast.run()
                    showToast(tr("Смотрим с начала", "Watching from the start"), "", 1500)
                } else if (cfg.string(K.KEY_UPDOWN) == "seek") scrub(cfg.bigStepMs)
                else showControls(5000)
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> if (cfg.string(K.KEY_UPDOWN) == "seek") scrub(-cfg.bigStepMs) else openPanel()
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> openPanel()
            KeyEvent.KEYCODE_MEDIA_NEXT -> if (index + 1 < items.size) playNext()
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> if (index > 0) { save(); play(index - 1, savedPosition(items[index - 1]) ?: 0) }
            KeyEvent.KEYCODE_BACK -> when {
                next.visibility == View.VISIBLE -> { nextDismissed = true; next.visibility = View.GONE }
                scrubTarget != null -> { scrubTarget = null; handler.removeCallbacks(commitScrub); showControls(2000) }
                controlsShown && !paused -> { handler.removeCallbacks(hideControls); setControls(false) }
                else -> finishWithResult()
            }
        }
        return true
    }

    private fun togglePlay() {
        val eng = engine ?: return
        if (paused) {
            paused = false
            if (completed) { eng.seekTo(0); completed = false }
            eng.play()
            scheduleHide()
        } else {
            paused = true
            eng.pause()
            showControls(0)
        }
        if (!loading) badge.flash(!paused)
    }

    /** Шаг перемотки из настроек; если включено ускорение, растёт, пока кнопку держат: до 30 с, 1 мин, 2 мин. */
    private fun step(repeat: Int): Long {
        val base = cfg.seekStepMs
        if (!cfg.bool(K.SEEK_ACCEL)) return base
        return when {
            repeat < 6 -> base
            repeat < 16 -> maxOf(base, 30_000L)
            repeat < 30 -> maxOf(base, 60_000L)
            else -> maxOf(base, 120_000L)
        }
    }

    private fun scrub(delta: Long) {
        val eng = engine ?: return
        val dur = eng.duration.takeIf { it > 0 } ?: return
        scrubTarget = ((scrubTarget ?: eng.position) + delta).coerceIn(0, dur - 1000)
        handler.removeCallbacks(commitScrub)
        showControls(0)
        updateTimes()
    }

    /** Перематываем, когда кнопку отпустили — торренту не нужно дёргаться на каждом шаге. */
    private fun commitScrubSoon() {
        handler.removeCallbacks(commitScrub)
        handler.postDelayed(commitScrub, 650)
    }

    private val commitScrub = Runnable {
        scrubTarget?.let { engine?.seekTo(it) }
        scrubTarget = null
        updateTimes()
        if (!paused) scheduleHide() else showControls(0)
    }

    private fun playNext() {
        save()
        if (index + 1 < items.size) play(index + 1, savedPosition(items[index + 1]) ?: 0)
    }

    // ── Панель «звук · субтитры · скорость · экран» ─────────────────────────
    private fun openPanel() {
        buildPanel()
        panelOpen = true
        panel.visibility = View.VISIBLE
        panel.animate().translationX(0f).setDuration(260).setInterpolator(DecelerateInterpolator()).start()
        showControls(0)
        handler.post { firstFocusable()?.requestFocus() }
    }

    private fun closePanel() {
        panelOpen = false
        panel.animate().translationX(panel.width.toFloat()).setDuration(220).withEndAction {
            panel.visibility = View.INVISIBLE
        }.start()
        scheduleHide()
    }

    private fun firstFocusable(): View? {
        val all = (0 until panelList.childCount).map { panelList.getChildAt(it) }
        return all.firstOrNull { it.isFocusable && it.tag == "selected" } ?: all.firstOrNull { it.isFocusable }
    }

    private fun buildPanel() {
        val eng = engine ?: return
        panelList.removeAllViews()

        val quality = eng.videoTracks()
        if (quality.isNotEmpty()) {
            header(tr("Качество", "Quality"))
            row(tr("Авто", "Auto"), quality.none { it.selected }) { eng.selectVideo(null) }
            for (t in quality) row(t.label, t.selected) { eng.selectVideo(t.id) }
        }

        header(tr("Звук", "Audio"))
        val audio = eng.audioTracks()
        if (audio.isEmpty()) note(tr("Одна дорожка", "One track"))
        for (t in audio) row(t.label, t.selected) { eng.selectAudio(t.id) }
        stepper(K.AUDIO_DELAY, { audioDelay }) { audioDelay = it; engine?.setAudioDelay(it) }

        header(tr("Субтитры", "Subtitles"))
        val text = eng.textTracks()
        row(tr("Выключены", "Off"), text.none { it.selected }) { eng.selectText(null) }
        for (t in text) row(t.label, t.selected) { eng.selectText(t.id) }
        if (text.isNotEmpty()) stepper(K.SUB_DELAY, { subDelay }) { setSubDelay(it) }

        header(tr("Скорость", "Speed"))
        for (s in listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)) {
            val label = if (s == 1f) tr("Обычная", "Normal") else "×" + s.toString().removeSuffix(".0").let { if (tr("ru", "en") == "ru") it.replace('.', ',') else it }
            row(label, kotlin.math.abs(eng.speed - s) < 0.01f) {
                eng.speed = s
                prefs().edit().putFloat("lastSpeed", s).apply()
            }
        }

        header(tr("Масштаб", "Zoom"))
        val ru = tr("ru", "en") == "ru"
        for (o in K.SCALE.options) row(if (ru) o.ru else o.en, cfg.string(K.SCALE) == o.value) {
            eng.setScale(o.value.toInt())
            cfg.set(K.SCALE, o.value)
        }
        header(tr("Соотношение сторон", "Aspect ratio"))
        for (o in K.ASPECT.options) row(if (ru) o.ru else o.en, cfg.string(K.ASPECT) == o.value) {
            eng.setAspect(o.value)
            cfg.set(K.ASPECT, o.value)
        }

        val exo = eng as? ExoEngine
        if (exo != null && exo.isDovi) {
            header("Dolby Vision 5")
            for ((hdr, label) in listOf(false to tr("SDR, свой тон-маппинг", "SDR, own tone mapping"), true to tr("HDR10 на проектор", "HDR10 to the display"))) {
                row(label, exo.doviHdr == hdr) {
                    exo.doviHdr = hdr
                    // режим вывода задаётся при создании слоя — пересоздаём движок с того же места
                    val pos = eng.position
                    engine?.release(); engine = null
                    closePanel()
                    play(index, pos)
                }
            }
        }

        action(tr("Все настройки плеера", "All player settings")) {
            closePanel()
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        note(if (engineIsVlc) tr("Движок: VLC", "Engine: VLC") else tr("Движок: Media3", "Engine: Media3"))
    }

    /** Задержка субтитров в панели. Media3 сдвигает внешние файлы при открытии, поэтому при минусе открываем заново. */
    private fun setSubDelay(v: Int) {
        val before = subDelay
        subDelay = v
        engine?.setSubtitleDelay(v)
        val item = current() ?: return
        if (!engineIsVlc && (item.subs.isNotEmpty() || item.uri.scheme == "file") && (v < 0 || before < 0)) {
            handler.removeCallbacks(reopen)
            handler.postDelayed(reopen, 900)
        }
    }

    private val reopen = Runnable {
        val pos = engine?.position ?: return@Runnable
        val wasPaused = paused
        play(index, pos)
        if (wasPaused) { engine?.pause(); paused = true }
    }

    private fun header(text: String) {
        panelList.addView(TextView(this).apply {
            this.text = text.uppercase()
            textSize = 13f
            letterSpacing = 0.14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(getColor(R.color.accent_warm))
            setPadding(dp(16), dp(if (panelList.childCount == 0) 0 else 22), dp(16), dp(8))
        })
    }

    private fun note(text: String) {
        panelList.addView(TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(getColor(R.color.text_faint))
            setPadding(dp(16), dp(10), dp(16), dp(8))
        })
    }

    /** Отметка выбранного: точка, нарисованная фигурой (не символ). */
    private fun dot(selected: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setSize(dp(10), dp(10))
        if (selected) setColor(Color.WHITE) else setColor(Color.TRANSPARENT)
    }

    /** Цвет строки панели: тёмный на светлой подложке фокуса, иначе светлый. */
    private fun ink(normal: Int) = android.content.res.ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(0xFF0E1014.toInt(), normal))

    private fun item(text: String, selected: Boolean): TextView = TextView(this).apply {
        this.text = text
        tag = if (selected) "selected" else null
        textSize = 19f
        setTextColor(ink(if (selected) Color.WHITE else getColor(R.color.text_dim)))
        compoundDrawableTintList = ink(Color.WHITE)
        typeface = if (selected) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.DEFAULT
        background = getDrawable(R.drawable.item_bg)
        setPadding(dp(16), dp(13), dp(16), dp(13))
        gravity = Gravity.CENTER_VERTICAL
        compoundDrawablePadding = dp(14)
        setCompoundDrawablesRelative(dot(selected), null, null, null)
        isFocusable = true
        isClickable = true
    }

    private fun row(text: String, selected: Boolean, onClick: () -> Unit) {
        panelList.addView(item(text, selected).apply {
            setOnClickListener {
                onClick()
                handler.postDelayed({
                    val idx = panelList.indexOfChild(this)
                    buildPanel()
                    panelList.getChildAt(idx)?.requestFocus()
                    updateChips()
                }, 200)
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
    }

    private fun action(text: String, onClick: () -> Unit) {
        panelList.addView(item(text, false).apply {
            setCompoundDrawablesRelative(null, null, null, null)
            setTextColor(ink(Color.WHITE))
            setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
    }

    /** Число, которое меняют кнопки влево и вправо (задержки). Только на этот сеанс. */
    private fun stepper(s: Stepper, get: () -> Int, set: (Int) -> Unit) {
        val ru = tr("ru", "en") == "ru"
        val v = item("", false)
        fun paint() { v.text = (if (ru) s.ru else s.en) + "   " + Settings.stepLabel(s, get(), ru) }
        paint()
        v.tag = "stepper"
        val l = getDrawable(R.drawable.ic_chevron_left)!!.mutate().apply { setBounds(0, 0, dp(20), dp(20)) }
        val r = getDrawable(R.drawable.ic_chevron_right)!!.mutate().apply { setBounds(0, 0, dp(20), dp(20)) }
        v.setCompoundDrawablesRelative(l, null, r, null)
        v.setOnKeyListener { _, code, ev ->
            val dir = when (code) { KeyEvent.KEYCODE_DPAD_LEFT -> -1; KeyEvent.KEYCODE_DPAD_RIGHT -> 1; else -> 0 }
            if (dir == 0) return@setOnKeyListener false
            if (ev.action == KeyEvent.ACTION_DOWN) { set(s.clamp(get() + dir * s.step)); paint() }
            true
        }
        panelList.addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
    }

    // ── Сохранение и выход ───────────────────────────────────────────────────
    private fun save() {
        val eng = engine ?: return
        val item = current() ?: return
        val dur = eng.duration.takeIf { it > 0 } ?: return
        val pos = eng.position
        lastSave = System.currentTimeMillis()
        val list = playlist?.takeIf { items.size > 1 }
        // список мог убрать prune (запись истории, что на него ссылалась, удалили) — вернуть
        if (list != null) rememberPlaylist(items)
        val entry = History.Entry(History.key(item.uri), item.uri.toString(),
            intent.getStringExtra("title")?.takeIf { items.size == 1 } ?: item.title, pos, dur, lastSave, list)
        // дошли до титров: серия просмотрена, в «Продолжить просмотр» её не оставляем
        val inCredits = Chapters.creditsStart(chapters, dur)?.let { pos >= it } == true
        if (entry.finished || completed || inCredits) {
            // вместо неё — следующая серия с начала, если её ещё не начинали (сначала она: удаление
            // последней записи со ссылкой на список убрало бы и сам список)
            val next = items.getOrNull(index + 1)
            if (list != null && next != null && History.get(this, History.key(next.uri)) == null)
                History.save(this, History.Entry(History.key(next.uri), next.uri.toString(), next.title, 0, 0, lastSave, list))
            History.remove(this, entry.key)
        } else if (pos > 15_000) {
            History.save(this, entry)
            // кадр из видео — обложка в «Продолжить просмотр» лаунчера
            if (pos > 60_000) WatchNext.grab(this, video, entry) { WatchNext.publish(this, entry) }
        }
    }

    private fun finishWithResult() {
        save()
        val pos = engine?.position ?: 0L
        val dur = engine?.duration ?: 0L
        setResult(RESULT_OK, Intent()
            .putExtra("position", pos.toInt())                 // MX Player
            .putExtra("duration", dur.toInt())
            .putExtra("end_by", if (completed) "playback_completion" else "user")
            .putExtra("extra_position", pos)                   // VLC
            .putExtra("extra_duration", dur))
        finish()
    }

    companion object {
        private const val HOLD = "player"
        private const val CREDITS_COUNTDOWN_MS = 10_000L
        private val SCRUB_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
        )
        private val HANDLED = SCRUB_KEYS + setOf(
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_BACK,
        )
    }
}

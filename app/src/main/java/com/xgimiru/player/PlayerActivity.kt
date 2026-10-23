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
import com.xgimiru.player.dlna.PlayerBridge

/**
 * Плеер «Кино». Интерфейс свой, движков два (см. [Engine]): Media3 для современных
 * форматов и Dolby Vision, VLC для старья и как запасной. Понимает параметры, которые
 * Лампа и другие приложения передают внешним плеерам (MX Player / VLC), и возвращает,
 * где остановились.
 */
class PlayerActivity : Activity(), Engine.Listener, PlayerBridge.Controls {

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
    private var controlsShown = false
    private var panelOpen = false
    private var scrubTarget: Long? = null
    private var nextDismissed = false
    private var resumedFrom: Long? = null
    private var lastSave = 0L
    private var torrentStat: TorrServe.Stat? = null
    private var completed = false
    private var paused = false          // пауза по нашей команде (а не буферизация)
    private var loading = false
    private var loadingPct = -1
    private var fellBack = false        // уже переключались на запасной движок для этого файла
    private var ownTorrents = false     // в списке есть видео со встроенного торрент-движка
    private var engineRestarted = false // перезапускали встроенный движок после сбоя этого файла
    private var torrRetries = 0         // повторы торрента, который не успел подгрузиться
    private var torrRetryIndex = -1
    private var startFrom = 0L          // с какой позиции открывали текущий файл (для «попробовать ещё раз»)
    private var chapters: List<Chapters.Chapter> = emptyList()  // главы текущего файла: где титры
    private var chaptersOf: Uri? = null // чьи главы прочитаны или читаются
    private var nextFrom = 0L           // позиция, на которой появилась карточка следующей серии

    // ── Жизненный цикл ───────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
        if (ownTorrents) TorrService.hold(this, HOLD)
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
        // встроенный торрент-движок остановится, если не вернуться к фильму за полторы минуты
        if (ownTorrents) TorrService.release(this, HOLD)
    }

    override fun onDestroy() {
        session?.release()
        session = null
        PlayerBridge.detach(this, completed)
        if (ownTorrents) TorrService.release(this, HOLD)
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
    }

    private fun prefs() = getSharedPreferences("player", MODE_PRIVATE)

    // ── Открытие ─────────────────────────────────────────────────────────────
    private fun open(intent: Intent) {
        val uri = intent.data ?: run { finish(); return }
        PlayerBridge.attach(this)
        val baseTitle = intent.getStringExtra("title") ?: intent.getStringExtra("forcename")
            ?: intent.getStringExtra("name") ?: Formats.titleFromPath(uri.lastPathSegment ?: uri.path)

        @Suppress("DEPRECATION")
        val subs = intent.getParcelableArrayExtra("subs")?.mapNotNull { it as? Uri }.orEmpty()
        @Suppress("DEPRECATION")
        val list = intent.getParcelableArrayExtra("video_list")?.mapNotNull { it as? Uri }.orEmpty()
        val names = intent.getStringArrayExtra("video_list.name")
        // субтитры каждой серии (так передаёт TorrentActivity): по строке на серию, ссылки через \n
        val listSubs = intent.getStringArrayExtra("video_list.subs")
        index = 0
        playlist = null
        items = if (list.size > 1) {
            index = list.indexOf(uri).coerceAtLeast(0)
            list.mapIndexed { i, u ->
                val itemSubs = listSubs?.getOrNull(i)?.lines()?.filter { it.isNotBlank() }?.map(Uri::parse)
                Item(u, names?.getOrNull(i) ?: Formats.titleFromPath(u.lastPathSegment), itemSubs ?: if (u == uri) subs else emptyList())
            }.also { rememberPlaylist(it) }
        } else restorePlaylist(uri, subs) ?: listOf(Item(uri, baseTitle, subs))
        val own = items.any { TorrEngine.isOwn(it.uri) }
        if (own) TorrService.hold(this, HOLD) else if (ownTorrents) TorrService.release(this, HOLD)
        ownTorrents = own

        @Suppress("DEPRECATION")
        val explicit = (intent.extras?.get("position") as? Number)?.toLong()
            ?: (intent.extras?.get("extra_position") as? Number)?.toLong()
        val startPos = when {
            intent.getBooleanExtra("from_start", false) -> 0L
            explicit != null && explicit > 0 -> explicit
            else -> savedPosition(items[index]) ?: 0L
        }
        resumedFrom = startPos.takeIf { it > 0 && explicit == null }
        play(index, startPos)
        resumedFrom?.let { showToast("Продолжаем с ${Time.format(it)}", "▲ — смотреть с начала", 5000) }
    }

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
        if (TorrEngine.isOwn(items[i].uri) && !(TorrEngine.isRunning && items[i].uri.port == TorrEngine.port)) {
            startTorrEngine(i, startMs, forceVlc)
            return
        }
        val item = items[i]
        completed = false
        nextDismissed = false
        if (item.uri != chaptersOf) { chapters = emptyList(); chaptersOf = null }
        handler.removeCallbacks(loadChapters)
        if (i + 1 < items.size) handler.postDelayed(loadChapters, 5000)   // когда фильм уже пошёл
        paused = false
        if (!forceVlc) fellBack = false
        error.visibility = View.GONE
        next.visibility = View.GONE
        badge.hide()

        val wantVlc = forceVlc || Engine.prefersVlc(item.uri)
        if (engine == null || engineIsVlc != wantVlc) {
            engine?.release()
            engine = if (wantVlc) VlcEngine(this, video, this) else ExoEngine(this, video, this)
            engineIsVlc = wantVlc
            engine!!.setScale(prefs().getInt("scaleMode", 0))
        }
        engine!!.open(item.uri, startMs, item.subs)

        showTitles()
        showControls(4000)
        onBuffering(true, -1)
    }

    /**
     * Встроенный движок не запущен (остановился, пока фильм стоял в фоне) или порт в ссылке от
     * прошлого запуска: запустить движок, подставить порт во все его ссылки и играть.
     */
    private fun startTorrEngine(i: Int, startMs: Long, forceVlc: Boolean) {
        error.visibility = View.GONE
        showTitles()
        onBuffering(true, -1)
        io.execute {
            val r = runCatching { TorrEngine.start(this) }
            handler.post {
                if (isFinishing || isDestroyed) return@post
                r.onSuccess {
                    fun fix(u: Uri) = if (TorrEngine.isOwn(u)) TorrEngine.rebase(u) else u
                    items = items.map { it.copy(uri = fix(it.uri), subs = it.subs.map(::fix)) }
                    play(i, startMs, forceVlc)
                }.onFailure {
                    onBuffering(false, 100)
                    errorText.text = "Торрент-движок не запустился: ${it.message}"
                    error.visibility = View.VISIBLE
                }
            }
        }
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
        reportRemote()
        val playing = engine?.isPlaying == true
        if (playing) { paused = false; engineRestarted = false }
        dim.animate().alpha(if (playing || loading) 0f else if (paused) 1f else 0f).setDuration(250).start()
        if (playing) scheduleHide() else if (paused) showControls(0)
    }

    override fun onPausedBySystem() {
        if (paused) return
        paused = true
        showControls(0)
        onStateChanged()
        showToast("Пауза: звук ушёл с Bluetooth", "OK — продолжить на проекторе", 5000)
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
        if (index + 1 < items.size) playNext()
        else { completed = true; finishWithResult() }
    }

    override fun onError(message: String, fallback: Boolean) {
        onBuffering(false, 100)
        val item = current()
        if (item != null && TorrEngine.isOwn(item.uri) && !TorrEngine.isRunning && !engineRestarted) {
            // встроенный движок остановился (простой в фоне, сбой): поднять и продолжить с того же места
            engineRestarted = true
            play(index, engine?.position ?: 0L, forceVlc = engineIsVlc)
            return
        }
        if (item != null && TorrServe.isTorrServe(item.uri) && !fallback) {
            // торрент не успел подгрузиться (мало сидов, медленная сеть): движок это не «формат не тот»,
            // а пауза в данных. Ждём и продолжаем с того же места, до 5 раз на одну серию.
            if (torrRetryIndex != index) { torrRetryIndex = index; torrRetries = 0 }
            if (torrRetries < 5) {
                torrRetries++
                val pos = engine?.position ?: 0L
                onBuffering(true, 0)
                handler.postDelayed({ if (!isFinishing) play(index, pos, forceVlc = engineIsVlc) }, 4000)
                return
            }
            errorText.text = "Торрент не успевает загружаться: у раздачи мало сидов или медленная сеть."
            error.visibility = View.VISIBLE
            return
        }
        if (fallback && !engineIsVlc && !fellBack) {
            // Media3 не справился с форматом — пробуем тот же файл на VLC с того же места.
            fellBack = true
            val pos = engine?.position ?: 0L
            play(index, pos, forceVlc = true)
            showToast("Включил запасной движок", "этот файл лучше играет через VLC", 3500)
            return
        }
        errorText.text = if (item != null && TorrEngine.isOwn(item.uri) && !TorrEngine.isRunning)
            "Торрент-движок остановился" else message
        error.visibility = View.VISIBLE
    }

    // ── Отрисовка ────────────────────────────────────────────────────────────
    private val tick = object : Runnable {
        override fun run() {
            reportRemote()
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
        remainingText.text = if (dur > 0) {
            val left = ((dur - shown) / e.speed.coerceAtLeast(0.1f)).toLong()
            "−${Time.format(dur - shown)}   ·   до ${clockFormat.format(Date(now.time + left))}"
        } else ""
        if (buffering.visibility == View.VISIBLE) updateBufferText()

        // Следующая серия: с начала титров, если в файле есть такая глава, иначе за 30 секунд до конца
        val hasNext = index + 1 < items.size
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

        // TorrServe: скорость и пиры — пока грузится или открыты панели
        val item = current()
        if (item != null && TorrServe.isTorrServe(item.uri) && (buffering.visibility == View.VISIBLE || controlsShown)) {
            io.execute {
                val s = TorrServe.stat(item.uri)
                handler.post { torrentStat = s; showStat() }
            }
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

    private fun showStat() {
        val s = torrentStat
        val need = neededMbit(s)
        val line = when {
            s == null -> ""
            s.complete -> "✓ загружено полностью"
            need > 0 -> "↓ %.1f из %.0f Мбит/с · %d пир%s".format(s.speedMbit, need, s.peers, plural(s.peers))
            else -> "↓ %.1f Мбит/с · %d пир%s".format(s.speedMbit, s.peers, plural(s.peers))
        }
        net.text = line
        bufferDetail.text = line
        warnSlowSwarm(s, need)
    }

    /** Битрейт файла: размер × 8 / длительность, Мбит/с (0 — не знаем). */
    private fun neededMbit(s: TorrServe.Stat?): Double {
        val dur = engine?.duration ?: 0L
        if (s == null || s.fileBytes <= 0 || dur < 60_000) return 0.0
        return s.fileBytes * 8.0 / (dur / 1000.0) / 1e6
    }

    private var slowSince = 0L
    private var slowWarnedFor: Uri? = null

    /**
     * Раздача стабильно медленнее фильма (меньше 90 % битрейта полминуты, во время загрузки): говорим прямо,
     * один раз на файл. Кэш движка в памяти — 256 МБ, это меньше минуты такого видео, так что пауза не спасёт:
     * поможет раздача с большим числом сидов или полегче.
     */
    private fun warnSlowSwarm(s: TorrServe.Stat?, need: Double) {
        val uri = current()?.uri ?: return
        if (s == null || s.complete || need <= 0 || s.speedMbit >= need * 0.9 || slowWarnedFor == uri) { slowSince = 0; return }
        val now = System.currentTimeMillis()
        if (slowSince == 0L) { slowSince = now; return }
        if (now - slowSince < 30_000 || buffering.visibility != View.VISIBLE) return
        slowWarnedFor = uri
        showToast("Раздача медленнее фильма: %.0f из %.0f Мбит/с".format(s.speedMbit, need),
            "Будут остановки. Выберите раздачу с большим числом сидов или полегче (BDRip, WEB-DL)", 9000)
    }

    private fun plural(n: Int) = when {
        n % 10 == 1 && n % 100 != 11 -> ""
        n % 10 in 2..4 && n % 100 !in 12..14 -> "а"
        else -> "ов"
    }

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
        val labels = listOfNotNull(
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
        if (engine?.isPlaying == true && !panelOpen && scrubTarget == null) handler.postDelayed(hideControls, 3500)
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

    // ── DLNA: состояние для телефона и его команды (см. dlna/PlayerBridge) ──
    private fun reportRemote() {
        val e = engine
        val state = when {
            error.visibility == View.VISIBLE || completed -> PlayerBridge.State.STOPPED
            e?.isPlaying == true -> PlayerBridge.State.PLAYING
            paused -> PlayerBridge.State.PAUSED
            else -> PlayerBridge.State.LOADING
        }
        PlayerBridge.report(this, current()?.uri?.toString(), state, e?.position ?: 0L, e?.duration ?: 0L)
        reportSession(state, e)
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

    private fun reportSession(state: PlayerBridge.State, e: Engine?) {
        val s = session ?: return
        val st = when (state) {
            PlayerBridge.State.PLAYING -> android.media.session.PlaybackState.STATE_PLAYING
            PlayerBridge.State.PAUSED -> android.media.session.PlaybackState.STATE_PAUSED
            PlayerBridge.State.STOPPED -> android.media.session.PlaybackState.STATE_STOPPED
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

    override fun remotePlay() { if (paused && error.visibility != View.VISIBLE) togglePlay() }
    override fun remotePause() { if (!paused && engine != null) togglePlay() }
    override fun remoteSeek(ms: Long) {
        val e = engine ?: return
        val dur = e.duration
        e.seekTo(if (dur > 0) ms.coerceIn(0, dur - 1000) else ms)
        showControls(3000)
        updateTimes()
    }
    override fun remoteStop() = finishWithResult()

    // ── Пульт ────────────────────────────────────────────────────────────────
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (panelOpen) {
            val close = e.keyCode == KeyEvent.KEYCODE_BACK || e.keyCode == KeyEvent.KEYCODE_MENU || e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT
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
                if (next.visibility == View.VISIBLE && e.keyCode != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) playNext()
                else if (e.repeatCount == 0) togglePlay()
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> if (paused) togglePlay()
            KeyEvent.KEYCODE_MEDIA_PAUSE -> if (!paused) togglePlay()
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> scrub(-step(e.repeatCount))
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> scrub(step(e.repeatCount))
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (resumedFrom != null && toast.alpha > 0f) {
                    eng.seekTo(0); hideToast.run()
                    showToast("Смотрим с начала", "", 1500)
                } else showControls(5000)
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> openPanel()
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

    /** Шаг перемотки растёт, пока кнопку держат: 10 с → 30 с → 1 мин → 2 мин. */
    private fun step(repeat: Int) = when {
        repeat < 6 -> 10_000L
        repeat < 16 -> 30_000L
        repeat < 30 -> 60_000L
        else -> 120_000L
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

        header("Звук")
        val audio = eng.audioTracks()
        if (audio.isEmpty()) note("Одна дорожка")
        for (t in audio) row(t.label, t.selected) { eng.selectAudio(t.id) }

        header("Субтитры")
        val text = eng.textTracks()
        row("Выключены", text.none { it.selected }) {
            eng.selectText(null)
            prefs().edit().remove("subLang").apply()
        }
        for (t in text) row(t.label, t.selected) { eng.selectText(t.id) }

        header("Скорость")
        for (s in listOf(0.75f, 1f, 1.25f, 1.5f, 2f)) {
            val label = if (s == 1f) "Обычная" else "×" + s.toString().removeSuffix(".0").replace('.', ',')
            row(label, kotlin.math.abs(eng.speed - s) < 0.01f) { eng.speed = s }
        }

        header("Экран")
        val scale = prefs().getInt("scaleMode", 0)
        for ((mode, label) in listOf(0 to "Целиком", 1 to "Заполнить (обрезать края)", 2 to "Растянуть")) {
            row(label, scale == mode) {
                eng.setScale(mode)
                prefs().edit().putInt("scaleMode", mode).apply()
            }
        }

        val exo = eng as? ExoEngine
        if (exo != null && exo.isDovi) {
            header("Dolby Vision 5")
            for ((hdr, label) in listOf(false to "SDR — свой тон-маппинг", true to "HDR10 — отдать проектору")) {
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

        note(if (engineIsVlc) "Движок: VLC" else "Движок: Media3")
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

    private fun row(text: String, selected: Boolean, onClick: () -> Unit) {
        panelList.addView(TextView(this).apply {
            this.text = (if (selected) "●   " else "      ") + text
            tag = if (selected) "selected" else null
            textSize = 19f
            setTextColor(if (selected) Color.WHITE else getColor(R.color.text_dim))
            typeface = if (selected) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.DEFAULT
            background = getDrawable(R.drawable.item_bg)
            setPadding(dp(16), dp(13), dp(16), dp(13))
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
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

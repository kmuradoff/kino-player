package com.xgimiru.player.dlna

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import com.xgimiru.player.BuildConfig
import com.xgimiru.player.PlayerActivity
import com.xgimiru.player.R
import com.xgimiru.player.dlna.HttpServer.Request
import com.xgimiru.player.dlna.HttpServer.Response
import com.xgimiru.player.ui.tr
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URL
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * DLNA Media Renderer: AVTransport, RenderingControl и ConnectionManager поверх [HttpServer],
 * объявление в сети через [Ssdp], события GENA подписчикам. Сам ничего не играет: открывает
 * [PlayerActivity] (или [ImageActivity] для фото) и управляет ими через [PlayerBridge].
 *
 * Потоки: один планировщик (SSDP, события, смена сети), до четырёх на HTTP, один на доставку
 * событий. Без подписчиков и запросов всё спит, раз в 30 секунд уходит только NOTIFY.
 */
class Renderer(private val ctx: Context, private val uuid: String, val name: String) {

    private class Fault(val code: Int, msg: String) : Exception(msg)

    private class Sub(val sid: String, val service: String, val callbacks: List<URL>, var expires: Long) {
        var seq = 0L
    }

    private val scheduler = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "lumen-dlna").apply { isDaemon = true } }.apply {
        executeExistingDelayedTasksAfterShutdownPolicy = false
        removeOnCancelPolicy = true
    }
    private val sender = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(64),
        { r -> Thread(r, "lumen-dlna-events").apply { isDaemon = true } }, ThreadPoolExecutor.DiscardOldestPolicy())
        .apply { allowCoreThreadTimeOut(true) }
    private val http = HttpServer(::handle)
    private val ssdp = Ssdp(uuid, { http.port }, scheduler)
    private val audio = ctx.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()

    // AVTransport, всё под lock
    private var uri = ""
    private var meta = ""
    private var info: Upnp.Meta? = null
    private var nextUri = ""
    private var nextMeta = ""
    private var launchedAt = 0L      // открыли экран, ждём, когда он отзовётся
    private var startPos = 0L        // Seek до Play: с какого места начать

    // GENA
    private val subs = LinkedHashMap<String, Sub>()
    private val lastSent = HashMap<String, Map<String, String>>()
    private var ticking: ScheduledFuture<*>? = null
    private var debounce: ScheduledFuture<*>? = null

    private val icon: ByteArray by lazy {
        runCatching { ctx.resources.openRawResource(R.drawable.ic_app).use { it.readBytes() } }.getOrDefault(ByteArray(0))
    }

    /** Адрес в сети, для уведомления и настроек. */
    val address: String? get() = ssdp.address

    fun start() {
        HttpServer.SERVER = "Android/${android.os.Build.VERSION.RELEASE} UPnP/1.0 LumenPlay/${BuildConfig.VERSION_NAME}"
        PlayerBridge.onChange = { poke() }
        PlayerBridge.onEnded = { u -> runCatching { scheduler.execute { ended(u) } } }
        scheduler.execute {
            runCatching { http.start(PORT) }
            ssdp.start()
        }
    }

    fun stop() {
        PlayerBridge.onChange = null
        PlayerBridge.onEnded = null
        runCatching {
            scheduler.execute {
                ssdp.stop()
                http.stop()
            }
        }
        scheduler.shutdown()
        sender.shutdown()
    }

    /** Wi-Fi переподключился или сменился адрес: через 2 секунды (сеть успевает устояться) пересоздать SSDP. */
    fun networkChanged() = runCatching {
        scheduler.execute {
            debounce?.cancel(false)
            debounce = scheduler.schedule({ runCatching { ssdp.refresh() } }, 2, TimeUnit.SECONDS)
        }
    }

    // ── HTTP ─────────────────────────────────────────────────────────────────
    private fun handle(r: Request): Response {
        val p = r.path
        val service = when {
            p.startsWith("/AVTransport/") -> Upnp.AVT
            p.startsWith("/RenderingControl/") -> Upnp.RCS
            p.startsWith("/ConnectionManager/") -> Upnp.CMS
            else -> null
        }
        return when (r.method) {
            "GET", "HEAD" -> when {
                p == "/" || p == "/description.xml" -> Response.xml(Descriptions.device(uuid, name, BuildConfig.VERSION_NAME))
                p == "/icon.png" -> Response(200, icon, "image/png")
                p.endsWith("/scpd.xml") -> when (service) {
                    Upnp.AVT -> Response.xml(Descriptions.AVT_SCPD)
                    Upnp.RCS -> Response.xml(Descriptions.RCS_SCPD)
                    Upnp.CMS -> Response.xml(Descriptions.CMS_SCPD)
                    else -> Response(404)
                }
                else -> Response(404)
            }
            "POST" -> if (service != null && p.endsWith("/control")) control(service, r) else Response(404)
            "SUBSCRIBE" -> if (service != null && p.endsWith("/event")) subscribe(service, r) else Response(404)
            "UNSUBSCRIBE" -> if (service != null && p.endsWith("/event")) unsubscribe(r) else Response(404)
            else -> Response(405)
        }
    }

    private fun control(service: String, r: Request): Response {
        val a = Upnp.parseSoap(r.text()) ?: return Response.xml(Upnp.soapFault(401, "Invalid Action"), 500)
        val out = try {
            a.args["InstanceID"]?.trim()?.let { if (it != "0") throw Fault(718, "Invalid InstanceID") }
            when (service) {
                Upnp.AVT -> avt(a.name, a.args)
                Upnp.RCS -> rcs(a.name, a.args)
                else -> cms(a.name, a.args)
            }
        } catch (f: Fault) {
            return Response.xml(Upnp.soapFault(f.code, f.message ?: ""), 500)
        }
        poke()
        return Response.xml(Upnp.soapResponse(service, a.name, out))
    }

    // ── AVTransport ──────────────────────────────────────────────────────────
    private fun avt(action: String, args: Map<String, String>): List<Pair<String, String>> = synchronized(lock) {
        when (action) {
            "SetAVTransportURI" -> {
                val u = args["CurrentURI"]?.trim() ?: throw Fault(402, "Invalid Args")
                if (u.isEmpty()) {
                    if (ours()) PlayerBridge.command { it.remoteStop() }
                    uri = ""; meta = ""; info = null; launchedAt = 0
                    return emptyList()
                }
                check(u)
                val wasActive = ours() || pending()
                uri = u
                meta = args["CurrentURIMetaData"]?.takeIf { it != "NOT_IMPLEMENTED" }.orEmpty()
                info = Upnp.parseDidl(meta)
                startPos = 0
                // уже что-то играет: по стандарту новый адрес играет сразу, в том же состоянии
                if (wasActive) launch()
                emptyList()
            }
            "SetNextAVTransportURI" -> {
                val u = args["NextURI"]?.trim() ?: throw Fault(402, "Invalid Args")
                if (u.isNotEmpty()) check(u)
                nextUri = u
                nextMeta = if (u.isEmpty()) "" else args["NextURIMetaData"]?.takeIf { it != "NOT_IMPLEMENTED" }.orEmpty()
                emptyList()
            }
            "Play" -> {
                if (uri.isEmpty()) throw Fault(701, "Transition not available")
                val s = PlayerBridge.snapshot
                when {
                    ours() && s.state == PlayerBridge.State.PAUSED -> PlayerBridge.command { it.remotePlay() }
                    ours() || pending() -> {}
                    else -> if (!launch()) throw Fault(701, "Lumen Play is in the background: allow it to display over other apps")
                }
                emptyList()
            }
            "Pause" -> {
                if (ours()) PlayerBridge.command { it.remotePause() }
                emptyList()
            }
            "Stop" -> {
                if (ours()) PlayerBridge.command { it.remoteStop() }
                launchedAt = 0
                startPos = 0
                emptyList()
            }
            "Seek" -> {
                val unit = args["Unit"]?.trim()?.uppercase() ?: throw Fault(402, "Invalid Args")
                when (unit) {
                    "REL_TIME", "ABS_TIME" -> {
                        val ms = Upnp.parseTime(args["Target"]) ?: throw Fault(711, "Illegal seek target")
                        if (ours()) PlayerBridge.command { it.remoteSeek(ms) } else startPos = ms
                    }
                    "TRACK_NR" -> {}
                    else -> throw Fault(710, "Seek mode not supported")
                }
                emptyList()
            }
            "Next" -> {
                if (nextUri.isNotEmpty()) advance()
                emptyList()
            }
            "Previous" -> emptyList()
            "GetMediaInfo" -> listOf(
                "NrTracks" to if (uri.isEmpty()) "0" else "1",
                "MediaDuration" to Upnp.time(duration()),
                "CurrentURI" to uri,
                "CurrentURIMetaData" to meta,
                "NextURI" to nextUri,
                "NextURIMetaData" to nextMeta,
                "PlayMedium" to if (uri.isEmpty()) "NONE" else "NETWORK",
                "RecordMedium" to "NOT_IMPLEMENTED",
                "WriteStatus" to "NOT_IMPLEMENTED",
            )
            "GetTransportInfo" -> listOf(
                "CurrentTransportState" to state(),
                "CurrentTransportStatus" to "OK",
                "CurrentSpeed" to "1",
            )
            "GetPositionInfo" -> {
                val pos = Upnp.time(position())
                listOf(
                    "Track" to if (uri.isEmpty()) "0" else "1",
                    "TrackDuration" to Upnp.time(duration()),
                    "TrackMetaData" to meta,
                    "TrackURI" to uri,
                    "RelTime" to pos,
                    "AbsTime" to pos,
                    "RelCount" to "2147483647",
                    "AbsCount" to "2147483647",
                )
            }
            "GetDeviceCapabilities" -> listOf(
                "PlayMedia" to "NETWORK",
                "RecMedia" to "NOT_IMPLEMENTED",
                "RecQualityModes" to "NOT_IMPLEMENTED",
            )
            "GetTransportSettings" -> listOf("PlayMode" to "NORMAL", "RecQualityMode" to "NOT_IMPLEMENTED")
            "GetCurrentTransportActions" -> listOf("Actions" to actions(state()))
            else -> throw Fault(401, "Invalid Action")
        }
    }

    /** Играем только по сети: http и https, без локальных файлов и чужих схем. */
    private fun check(u: String) {
        if (u.length > 8192) throw Fault(716, "Resource not found")
        val scheme = runCatching { Uri.parse(u).scheme?.lowercase() }.getOrNull()
        if (scheme != "http" && scheme != "https") throw Fault(714, "Illegal MIME-type")
    }

    /** Экран показывает именно наш адрес (а не фильм, открытый, например, из Лампы). */
    private fun ours(): Boolean {
        val s = PlayerBridge.snapshot
        return uri.isNotEmpty() && s.uri == uri && s.state != PlayerBridge.State.STOPPED
    }

    private fun pending() = launchedAt > 0 && SystemClock.elapsedRealtime() - launchedAt < LAUNCH_WAIT_MS

    private fun state(): String {
        val s = PlayerBridge.snapshot
        return when {
            uri.isEmpty() -> "NO_MEDIA_PRESENT"
            ours() -> {
                launchedAt = 0
                when (s.state) {
                    PlayerBridge.State.PLAYING -> "PLAYING"
                    PlayerBridge.State.PAUSED -> "PAUSED_PLAYBACK"
                    else -> "TRANSITIONING"
                }
            }
            pending() -> "TRANSITIONING"
            else -> "STOPPED"
        }
    }

    private fun actions(state: String) = when (state) {
        "PLAYING" -> "Pause,Stop,Seek"
        "PAUSED_PLAYBACK" -> "Play,Stop,Seek"
        "STOPPED" -> "Play,Seek"
        "TRANSITIONING" -> "Stop"
        else -> ""
    }

    private fun duration(): Long {
        val s = PlayerBridge.snapshot
        return if (ours() && s.duration > 0) s.duration else info?.durationMs ?: 0L
    }

    private fun position(): Long = if (ours()) PlayerBridge.snapshot.position else 0L

    private fun isImage(u: String, m: Upnp.Meta?): Boolean {
        if (m?.upnpClass?.contains("imageItem") == true) return true
        val mime = m?.protocolInfo?.split(':')?.getOrNull(2)?.lowercase()
        if (mime != null && mime != "*") return mime.startsWith("image/")
        val ext = Uri.parse(u).lastPathSegment?.substringAfterLast('.', "")?.lowercase()
        return ext in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
    }

    /**
     * Android 10+ не даёт открыть экран из фона, если нет разрешения «поверх других приложений»
     * и Lumen Play не на экране. Тогда и пытаться не стоит: заблокированный запуск всплыл бы позже,
     * когда пользователь сам откроет Lumen Play.
     */
    private fun blocked(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 29 || Settings.canDrawOverlays(ctx)) return false
        val i = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(i)
        return i.importance != ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
            i.importance != ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
    }

    /** Открыть экран просмотра с текущим адресом (под lock). false: Android не даст открыть из фона. */
    private fun launch(): Boolean {
        if (blocked()) {
            launchedAt = 0
            main.post {
                Toast.makeText(ctx, tr(
                    "Lumen Play не может открыть видео с телефона из фона. Откройте Lumen Play и повторите или " +
                        "разрешите в его настройках открываться поверх других приложений.",
                    "Lumen Play cannot open the video from the background. Open Lumen Play and try again, or " +
                        "allow it to open over other apps in its settings."), Toast.LENGTH_LONG).show()
            }
            return false
        }
        val u = uri
        val title = info?.title
        val pos = startPos
        val image = isImage(u, info)
        startPos = 0
        launchedAt = SystemClock.elapsedRealtime()
        main.post {
            val i = Intent(ctx, if (image) ImageActivity::class.java else PlayerActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setData(Uri.parse(u))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (!image) {
                title?.let { i.putExtra("title", it) }
                if (pos > 0) i.putExtra("position", pos) else i.putExtra("from_start", true)
            }
            runCatching { ctx.startActivity(i) }
        }
        return true
    }

    /** Следующий адрес из SetNextAVTransportURI становится текущим (под lock). */
    private fun advance() {
        uri = nextUri
        meta = nextMeta
        info = Upnp.parseDidl(meta)
        nextUri = ""
        nextMeta = ""
        startPos = 0
        launch()
    }

    /** Экран сам закрылся в конце файла: есть следующий, играем его. */
    private fun ended(u: String?) {
        synchronized(lock) { if (u != null && u == uri && nextUri.isNotEmpty()) advance() }
        evaluate()
    }

    // ── RenderingControl ─────────────────────────────────────────────────────
    @Volatile private var audible = -1

    /** Громкость 0…100. Без звука Android отдаёт 0: показываем последнюю слышимую, как требует UPnP. */
    private fun volume(): Int {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val v = (audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max).roundToInt()
        if (!muted()) audible = v
        return if (muted() && v == 0 && audible >= 0) audible else v
    }

    private fun muted() = audio.isStreamMute(AudioManager.STREAM_MUSIC)

    private fun rcs(action: String, args: Map<String, String>): List<Pair<String, String>> = when (action) {
        "GetVolume" -> listOf("CurrentVolume" to volume().toString())
        "SetVolume" -> {
            val v = args["DesiredVolume"]?.trim()?.toIntOrNull()?.takeIf { it in 0..100 } ?: throw Fault(402, "Invalid Args")
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, (v * max / 100f).roundToInt(), AudioManager.FLAG_SHOW_UI)
            emptyList()
        }
        "GetMute" -> listOf("CurrentMute" to if (muted()) "1" else "0")
        "SetMute" -> {
            val m = args["DesiredMute"]?.trim()?.lowercase() ?: throw Fault(402, "Invalid Args")
            val on = m == "1" || m == "true" || m == "yes"
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                if (on) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
            emptyList()
        }
        "ListPresets" -> listOf("CurrentPresetNameList" to "FactoryDefaults")
        "SelectPreset" -> emptyList()
        else -> throw Fault(401, "Invalid Action")
    }

    // ── ConnectionManager ────────────────────────────────────────────────────
    private fun cms(action: String, args: Map<String, String>): List<Pair<String, String>> = when (action) {
        "GetProtocolInfo" -> listOf("Source" to "", "Sink" to Descriptions.SINK)
        "GetCurrentConnectionIDs" -> listOf("ConnectionIDs" to "0")
        "GetCurrentConnectionInfo" -> {
            if (args["ConnectionID"]?.trim() != "0") throw Fault(706, "Invalid connection reference")
            listOf("RcsID" to "0", "AVTransportID" to "0", "ProtocolInfo" to "", "PeerConnectionManager" to "",
                "PeerConnectionID" to "-1", "Direction" to "Input", "Status" to "OK")
        }
        else -> throw Fault(401, "Invalid Action")
    }

    // ── События GENA ─────────────────────────────────────────────────────────
    private fun vars(service: String): List<Pair<String, String>> = when (service) {
        Upnp.AVT -> synchronized(lock) {
            val st = state()
            val dur = Upnp.time(duration())
            listOf(
                "TransportState" to st,
                "TransportStatus" to "OK",
                "PlaybackStorageMedium" to if (uri.isEmpty()) "NONE" else "NETWORK",
                "PossiblePlaybackStorageMedia" to "NETWORK",
                "CurrentPlayMode" to "NORMAL",
                "TransportPlaySpeed" to "1",
                "NumberOfTracks" to if (uri.isEmpty()) "0" else "1",
                "CurrentTrack" to if (uri.isEmpty()) "0" else "1",
                "CurrentTrackDuration" to dur,
                "CurrentMediaDuration" to dur,
                "CurrentTrackURI" to uri,
                "AVTransportURI" to uri,
                "CurrentTrackMetaData" to meta,
                "AVTransportURIMetaData" to meta,
                "NextAVTransportURI" to nextUri,
                "NextAVTransportURIMetaData" to nextMeta,
                "CurrentTransportActions" to actions(st),
            )
        }
        Upnp.RCS -> listOf(
            "Volume" to volume().toString(),
            "Mute" to if (muted()) "1" else "0",
            "PresetNameList" to "FactoryDefaults",
        )
        else -> listOf("SourceProtocolInfo" to "", "SinkProtocolInfo" to Descriptions.SINK, "CurrentConnectionIDs" to "0")
    }

    private fun body(service: String, vars: List<Pair<String, String>>): String = when (service) {
        Upnp.AVT -> Upnp.propertySet(listOf("LastChange" to Upnp.lastChange("AVT/", vars)))
        Upnp.RCS -> Upnp.propertySet(listOf("LastChange" to Upnp.lastChange("RCS/", vars)))
        else -> Upnp.propertySet(vars)
    }

    private fun subscribe(service: String, r: Request): Response {
        val timeout = r.header("TIMEOUT")?.substringAfter("Second-", "")?.trim()?.toIntOrNull()?.coerceIn(30, 7200) ?: 1800
        val old = r.header("SID")
        if (old != null) {
            if (r.header("CALLBACK") != null || r.header("NT") != null) return Response(400)
            synchronized(lock) {
                val s = subs[old]?.takeIf { it.service == service } ?: return Response(412)
                s.expires = SystemClock.elapsedRealtime() + timeout * 1000L
            }
            return Response(200, headers = listOf("SID" to old, "TIMEOUT" to "Second-$timeout"))
        }
        if (r.header("NT") != "upnp:event") return Response(412)
        val callbacks = callbacks(r.header("CALLBACK"))
        if (callbacks.isEmpty()) return Response(412)
        val sub = Sub("uuid:" + UUID.randomUUID(), service, callbacks, SystemClock.elapsedRealtime() + timeout * 1000L)
        synchronized(lock) {
            while (subs.size >= MAX_SUBS) subs.remove(subs.keys.first())
            subs[sub.sid] = sub
        }
        runCatching { scheduler.execute { ensureTicking() } }
        return Response(200, headers = listOf("SID" to sub.sid, "TIMEOUT" to "Second-$timeout"),
            after = { send(sub, body(service, vars(service))) })
    }

    private fun unsubscribe(r: Request): Response {
        val sid = r.header("SID") ?: return Response(412)
        synchronized(lock) { subs.remove(sid) } ?: return Response(412)
        return Response(200)
    }

    /** «<http://192.168.1.5:49152/cb>»: только http и только адреса локальной сети. */
    private fun callbacks(h: String?): List<URL> {
        if (h == null) return emptyList()
        return Regex("<([^>]{1,512})>").findAll(h).mapNotNull { m ->
            runCatching {
                val u = URL(m.groupValues[1].trim())
                val ip = u.host.takeIf { it.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}")) } ?: return@runCatching null
                val a = InetAddress.getByName(ip) as? Inet4Address ?: return@runCatching null
                u.takeIf { u.protocol == "http" && (a.isSiteLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress) }
            }.getOrNull()
        }.take(3).toList()
    }

    private fun send(sub: Sub, body: String) {
        val seq = synchronized(lock) { sub.seq.also { sub.seq = if (it >= 0xFFFFFFFFL) 1 else it + 1 } }
        runCatching {
            sender.execute {
                for (u in sub.callbacks) if (HttpServer.notify(u, sub.sid, seq, body)) break
            }
        }
    }

    /** Пока есть подписчики, раз в секунду сверяем состояние (громкость меняют и с пульта). */
    private fun ensureTicking() {
        if (ticking != null) return
        ticking = scheduler.scheduleWithFixedDelay({ runCatching { tick() } }, 1, 1, TimeUnit.SECONDS)
    }

    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        val empty = synchronized(lock) {
            subs.values.removeAll { it.expires < now }
            subs.isEmpty()
        }
        if (empty) {
            ticking?.cancel(false)
            ticking = null
            lastSent.clear()
            return
        }
        evaluate()
    }

    /** Состояние поменялось: сообщить подписчикам сразу, не дожидаясь секунды. */
    private fun poke() {
        runCatching { scheduler.execute { evaluate() } }
    }

    /** На потоке планировщика: разослать изменившиеся переменные. */
    private fun evaluate() {
        val targets = synchronized(lock) { subs.values.toList() }
        if (targets.isEmpty()) return
        for (svc in listOf(Upnp.AVT, Upnp.RCS)) {
            val cur = vars(svc)
            val prev = lastSent[svc]
            lastSent[svc] = cur.toMap()
            if (prev == null) continue
            val changed = cur.filter { prev[it.first] != it.second }
            if (changed.isEmpty()) continue
            val b = body(svc, changed)
            for (s in targets) if (s.service == svc) send(s, b)
        }
    }

    companion object {
        private const val PORT = 49595
        private const val MAX_SUBS = 32
        private const val LAUNCH_WAIT_MS = 20_000L
    }
}

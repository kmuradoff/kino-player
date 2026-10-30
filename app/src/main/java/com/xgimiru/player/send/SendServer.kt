package com.xgimiru.player.send

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.xgimiru.player.BuildConfig
import com.xgimiru.player.PlayerActivity
import com.xgimiru.player.StreamRequest
import com.xgimiru.player.dlna.DlnaSettings
import com.xgimiru.player.dlna.HttpServer
import com.xgimiru.player.dlna.PlayerBridge
import com.xgimiru.player.ui.tr
import org.json.JSONObject

/**
 * Приёмник «Отправить на Lumen Play»: HTTP на порту [SendProtocol.PORT] и объявление в сети
 * (NSD [SendProtocol.SERVICE_TYPE]). Расширение браузера присылает ссылку на видео со страницы,
 * плеер открывается тем же путём, что и по интенту VIEW ([StreamRequest]). Протокол:
 * docs/send-protocol.md. Живёт внутри службы DlnaService, пока Lumen Play установлен.
 */
class SendServer(ctx: Context) {
    private val app = ctx.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val pairing = Pairing(PrefsTokenStore(app))
    private val display = PairCodeDisplay(app)
    private val http = HttpServer(::handle, "lumen-send")
    private var nsd: NsdManager.RegistrationListener? = null

    /** Что отправили последним: по адресу из [PlayerBridge] понятно, что играет наше. */
    @Volatile private var sentUrl: String? = null
    @Volatile private var sentTitle: String? = null

    fun start() {
        runCatching { http.start(SendProtocol.PORT) }
        advertise()
    }

    fun stop() {
        nsd?.let { l -> runCatching { app.getSystemService(NsdManager::class.java).unregisterService(l) } }
        nsd = null
        http.stop()
        display.hide()
    }

    private fun advertise() {
        val port = http.port.takeIf { it > 0 } ?: return
        val info = NsdServiceInfo().apply {
            serviceName = DlnaSettings.friendlyName(app)
            serviceType = SendProtocol.SERVICE_TYPE
            setPort(port)
            setAttribute("path", "/lumen")
            setAttribute("version", BuildConfig.VERSION_NAME)
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) {}
            override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) {}
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) {}
        }
        runCatching {
            app.getSystemService(NsdManager::class.java).registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
            nsd = l
        }
    }

    // ── Запросы ─────────────────────────────────────────────────────────────

    private fun handle(req: HttpServer.Request): HttpServer.Response {
        val origin = req.header("origin")
        if (!SendProtocol.originAllowed(origin)) return json(403, error("forbidden_origin", "Запросы принимаются только от расширения браузера"), null)
        if (req.method == "OPTIONS") return preflight(origin)
        if (req.body.size > SendProtocol.MAX_BODY) return json(413, error("too_large", "Тело запроса больше 64 КБ"), origin)
        val body = req.text()
        return try {
            when (req.path) {
                "/lumen/info" -> if (req.method == "GET") info(req, origin) else notAllowed(origin)
                "/lumen/pair" -> if (req.method == "POST") pair(body, origin) else notAllowed(origin)
                "/lumen/pair/confirm" -> if (req.method == "POST") confirm(body, origin) else notAllowed(origin)
                "/lumen/play" -> if (req.method != "POST") notAllowed(origin) else authed(req, origin) { play(body, origin) }
                "/lumen/control" -> if (req.method != "POST") notAllowed(origin) else authed(req, origin) { control(body, origin) }
                "/lumen/state" -> if (req.method != "GET") notAllowed(origin) else authed(req, origin) { state(origin) }
                else -> json(404, error("not_found", "Неизвестный путь"), origin)
            }
        } catch (e: SendProtocol.BadRequest) {
            json(400, error("bad_request", e.message ?: "Неверный запрос"), origin)
        }
    }

    private fun info(req: HttpServer.Request, origin: String?) = json(200, JSONObject()
        .put("app", "Lumen Play")
        .put("version", BuildConfig.VERSION_NAME)
        .put("name", DlnaSettings.deviceName(app))
        .put("paired", pairing.anyDevice()), origin)

    private fun pair(body: String, origin: String?): HttpServer.Response {
        val client = SendProtocol.parsePairStart(body)
        return when (val r = pairing.start(client)) {
            is Pairing.Start.Code -> {
                display.show(r.code, r.client, Pairing.CODE_TTL_MS)
                json(200, JSONObject().put("ok", true).put("expiresIn", Pairing.CODE_TTL_MS / 1000), origin)
            }
            is Pairing.Start.Locked -> locked(r.retryAfterSec, origin)
        }
    }

    private fun confirm(body: String, origin: String?): HttpServer.Response {
        val code = SendProtocol.parsePairConfirm(body)
        return when (val r = pairing.confirm(code)) {
            is Pairing.Confirm.Ok -> {
                display.hide()
                display.message(tr("«${r.client}» может отправлять видео на Lumen Play",
                    "\"${r.client}\" can now send videos to Lumen Play"))
                json(200, JSONObject().put("token", r.token), origin)
            }
            is Pairing.Confirm.Wrong -> json(403, error("bad_code", "Неверный код").put("attemptsLeft", r.attemptsLeft), origin)
            is Pairing.Confirm.Locked -> {
                display.hide()
                locked(r.retryAfterSec, origin)
            }
            Pairing.Confirm.NoCode -> json(409, error("no_pairing", "Кода нет или он истёк, запросите новый"), origin)
        }
    }

    private fun play(body: String, origin: String?): HttpServer.Response {
        val p = SendProtocol.parsePlay(body)
        if (blocked()) {
            display.message(tr(
                "Lumen Play не может открыть видео из фона. Откройте Lumen Play и повторите или " +
                    "разрешите в его настройках открываться поверх других приложений.",
                "Lumen Play cannot open the video from the background. Open Lumen Play and try again, or " +
                    "allow it to open over other apps in its settings."))
            return json(409, error("background_blocked", "Откройте Lumen Play на проекторе или разрешите ему открываться поверх других приложений"), origin)
        }
        sentUrl = Uri.parse(p.url).toString()
        sentTitle = p.title
        main.post {
            val uri = Uri.parse(p.url)
            val i = Intent(app, PlayerActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val mime = p.mime
            if (mime != null) i.setDataAndType(uri, mime) else i.data = uri
            p.title?.let { i.putExtra("title", it) }
            if (p.positionMs > 0) i.putExtra("position", p.positionMs) else i.putExtra("from_start", true)
            if (p.headers.isNotEmpty()) {
                i.putExtra(StreamRequest.EXTRA_HEADERS, p.headers.flatMap { listOf(it.key, it.value) }.toTypedArray())
            }
            if (p.subtitles.isNotEmpty()) i.putExtra("subs", p.subtitles.map { Uri.parse(it.url) }.toTypedArray())
            runCatching { app.startActivity(i) }
        }
        return json(200, JSONObject().put("ok", true), origin)
    }

    private fun control(body: String, origin: String?): HttpServer.Response {
        val c = SendProtocol.parseControl(body)
        val ok = PlayerBridge.command {
            when (c.action) {
                "pause" -> it.remotePause()
                "play" -> it.remotePlay()
                "seek" -> it.remoteSeek(c.positionMs ?: 0L)
                "stop" -> it.remoteStop()
            }
        }
        return json(200, JSONObject().put("ok", ok), origin)
    }

    private fun state(origin: String?): HttpServer.Response {
        val s = PlayerBridge.snapshot
        val open = s.state != PlayerBridge.State.STOPPED
        val title = sentTitle.takeIf { open && s.uri != null && s.uri == sentUrl }
        return json(200, JSONObject()
            .put("playing", s.state == PlayerBridge.State.PLAYING)
            .put("state", s.state.name.lowercase())
            .put("position", if (open) s.position / 1000.0 else 0.0)
            .put("duration", if (open) s.duration / 1000.0 else 0.0)
            .put("title", title ?: JSONObject.NULL), origin)
    }

    private inline fun authed(req: HttpServer.Request, origin: String?, block: () -> HttpServer.Response) =
        if (pairing.authorized(req.header("authorization"))) block()
        else json(401, error("unauthorized", "Браузер не подключён или удалён в настройках Lumen Play"), origin, listOf("WWW-Authenticate" to "Bearer"))

    /** Android 10+ не даёт открыть экран из фона без разрешения «поверх других окон». */
    private fun blocked(): Boolean {
        if (Build.VERSION.SDK_INT < 29 || Settings.canDrawOverlays(app)) return false
        val i = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(i)
        return i.importance != ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
            i.importance != ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
    }

    // ── Ответы ──────────────────────────────────────────────────────────────

    /** Тело ошибки по протоколу: код для расширения и сообщение по-русски для человека. */
    private fun error(code: String, message: String) = JSONObject().put("error", code).put("message", message)

    private fun locked(sec: Long, origin: String?) = json(429,
        error("locked", "Пять неверных кодов подряд, подключение закрыто на 5 минут").put("retryAfter", sec),
        origin, listOf("Retry-After" to sec.toString()))

    private fun notAllowed(origin: String?) = json(405, error("method_not_allowed", "Этот метод здесь не принимается"), origin)

    private fun cors(origin: String?): List<Pair<String, String>> =
        if (origin == null) emptyList() else listOf("Access-Control-Allow-Origin" to origin, "Vary" to "Origin")

    private fun preflight(origin: String?) = HttpServer.Response(204, headers = cors(origin) + listOf(
        "Access-Control-Allow-Methods" to "GET, POST, OPTIONS",
        "Access-Control-Allow-Headers" to "Authorization, Content-Type",
        "Access-Control-Allow-Private-Network" to "true",
        "Access-Control-Max-Age" to "600",
    ))

    private fun json(status: Int, o: JSONObject, origin: String?, extra: List<Pair<String, String>> = emptyList()) =
        HttpServer.Response(status, o.toString().toByteArray(Charsets.UTF_8), "application/json; charset=utf-8",
            cors(origin) + extra + ("Cache-Control" to "no-store"))
}

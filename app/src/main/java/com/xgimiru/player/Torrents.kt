package com.xgimiru.player

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.system.Os
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Где «Кино» берёт торренты. Встроенный движок, если не задан адрес внешнего TorrServe: тогда
 * отдельное приложение TorrServe не нужно. Ссылки на видео от Лампы и других приложений играют
 * как раньше, какой бы режим ни был выбран.
 */
object TorrSettings {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("torrents", Context.MODE_PRIVATE)

    /** true: встроенный движок. По умолчанию он, если не задан адрес внешнего TorrServe. */
    fun builtin(ctx: Context): Boolean {
        if (!TorrEngine.isAvailable(ctx)) return false
        val p = prefs(ctx)
        return if (p.contains("builtin")) p.getBoolean("builtin", true) else external(ctx) == null
    }

    fun setBuiltin(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("builtin", on).apply()

    /** Адрес внешнего TorrServe, например http://192.168.1.10:8090, или null. */
    fun external(ctx: Context): String? = prefs(ctx).getString("external", null)

    fun setExternal(ctx: Context, addr: String?) =
        prefs(ctx).edit().putString("external", addr?.let { normalize(it) }).apply()

    /** «192.168.1.10» → «http://192.168.1.10:8090»; пустая строка → null. */
    fun normalize(addr: String): String? {
        var a = addr.trim().trimEnd('/')
        if (a.isEmpty()) return null
        if (!a.contains("://")) a = "http://$a"
        val u = Uri.parse(a)
        return if (u.port == -1) "${u.scheme}://${u.host}:8090" else "${u.scheme}://${u.host}:${u.port}"
    }

    /** Память под кэш встроенного движка в МБ; 0 = подобрать по объёму памяти устройства. */
    fun cacheMb(ctx: Context) = prefs(ctx).getInt("cacheMb", 0)

    fun setCacheMb(ctx: Context, mb: Int) = prefs(ctx).edit().putInt("cacheMb", mb).apply()

    fun effectiveCacheMb(ctx: Context) = cacheMb(ctx).takeIf { it > 0 } ?: autoCacheMb(ctx)

    /** 48 МБ до 1,2 ГБ памяти, 64 до 1,8, 96 до 2,5, дальше 256: 4K-ремуксу нужен запас на 20-30 секунд. */
    fun autoCacheMb(ctx: Context): Int {
        val mb = totalRamMb(ctx)
        return when {
            mb < 1229 -> 48
            mb < 1843 -> 64
            mb < 2560 -> 96
            else -> 256
        }
    }

    /** Одновременных соединений с пирами. У TorrServer по умолчанию 25: на раздаче в сотни пиров качали 16. */
    const val CONNECTIONS = 100

    fun totalRamMb(ctx: Context): Long {
        val info = ActivityManager.MemoryInfo()
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        return info.totalMem / (1024 * 1024)
    }

    /**
     * Движок для других приложений (Лампа): постоянный порт 8090 на 127.0.0.1, работает всё время,
     * а не только пока «Кино» играет торрент, и запускается при включении устройства.
     */
    fun shared(ctx: Context) = TorrEngine.isAvailable(ctx) && prefs(ctx).getBoolean("shared", false)

    fun setShared(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("shared", on).apply()
}

/**
 * Встроенный движок: TorrServer (GPL-3.0, https://github.com/YouROK/TorrServer), собранный отдельной
 * программой и уложенный в APK как lib/<abi>/libtorrserver.so. Android распаковывает его в
 * nativeLibraryDir, откуда его можно запустить. «Кино» держит его дочерним процессом и говорит с ним
 * только по HTTP на 127.0.0.1, поэтому из сети он не виден. Живым его держит [TorrService].
 *
 * Порт обычно случайный и меняется от запуска к запуску. Поэтому ссылки на видео встроенного движка
 * помечены параметром [MARK], и плеер перед открытием подставляет текущий порт ([rebase]).
 */
object TorrEngine {
    const val MARK = "kino"
    const val SHARED_PORT = 8090
    private const val EXE = "libtorrserver.so"
    private const val START_TIMEOUT_MS = 20_000L

    @Volatile private var process: Process? = null
    @Volatile var port = 0
        private set
    /** Почему не запустился в последний раз (для настроек); null, если запустился. */
    @Volatile var lastError: String? = null
        private set

    val isRunning get() = process?.isAlive == true

    fun isAvailable(ctx: Context) = exe(ctx).isFile

    private fun exe(ctx: Context) = File(ctx.applicationInfo.nativeLibraryDir, EXE)

    fun base() = "http://127.0.0.1:$port"

    /** Журнал движка за последний запуск. Его видно через adb: logcat на прошивке закрыт. */
    fun logFile(ctx: Context) = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "torrserver.log")

    /** Ссылка на видео встроенного движка (с меткой [MARK]). */
    fun isOwn(uri: Uri) = uri.getQueryParameter(MARK) != null && TorrServe.isTorrServe(uri)

    /** Та же ссылка, но на нынешний порт движка. */
    fun rebase(uri: Uri): Uri = uri.buildUpon().encodedAuthority("127.0.0.1:$port").build()

    /**
     * Запустить, если ещё не запущен, и дождаться ответа. Блокирует до 20 секунд: не из главного потока.
     * Возвращает адрес движка, при неудаче бросает IOException с понятным текстом.
     */
    @Synchronized
    fun start(ctx: Context): String = try {
        launch(ctx).also { lastError = null }
    } catch (e: IOException) {
        lastError = e.message
        throw e
    }

    private fun launch(ctx: Context): String {
        val app = ctx.applicationContext
        val wanted = if (TorrSettings.shared(app)) SHARED_PORT else 0
        if (isRunning && (wanted == 0 || port == wanted)) return base()
        stopLocked()
        if (!isAvailable(app)) throw IOException("встроенного движка нет в этой сборке «Кино»")
        killStale()
        val dir = File(app.filesDir, "torrserver").apply { mkdirs() }
        val p = if (wanted != 0) wanted else freePort()
        // на занятом порту /echo ответил бы чужой сервер, и мы приняли бы его за свой
        if (wanted != 0 && !isFree(p)) throw IOException("порт $p занят: наверное, работает отдельный TorrServe")
        val log = logFile(app)
        val pb = ProcessBuilder(exe(app).path, "-p", "$p", "--ip", "127.0.0.1", "-d", dir.path)
            .directory(dir)
            .redirectErrorStream(true)
            .redirectOutput(log)
        pb.environment().apply {
            put("GOGC", "50")                    // сборщик мусора чаще: памяти на проекторах мало
            put("GODEBUG", "madvdontneed=1")     // освобождённая память сразу уходит системе
            put("TMPDIR", app.cacheDir.path)
            put("HOME", dir.path)
        }
        val proc = pb.start()
        process = proc
        port = p
        val deadline = System.currentTimeMillis() + START_TIMEOUT_MS
        while (!(echo() && proc.isAlive)) {
            if (!proc.isAlive) {
                process = null
                throw IOException(failure(log, p))
            }
            if (System.currentTimeMillis() > deadline) {
                stopLocked()
                throw IOException("движок не ответил за ${START_TIMEOUT_MS / 1000} секунд")
            }
            Thread.sleep(150)
        }
        runCatching { applySettings(app) }
        if (wanted == 0) runCatching { prune(app) }
        return base()
    }

    @Synchronized
    fun stop() = stopLocked()

    private fun stopLocked() {
        val proc = process ?: return
        process = null
        // /shutdown закрывает базу аккуратно; если не ответил, SIGTERM, потом SIGKILL
        runCatching { TorrApi.get(base(), "/shutdown", 2000) }
        if (!proc.waitFor(3, TimeUnit.SECONDS)) {
            proc.destroy()
            if (!proc.waitFor(2, TimeUnit.SECONDS)) proc.destroyForcibly()
        }
    }

    private fun echo() = runCatching { TorrApi.get(base(), "/echo", 1000).isNotBlank() }.getOrDefault(false)

    /** Движок, оставшийся от упавшего «Кино», держит базу и порт: убрать. Видны только свои процессы. */
    private fun killStale() {
        val uid = android.os.Process.myUid()
        var killed = false
        File("/proc").listFiles()?.forEach { d ->
            val pid = d.name.toIntOrNull() ?: return@forEach
            val cmd = runCatching { File(d, "cmdline").readText() }.getOrNull() ?: return@forEach
            if (cmd.substringBefore('\u0000').endsWith("/$EXE") && runCatching { Os.stat(d.path).st_uid }.getOrNull() == uid) {
                android.os.Process.killProcess(pid)
                killed = true
            }
        }
        if (killed) Thread.sleep(500)   // пусть система освободит порт и базу
    }

    private fun freePort() = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    private fun isFree(port: Int) =
        runCatching { ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")).close() }.isSuccess

    private fun failure(log: File, port: Int): String {
        val tail = runCatching { log.readLines().takeLast(20) }.getOrDefault(emptyList())
        return when {
            tail.any { "cannot bind HTTP port" in it || "already in use" in it } ->
                "порт $port занят" + (if (port == SHARED_PORT) ": наверное, работает отдельный TorrServe" else "")
            else -> "движок остановился сразу после запуска" +
                (tail.lastOrNull { it.isNotBlank() }?.let { ": " + it.takeLast(120) } ?: "")
        }
    }

    /**
     * Свои настройки: кэш по памяти устройства, 100 соединений, без DLNA и объявлений в локальной сети (Bonjour).
     * Смена настроек перезапускает торрент-клиент внутри движка, поэтому только если что-то отличается.
     */
    fun applySettings(ctx: Context) {
        val sets = JSONObject(TorrApi.post(base(), "/settings", JSONObject().put("action", "get")))
        val cache = TorrSettings.effectiveCacheMb(ctx) * 1024L * 1024L
        var changed = false
        if (sets.optLong("CacheSize") != cache) { sets.put("CacheSize", cache); changed = true }
        if (sets.optInt("ConnectionsLimit") < TorrSettings.CONNECTIONS) {
            sets.put("ConnectionsLimit", TorrSettings.CONNECTIONS); changed = true
        }
        for (k in listOf("EnableBonjour", "EnableDLNA")) {
            if (sets.optBoolean(k)) { sets.put(k, false); changed = true }
        }
        if (changed) TorrApi.post(base(), "/settings", JSONObject().put("action", "set").put("sets", sets))
    }

    /**
     * Торренты в базе движка нужны, только чтобы «Продолжить просмотр» открывался сразу, без поиска
     * данных торрента заново. Остальные убираем. В режиме для Лампы базу не трогаем: это её список.
     */
    private fun prune(ctx: Context) {
        val keep = History.all(ctx).mapNotNull { e ->
            e.key.takeIf { it.startsWith("ts:") }?.removePrefix("ts:")?.substringBefore(':')
        }.toSet()
        val list = JSONArray(TorrApi.post(base(), "/torrents", JSONObject().put("action", "list")))
        for (i in 0 until list.length()) {
            val hash = list.getJSONObject(i).optString("hash").lowercase()
            if (hash.isNotEmpty() && hash !in keep) {
                TorrApi.post(base(), "/torrents", JSONObject().put("action", "rem").put("hash", hash))
            }
        }
    }
}

/** JSON API TorrServer (и встроенного, и внешнего). Всё блокирующее: не из главного потока. */
object TorrApi {
    class FileInfo(val id: Int, val path: String, val length: Long) {
        val name get() = path.substringAfterLast('/')
    }

    private fun open(url: String, timeout: Int) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = timeout.coerceAtMost(5000)
        readTimeout = timeout
    }

    private fun read(c: HttpURLConnection): String {
        try {
            val code = c.responseCode
            if (code !in 200..299) {
                val err = runCatching { c.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
                val msg = err?.let { runCatching { JSONObject(it).optString("error") }.getOrNull() }
                throw IOException(msg?.takeIf { it.isNotBlank() } ?: "сервер ответил $code")
            }
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    fun get(base: String, path: String, timeout: Int = 5000): String = read(open(base + path, timeout))

    fun post(base: String, path: String, body: JSONObject, timeout: Int = 10_000): String {
        val c = open(base + path, timeout)
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        return read(c)
    }

    fun alive(base: String) = runCatching { get(base, "/echo", 2000).isNotBlank() }.getOrDefault(false)

    /** Добавить magnet, хеш или ссылку на .torrent (её TorrServer скачает сам). Возвращает статус. */
    fun add(base: String, link: String): JSONObject = JSONObject(post(base, "/torrents",
        JSONObject().put("action", "add").put("link", link).put("save_to_db", true), 30_000))

    /** Загрузить содержимое .torrent-файла. Возвращает статус. */
    fun upload(base: String, torrent: ByteArray, name: String): JSONObject {
        val boundary = "kino" + System.nanoTime()
        val c = open("$base/torrent/upload", 30_000)
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        c.outputStream.use { out ->
            fun line(s: String) = out.write((s + "\r\n").toByteArray())
            line("--$boundary")
            line("Content-Disposition: form-data; name=\"save\"")
            line("")
            line("true")
            line("--$boundary")
            line("Content-Disposition: form-data; name=\"file\"; filename=\"${name.replace("\"", "")}\"")
            line("Content-Type: application/x-bittorrent")
            line("")
            out.write(torrent)
            line("")
            line("--$boundary--")
        }
        val text = read(c)
        return if (text.trimStart().startsWith("[")) JSONArray(text).getJSONObject(0) else JSONObject(text)
    }

    fun status(base: String, hash: String): JSONObject =
        JSONObject(post(base, "/torrents", JSONObject().put("action", "get").put("hash", hash)))

    fun files(status: JSONObject): List<FileInfo> {
        val arr = status.optJSONArray("file_stats") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .map { FileInfo(it.optInt("id"), it.optString("path"), it.optLong("length")) }
    }

    /** Ссылка на файл торрента для плеера. own: встроенный движок, ставим метку [TorrEngine.MARK]. */
    fun streamUri(base: String, hash: String, f: FileInfo, own: Boolean): Uri {
        val b = Uri.parse(base).buildUpon().appendPath("stream").appendPath(f.name)
            .appendQueryParameter("link", hash)
            .appendQueryParameter("index", f.id.toString())
            .appendQueryParameter("play", "")
        if (own) b.appendQueryParameter(TorrEngine.MARK, "1")
        return b.build()
    }
}

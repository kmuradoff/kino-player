package com.xgimiru.player

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.system.Os
import com.xgimiru.player.ui.tr
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
 * Built-in TorrServer settings. Lumen Play plays torrent links that other apps (Lampa) hand over, the engine
 * itself serves them on 127.0.0.1:8090 while the "for other apps" mode is on.
 */
object TorrSettings {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("torrents", Context.MODE_PRIVATE)

    /** Engine cache in MB, 0 = chosen from the device memory. */
    fun cacheMb(ctx: Context) = prefs(ctx).getInt("cacheMb", 0)

    fun setCacheMb(ctx: Context, mb: Int) = prefs(ctx).edit().putInt("cacheMb", mb).apply()

    fun effectiveCacheMb(ctx: Context) = cacheMb(ctx).takeIf { it > 0 } ?: autoCacheMb(totalRamMb(ctx))

    /** 48 MB up to 1.2 GB of memory, 64 up to 1.8, 96 up to 2.5, then 256: a 4K remux needs 20-30 seconds ahead. */
    fun autoCacheMb(ramMb: Long): Int = when {
        ramMb < 1229 -> 48
        ramMb < 1843 -> 64
        ramMb < 2560 -> 96
        else -> 256
    }

    /** Peer connections at once. TorrServer's default is 25: a swarm of hundreds gave 16. */
    const val CONNECTIONS = 100

    /** Cache sizes offered in the settings, 0 = automatic. */
    val CACHE_CHOICES = listOf(0, 48, 64, 96, 128, 256, 512)

    fun totalRamMb(ctx: Context): Long {
        val info = ActivityManager.MemoryInfo()
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        return info.totalMem / (1024 * 1024)
    }

    /**
     * Engine for other apps (Lampa): fixed port 8090 on 127.0.0.1, runs all the time, not only while Lumen Play
     * plays a torrent, and starts when the device boots.
     */
    fun shared(ctx: Context) = TorrEngine.isAvailable(ctx) && prefs(ctx).getBoolean("shared", false)

    fun setShared(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("shared", on).apply()
}

/**
 * One engine process: start, wait for its answer, stop. No Android in here, so the host tests run it with a
 * stand-in program.
 */
class EngineProcess(
    private val probe: (port: Int) -> Boolean,
    private val timeoutMs: Long = 20_000L,
    private val pollMs: Long = 150L,
) {
    @Volatile private var process: Process? = null
    @Volatile var port = 0
        private set

    val isRunning get() = process?.isAlive == true

    /** Starts [cmd] listening on [port] and waits until [probe] answers. Throws IOException with the reason. */
    fun start(cmd: List<String>, port: Int, dir: File, log: File, env: Map<String, String>, sharedPort: Int) {
        val pb = ProcessBuilder(cmd).directory(dir).redirectErrorStream(true).redirectOutput(log)
        pb.environment().putAll(env)
        val proc = pb.start()
        process = proc
        this.port = port
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!(proc.isAlive && probe(port))) {
            if (!proc.isAlive) {
                process = null
                throw IOException(failure(runCatching { log.readLines().takeLast(20) }.getOrDefault(emptyList()), port, sharedPort))
            }
            if (System.currentTimeMillis() > deadline) {
                stop {}
                throw IOException(tr("движок не ответил за ${timeoutMs / 1000} с", "the engine did not answer in ${timeoutMs / 1000} s"))
            }
            Thread.sleep(pollMs)
        }
    }

    /** [shutdown] asks the engine to close its database, then SIGTERM, then SIGKILL. */
    fun stop(shutdown: () -> Unit) {
        val proc = process ?: return
        process = null
        runCatching { shutdown() }
        if (!proc.waitFor(3, TimeUnit.SECONDS)) {
            proc.destroy()
            if (!proc.waitFor(2, TimeUnit.SECONDS)) proc.destroyForcibly()
        }
    }

    companion object {
        /** Plain reason from the last lines of the engine log. */
        fun failure(tail: List<String>, port: Int, sharedPort: Int): String = when {
            tail.any { "cannot bind HTTP port" in it || "already in use" in it } ->
                tr("порт $port занят", "port $port is busy") +
                    (if (port == sharedPort) tr(": наверное, работает отдельный TorrServe", ": another TorrServe probably runs") else "")
            else -> tr("движок остановился сразу после запуска", "the engine stopped right after the start") +
                (tail.lastOrNull { it.isNotBlank() }?.let { ": " + it.takeLast(120) } ?: "")
        }
    }
}

/**
 * Built-in engine: TorrServer (GPL-3.0, https://github.com/YouROK/TorrServer), built as its own program and
 * packed into the APK as lib/<abi>/libtorrserver.so. Android unpacks it into nativeLibraryDir, from where it can
 * run. Lumen Play keeps it as a child process and talks to it over HTTP on 127.0.0.1 only, so the network does
 * not see it. [TorrService] keeps it alive.
 *
 * Old links of the built-in engine (Lumen Play 1.3 history) carry the parameter [MARK] and a port of an old
 * start: the player puts the current port in before opening them ([rebase]).
 */
object TorrEngine {
    const val MARK = "kino"
    const val SHARED_PORT = 8090
    private const val EXE = "libtorrserver.so"

    private val engine = EngineProcess({ p -> echo(p) })

    val port get() = engine.port
    val isRunning get() = engine.isRunning

    /** Why the last start failed (for the settings), null when it started. */
    @Volatile var lastError: String? = null
        private set

    fun isAvailable(ctx: Context) = exe(ctx).isFile

    private fun exe(ctx: Context) = File(ctx.applicationInfo.nativeLibraryDir, EXE)

    fun base() = "http://127.0.0.1:$port"

    /** Engine log of the last start, readable over adb (logcat is closed on the firmware). */
    fun logFile(ctx: Context) = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "torrserver.log")

    /** A video link of the built-in engine (with [MARK]). */
    fun isOwn(uri: Uri) = uri.getQueryParameter(MARK) != null && TorrServe.isTorrServe(uri)

    /** The same link on the current engine port. */
    fun rebase(uri: Uri): Uri = uri.buildUpon().encodedAuthority("127.0.0.1:$port").build()

    /**
     * Starts the engine unless it runs and waits for its answer. Blocks up to 20 seconds: never on the main
     * thread. Returns the engine address, throws IOException with a plain reason.
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
        if (!isAvailable(app)) throw IOException(tr("встроенного движка нет в этой сборке", "this build has no built-in engine"))
        killStale()
        val dir = File(app.filesDir, "torrserver").apply { mkdirs() }
        val p = if (wanted != 0) wanted else freePort()
        // on a busy port /echo would answer from another server and we would take it for ours
        if (wanted != 0 && !isFree(p)) throw IOException(EngineProcess.failure(listOf("already in use"), p, SHARED_PORT))
        engine.start(
            listOf(exe(app).path, "-p", "$p", "--ip", "127.0.0.1", "-d", dir.path), p, dir, logFile(app),
            mapOf(
                "GOGC" to "50",                     // collect garbage more often: projectors have little memory
                "GODEBUG" to "madvdontneed=1",      // freed memory goes back to the system at once
                "TMPDIR" to app.cacheDir.path,
                "HOME" to dir.path,
            ),
            SHARED_PORT,
        )
        runCatching { applySettings(app) }
        if (wanted == 0) runCatching { prune(app) }
        return base()
    }

    @Synchronized
    fun stop() = stopLocked()

    private fun stopLocked() = engine.stop { TorrApi.get(base(), "/shutdown", 2000) }

    private fun echo(port: Int) = runCatching { TorrApi.get("http://127.0.0.1:$port", "/echo", 1000).isNotBlank() }.getOrDefault(false)

    /** An engine left by a crashed Lumen Play holds the database and the port: end it. Only own processes are visible. */
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
        if (killed) Thread.sleep(500)   // let the system free the port and the database
    }

    private fun freePort() = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    private fun isFree(port: Int) =
        runCatching { ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")).close() }.isSuccess

    /**
     * Own settings: cache by device memory, 100 connections, no DLNA and no announcements on the local network
     * (Bonjour). Changing settings restarts the torrent client inside the engine, so only when something differs.
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
     * Torrents in the engine database are only needed so Continue watching opens at once. The rest goes. In the
     * mode for other apps the database is left alone: it is Lampa's list.
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

/** TorrServer JSON API. Everything blocks: never on the main thread. */
object TorrApi {
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
                throw IOException(msg?.takeIf { it.isNotBlank() } ?: tr("сервер ответил $code", "the server answered $code"))
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
}

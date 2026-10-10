package com.xgimiru.player

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Обновление «Кино» по воздуху: раз в 12 часов (или по кнопке) читаем kino-update.json из последнего
 * релиза на GitHub, сравниваем versionCode, по согласию скачиваем APK, сверяем размер и SHA-256 и ставим
 * через PackageInstaller (подтверждение показывает сам Android). Подпись APK проверяет система: поверх
 * встанет только APK с тем же ключом.
 *
 * Формат kino-update.json: {"versionCode": 9, "versionName": "0.8", "apk": "<url>", "size": N,
 * "sha256": "<hex>", "notes": "<что нового>"}.
 */
object Updater {
    private const val MANIFEST_URL = "https://github.com/kmuradoff/kino-player/releases/latest/download/kino-update.json"
    private const val CHECK_EVERY_MS = 12 * 60 * 60 * 1000L
    private const val ACTION_DONE = "com.xgimiru.player.UPDATE_DONE"

    class Info(val versionCode: Long, val versionName: String, val apk: String, val size: Long,
               val sha256: String, val notes: String)

    private val main = Handler(Looper.getMainLooper())

    /** Адрес манифеста. Для проверки на столе: http://127.0.0.1:… (через adb reverse), и только он. */
    private fun manifestUrl(ctx: Context): String {
        val o = prefs(ctx).getString("update_url", null)
        return if (o != null && o.startsWith("http://127.0.0.1:")) o else MANIFEST_URL
    }

    fun setTestUrl(ctx: Context, url: String?) {
        if (url == null || url.startsWith("http://127.0.0.1:")) prefs(ctx).edit().putString("update_url", url).apply()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("update", Context.MODE_PRIVATE)

    private fun installedCode(ctx: Context): Long {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
    }

    /** Проверка в фоне. force = по кнопке, без паузы 12 часов. В done: новая версия или null. */
    fun check(ctx: Context, force: Boolean, done: (Info?) -> Unit) {
        val app = ctx.applicationContext
        val p = prefs(app)
        if (!force && System.currentTimeMillis() - p.getLong("last_check", 0) < CHECK_EVERY_MS) {
            // между проверками помним найденную версию, чтобы плашка не пропадала
            main.post { done(cached(app)) }
            return
        }
        Thread {
            val info = runCatching { fetch(app) }.getOrNull()
            p.edit().putLong("last_check", System.currentTimeMillis())
                .putString("found", info?.let { raw(it) }).apply()
            main.post { done(info) }
        }.start()
    }

    private fun cached(ctx: Context): Info? = runCatching {
        prefs(ctx).getString("found", null)?.let { parse(JSONObject(it)) }?.takeIf { it.versionCode > installedCode(ctx) }
    }.getOrNull()

    private fun raw(i: Info) = JSONObject().put("versionCode", i.versionCode).put("versionName", i.versionName)
        .put("apk", i.apk).put("size", i.size).put("sha256", i.sha256).put("notes", i.notes).toString()

    private fun parse(j: JSONObject) = Info(j.getLong("versionCode"), j.getString("versionName"), j.getString("apk"),
        j.getLong("size"), j.getString("sha256").lowercase(), j.optString("notes"))

    private fun fetch(ctx: Context): Info? {
        val c = open(manifestUrl(ctx))
        try {
            if (c.responseCode != 200) return null
            val info = parse(JSONObject(c.inputStream.bufferedReader().readText()))
            val safeUrl = info.apk.startsWith("https://") || info.apk.startsWith("http://127.0.0.1:")
            return if (safeUrl && info.versionCode > installedCode(ctx)) info else null
        } finally {
            c.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 20_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "Kino-updater")
    }

    /** Скачать, проверить и отдать установщику. progress: 0..100; fail: текст ошибки по-русски. */
    fun install(ctx: Context, info: Info, progress: (Int) -> Unit, fail: (String) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            val f = File(app.cacheDir, "update.apk")
            try {
                download(info, f) { pct -> main.post { progress(pct) } }
                if (f.length() != info.size) throw IllegalStateException("файл неполный (${f.length()} из ${info.size} байт)")
                if (sha256(f) != info.sha256) throw IllegalStateException("контрольная сумма не совпала")
                commit(app, f)
            } catch (t: Throwable) {
                f.delete()
                main.post { fail(t.message ?: t.toString()) }
            }
        }.start()
    }

    private fun download(info: Info, to: File, progress: (Int) -> Unit) {
        var url = info.apk
        var c = open(url)
        // GitHub отдаёт файл через переадресацию на другой хост: идём по ней сами
        var hops = 0
        while (c.responseCode in 301..308 && hops++ < 5) {
            url = c.getHeaderField("Location") ?: break
            c.disconnect()
            c = open(url)
        }
        try {
            if (c.responseCode != 200) throw IllegalStateException("сервер ответил ${c.responseCode}")
            c.inputStream.use { inp ->
                to.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val pct = if (info.size > 0) (done * 100 / info.size).toInt().coerceIn(0, 100) else 0
                        if (pct != last) { last = pct; progress(pct) }
                    }
                }
            }
        } finally {
            c.disconnect()
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { inp ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = inp.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun commit(ctx: Context, apk: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("kino.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; s.fsync(out) }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val intent = Intent(ctx, Done::class.java).setAction(ACTION_DONE)
            s.commit(PendingIntent.getBroadcast(ctx, id, intent, flags).intentSender)
        }
        apk.delete()
    }

    /** Ответ установщика: попросить подтверждение у пользователя или сказать, что вышло. */
    class Done : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { runCatching { ctx.startActivity(it) } }
                }
                PackageInstaller.STATUS_SUCCESS -> Unit   // процесс перезапустится уже новой версией
                else -> Toast.makeText(ctx, "Обновление не установилось: " +
                    (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "ошибка"), Toast.LENGTH_LONG).show()
            }
        }
    }
}

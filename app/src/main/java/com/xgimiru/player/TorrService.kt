package com.xgimiru.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.xgimiru.player.ui.tr

/**
 * Keeps the built-in engine ([TorrEngine]) alive while it is needed: a foreground service (mediaPlayback), else
 * the firmware may end Lumen Play together with the engine in the middle of a film. A user of the engine takes
 * it with [hold] and gives it back with [release]. When nobody holds it, the engine stops after 90 seconds. In
 * the mode for other apps ([TorrSettings.shared]) the engine runs all the time.
 */
class TorrService : Service() {

    companion object {
        const val SHARED = "shared"
        private const val CHANNEL = "torrents"
        private const val IDLE_MS = 90_000L
        private const val WATCH_MS = 30_000L

        private val main = Handler(Looper.getMainLooper())
        private val holders = mutableSetOf<String>()
        private var instance: TorrService? = null
        private var app: Context? = null

        /** Main thread only, while the app is on screen. who: the holder ("player", ...). */
        fun hold(ctx: Context, who: String) {
            app = ctx.applicationContext
            holders += who
            main.removeCallbacks(idleStop)
            if (instance == null) ctx.startForegroundService(Intent(ctx, TorrService::class.java))
            if (who == SHARED) {
                main.removeCallbacks(watch)
                watch.run()
            }
        }

        fun release(ctx: Context, who: String) {
            holders -= who
            if (who == SHARED) main.removeCallbacks(watch)
            if (holders.isEmpty()) {
                main.removeCallbacks(idleStop)
                main.postDelayed(idleStop, IDLE_MS)
            }
        }

        /** Mode for other apps: the engine may not have come up (busy port) or may have died. Start it again. */
        private val watch: Runnable = object : Runnable {
            override fun run() {
                val ctx = app ?: return
                if (SHARED !in holders) return
                if (!TorrEngine.isRunning) Thread { runCatching { TorrEngine.start(ctx) } }.start()
                main.postDelayed(this, WATCH_MS)
            }
        }

        private val idleStop = Runnable {
            val ctx = app
            if (holders.isEmpty() && (ctx == null || !TorrSettings.shared(ctx))) instance?.shutdown()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        goForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()   // required after every startForegroundService
        // Mode for other apps: when the system killed the process it restarts the service (START_STICKY) and
        // the engine is taken into work again here.
        if (TorrSettings.shared(this) && SHARED !in holders) hold(this, SHARED)
        if (holders.isEmpty()) {
            main.removeCallbacks(idleStop)
            main.postDelayed(idleStop, IDLE_MS)
        }
        return if (TorrSettings.shared(this)) START_STICKY else START_NOT_STICKY
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "TorrServer", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, SettingsActivity::class.java).putExtra("section", "torrserver"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(tr("Lumen Play: TorrServer", "Lumen Play: TorrServer"))
            .setContentText(if (TorrSettings.shared(this)) tr("Работает для Лампы и других приложений", "Runs for Lampa and other apps")
                else tr("Работает, пока смотрите торрент", "Runs while you watch a torrent"))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(1, n)
    }

    private fun shutdown() {
        instance = null
        Thread { TorrEngine.stop() }.start()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
            Thread { TorrEngine.stop() }.start()
        }
        main.removeCallbacks(idleStop)
        main.removeCallbacks(watch)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Mode for other apps: start the engine after the device boots. */
    class Boot : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BOOT_COMPLETED && TorrSettings.shared(ctx)) hold(ctx, SHARED)
        }
    }
}

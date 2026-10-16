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

/**
 * Держит встроенный движок ([TorrEngine]) живым, пока он нужен: служба переднего плана (mediaPlayback),
 * иначе прошивка может убить «Кино» вместе с движком посреди фильма. Кто пользуется движком, берёт его
 * через [hold] и отпускает через [release]; когда не держит никто, через 90 секунд движок
 * останавливается. В режиме для Лампы ([TorrSettings.shared]) движок работает всё время.
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

        /** Вызывать из главного потока, пока приложение на экране. who: кто держит («player», …). */
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

        /** Режим для Лампы: движок мог не подняться (порт был занят) или упасть. Поднимаем снова. */
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
        goForeground()   // обязательно после каждого startForegroundService
        // Режим для Лампы: если система убила процесс «Кино», она перезапустит службу (START_STICKY),
        // а здесь движок снова берётся в работу. Раньше он оставался выключенным до перезагрузки.
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
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Торренты", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, SettingsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Кино: торрент-движок")
            .setContentText(if (TorrSettings.shared(this)) "Работает для Лампы и других приложений" else "Работает, пока смотрите торрент")
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

    /** Режим для Лампы: поднять движок после включения устройства. */
    class Boot : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BOOT_COMPLETED && TorrSettings.shared(ctx)) hold(ctx, SHARED)
        }
    }
}

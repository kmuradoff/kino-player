package com.xgimiru.player.dlna

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import com.xgimiru.player.SettingsActivity
import com.xgimiru.player.ui.tr

/**
 * Держит DLNA-рендерер ([Renderer]) включённым: служба переднего плана, запускается при включении
 * устройства и при открытии Lumen Play, если настройка включена ([DlnaSettings]). Пока работает,
 * держит MulticastLock (иначе Wi-Fi отбрасывает поиск SSDP) и следит за сменой сети.
 */
class DlnaService : Service() {

    companion object {
        private const val CHANNEL = "dlna"
        private const val ID = 2

        /** Включить или выключить службу по настройке. Можно звать из главного потока. */
        fun sync(ctx: Context) {
            val app = ctx.applicationContext
            if (DlnaSettings.enabled(app)) {
                runCatching { app.startForegroundService(Intent(app, DlnaService::class.java)) }
            } else {
                app.stopService(Intent(app, DlnaService::class.java))
            }
        }
    }

    private var renderer: Renderer? = null
    private var lock: WifiManager.MulticastLock? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        goForeground()
        if (!DlnaSettings.enabled(this)) return
        lock = runCatching {
            getSystemService(WifiManager::class.java)?.createMulticastLock("lumen-dlna")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
        val r = Renderer(applicationContext, DlnaSettings.uuid(this), DlnaSettings.friendlyName(this))
        renderer = r
        r.start()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { r.networkChanged() }
            override fun onLost(network: Network) { r.networkChanged() }
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) { r.networkChanged() }
        }
        runCatching {
            val req = NetworkRequest.Builder().removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
            getSystemService(ConnectivityManager::class.java).registerNetworkCallback(req, cb)
            callback = cb
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()   // обязательно после каждого startForegroundService
        if (!DlnaSettings.enabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL,
                tr("Трансляция с телефона", "Casting from a phone"), NotificationManager.IMPORTANCE_MIN))
        }
        val open = PendingIntent.getActivity(this, 1, Intent(this, SettingsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle(tr("Lumen Play виден телефонам и компьютерам как телевизор",
                "Lumen Play is visible to phones and computers as a TV"))
            .setContentText(DlnaSettings.friendlyName(this))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(ID, n)
    }

    override fun onDestroy() {
        callback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        callback = null
        renderer?.stop()   // byebye и закрытие сокетов уходят в его собственный поток
        renderer = null
        runCatching { lock?.release() }
        lock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Поднять рендерер после включения устройства. */
    class Boot : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BOOT_COMPLETED) DlnaService.sync(ctx)
        }
    }
}

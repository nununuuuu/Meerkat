package com.resourcesniffer.app.capture

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import androidx.core.app.NotificationCompat
import com.resourcesniffer.app.MainActivity
import com.resourcesniffer.app.R
import com.resourcesniffer.app.repository.SessionStore

class SnifferVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.resourcesniffer.app.START_CAPTURE"
        const val ACTION_STOP = "com.resourcesniffer.app.STOP_CAPTURE"
        const val EXTRA_ENABLE_HTTPS_MITM = "enable_https_mitm"
        private const val CHANNEL_ID = "sniffer_vpn"
        private const val NOTIFICATION_ID = 1001
    }

    @Volatile private var forwarder: NetstackForwarder? = null
    @Volatile private var localProxy: LocalMitmProxy? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopCapture()
            else -> startCapture(
                intent?.getBooleanExtra(EXTRA_ENABLE_HTTPS_MITM, false) == true,
            )
        }
        return START_STICKY
    }

    @Synchronized
    private fun startCapture(enableHttpsMitm: Boolean) {
        if (forwarder != null) return

        SessionStore.startExternal(targetPackage = null, targetName = "全域 App 嗅探")
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        val builder = Builder()
            .setSession("Meerkat 資源嗅探")
            .setMtu(1500)
            .addAddress("10.73.0.1", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")

        // Global mode: capture all device traffic except Meerkat itself.
        // Proxy/upstream sockets are also protected individually, but excluding
        // our own package avoids routing WebView/download traffic back into TUN.
        runCatching { builder.addDisallowedApplication(packageName) }

        val pfd = builder.establish() ?: run {
            stopCapture()
            return
        }

        val fd = pfd.detachFd()
        val targetName = "全域 App 嗅探"
        val proxyPort = if (enableHttpsMitm) {
            val ca = MitmCertificateAuthority(this)
            val proxy = LocalMitmProxy(this, null, targetName, ca)
            localProxy = proxy
            proxy.start()
        } else null
        val engine = NetstackForwarder(this, null, targetName, proxyPort)
        forwarder = engine

        try {
            engine.start(fd, 1500)
        } catch (_: Throwable) {
            stopCapture()
        }
    }

    @Synchronized
    private fun stopCapture() {
        val engine = forwarder
        forwarder = null
        runCatching { engine?.stop() }
        runCatching { localProxy?.stop() }
        localProxy = null
        SessionStore.stopExternal()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        stopCapture()
        super.onRevoke()
    }

    override fun onDestroy() {
        val engine = forwarder
        forwarder = null
        runCatching { engine?.stop() }
        runCatching { localProxy?.stop() }
        localProxy = null
        super.onDestroy()
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_download_done)
        .setContentTitle("Meerkat 正在全域嗅探")
        .setContentText("你可以離開 Meerkat，自行開啟任何 App")
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.vpn_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }
    }
}

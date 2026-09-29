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

class SnifferVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.resourcesniffer.app.START_CAPTURE"
        const val ACTION_STOP = "com.resourcesniffer.app.STOP_CAPTURE"
        const val EXTRA_TARGET_PACKAGE = "target_package"
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
                intent?.getStringExtra(EXTRA_TARGET_PACKAGE),
                intent?.getBooleanExtra(EXTRA_ENABLE_HTTPS_MITM, false) == true,
            )
        }
        return START_STICKY
    }

    @Synchronized
    private fun startCapture(targetPackage: String?, enableHttpsMitm: Boolean) {
        if (forwarder != null) return

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(targetPackage))

        val builder = Builder()
            .setSession("Meerkat 資源嗅探")
            .setMtu(1500)
            .addAddress("10.73.0.1", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")

        if (!targetPackage.isNullOrBlank()) {
            val allowed = runCatching {
                builder.addAllowedApplication(targetPackage)
                true
            }.getOrDefault(false)
            if (!allowed) {
                stopCapture()
                return
            }
        } else {
            // Avoid accidentally routing the whole device while this feature is
            // intended for a user-selected target application.
            stopCapture()
            return
        }

        val pfd = builder.establish() ?: run {
            stopCapture()
            return
        }

        val fd = pfd.detachFd()
        val targetName = runCatching {
            val info = packageManager.getApplicationInfo(targetPackage, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrNull()
        val proxyPort = if (enableHttpsMitm) {
            val ca = MitmCertificateAuthority(this)
            val proxy = LocalMitmProxy(this, targetPackage, targetName, ca)
            localProxy = proxy
            proxy.start()
        } else null
        val engine = NetstackForwarder(this, targetPackage, targetName, proxyPort)
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

    private fun buildNotification(targetPackage: String?) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_download_done)
        .setContentTitle("Meerkat 正在嗅探資源")
        .setContentText(
            targetPackage?.let { pkg ->
                runCatching {
                    val info = packageManager.getApplicationInfo(pkg, 0)
                    packageManager.getApplicationLabel(info).toString()
                }.getOrDefault(pkg)
            } ?: "已啟用"
        )
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

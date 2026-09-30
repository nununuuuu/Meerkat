package com.resourcesniffer.app.capture

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.net.ConnectivityManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.resourcesniffer.app.MainActivity
import com.resourcesniffer.app.R
import com.resourcesniffer.app.repository.SessionStore

class SnifferVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.resourcesniffer.app.START_CAPTURE"
        const val ACTION_UPDATE_QUIC = "com.resourcesniffer.app.UPDATE_QUIC"
        const val ACTION_STOP = "com.resourcesniffer.app.STOP_CAPTURE"
        const val EXTRA_BLOCK_QUIC = "block_quic"
        const val EXTRA_ENABLE_HTTPS_MITM = "enable_https_mitm"
        private const val CHANNEL_ID = "sniffer_vpn"
        private const val NOTIFICATION_ID = 1001
    }

    @Volatile private var forwarder: NetstackForwarder? = null
    @Volatile private var localProxy: LocalMitmProxy? = null
    private var tun: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopCapture()
            ACTION_UPDATE_QUIC -> {
                val block = intent?.getBooleanExtra(EXTRA_BLOCK_QUIC, false) == true
                forwarder?.setBlockQuic(block)
                CaptureStatus.quicMode(block)
            }
            else -> startCapture(
                intent?.getBooleanExtra(EXTRA_ENABLE_HTTPS_MITM, false) == true,
                intent?.getBooleanExtra(EXTRA_BLOCK_QUIC, false) == true,
            )
        }
        return START_NOT_STICKY
    }

    @Synchronized
    private fun startCapture(enableHttpsMitm: Boolean, blockQuic: Boolean) {
        if (forwarder != null) return

        CaptureStatus.starting()
        try {
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, buildNotification())

            val builder = Builder()
                .setSession("Meerkat 資源嗅探")
                .setMtu(1500)
                .addAddress("10.73.0.1", 32)
                .addRoute("0.0.0.0", 0)
                .addAddress("fd73:6d65:6572::1", 128)
                .addRoute("::", 0)

            val connectivity = getSystemService(ConnectivityManager::class.java)
            val dnsServers = connectivity.activeNetwork
                ?.let(connectivity::getLinkProperties)
                ?.dnsServers
                .orEmpty()
                .filter { !it.isLoopbackAddress && !it.isAnyLocalAddress }
                .distinct()

            if (dnsServers.isEmpty()) {
                builder.addDnsServer("1.1.1.1")
                builder.addDnsServer("8.8.8.8")
            } else {
                dnsServers.forEach(builder::addDnsServer)
            }

            // Global mode: capture all device traffic except Meerkat itself.
            // Proxy/upstream sockets are also protected individually, but excluding
            // our own package avoids routing WebView/download traffic back into TUN.
            runCatching { builder.addDisallowedApplication(packageName) }

            val pfd = builder.establish() ?: error("系統未建立 VPN，請重新授權")
            tun = pfd
            val fd = pfd.fd
            val targetName = "全域 App 嗅探"
            val proxyPort = if (enableHttpsMitm) {
                val ca = MitmCertificateAuthority(this)
                val proxy = LocalMitmProxy(this, null, targetName, ca)
                localProxy = proxy
                proxy.start()
            } else null
            val engine = NetstackForwarder(this, null, targetName, proxyPort, blockQuic)
            forwarder = engine

            SessionStore.startExternal(targetPackage = null, targetName = targetName)
            engine.start(fd, 1500)
            CaptureStatus.started(enableHttpsMitm)
            CaptureStatus.quicMode(blockQuic)
        } catch (error: Throwable) {
            Log.e("MeerkatVPN", "Unable to start capture", error)
            stopCapture(error.message ?: error.javaClass.simpleName)
        }
    }

    @Synchronized
    private fun stopCapture(error: String? = null) {
        val engine = forwarder
        forwarder = null
        runCatching { engine?.stop() }
        runCatching { localProxy?.stop() }
        localProxy = null
        runCatching { tun?.close() }
        tun = null
        SessionStore.stopExternal()
        CaptureStatus.stopped(error)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        stopCapture()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopCapture(CaptureStatus.state.value.error)
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


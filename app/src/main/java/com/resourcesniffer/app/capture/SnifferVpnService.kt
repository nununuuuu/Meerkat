package com.resourcesniffer.app.capture

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.resourcesniffer.app.MainActivity
import com.resourcesniffer.app.R
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicBoolean

class SnifferVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.resourcesniffer.app.START_CAPTURE"
        const val ACTION_STOP = "com.resourcesniffer.app.STOP_CAPTURE"
        const val EXTRA_TARGET_PACKAGE = "target_package"
        private const val CHANNEL_ID = "sniffer_vpn"
        private const val NOTIFICATION_ID = 1001
    }

    private var tun: ParcelFileDescriptor? = null
    private var readerThread: Thread? = null
    private val running = AtomicBoolean(false)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopCapture()
            else -> startCapture(intent?.getStringExtra(EXTRA_TARGET_PACKAGE))
        }
        return START_STICKY
    }

    private fun startCapture(targetPackage: String?) {
        if (running.getAndSet(true)) return
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(targetPackage))

        val builder = Builder()
            .setSession("Resource Sniffer")
            .setMtu(1500)
            .addAddress("10.73.0.1", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")

        if (!targetPackage.isNullOrBlank()) {
            runCatching { builder.addAllowedApplication(targetPackage) }
        }

        tun = builder.establish()
        val descriptor = tun ?: run {
            stopCapture()
            return
        }

        readerThread = Thread {
            val input = FileInputStream(descriptor.fileDescriptor)
            val parser = PacketMetadataParser(ConnectionOwnerResolver(this))
            val buffer = ByteArray(32767)
            try {
                while (running.get()) {
                    val length = input.read(buffer)
                    if (length > 0) parser.inspect(buffer, length)
                }
            } catch (_: Exception) {
            }
        }.apply {
            name = "ResourceSniffer-TunReader"
            start()
        }
    }

    private fun stopCapture() {
        running.set(false)
        runCatching { tun?.close() }
        tun = null
        readerThread = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    private fun buildNotification(targetPackage: String?) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_download_done)
        .setContentTitle("Resource Sniffer")
        .setContentText(targetPackage?.let { "Capture core active: $it" } ?: "Capture core active")
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
                NotificationChannel(CHANNEL_ID, getString(R.string.vpn_channel_name), NotificationManager.IMPORTANCE_LOW)
            )
        }
    }
}

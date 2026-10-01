package com.resourcesniffer.app.capture

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.PowerManager
import android.os.Process
import java.net.InetSocketAddress

/** A lease is invalidated permanently when the foreground changes. */
internal data class CaptureLease(val packageName: String, val generation: Long) {
    fun active() = ForegroundCapture.isActive(this)
}

internal object ForegroundCapture {
    private var context: Context? = null
    private var lastQuery = 0L
    private var lastRefresh = 0L
    private val selection = ForegroundSelection()
    private val current get() = selection.packageName
    private val generation get() = selection.generation

    fun hasPermission(context: Context): Boolean = context.getSystemService(AppOpsManager::class.java)
        .unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED

    @Synchronized fun start(context: Context) {
        this.context = context.applicationContext
        lastQuery = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        selection.reset()
        lastRefresh = 0L
        refresh()
    }

    @Synchronized fun stop() { context = null; selection.reset() }

    @Synchronized fun refresh() {
        val ctx = context ?: return
        val now = System.currentTimeMillis()
        if (now - lastRefresh in 0..249) return
        lastRefresh = now
        if (!hasPermission(ctx) || !ctx.getSystemService(PowerManager::class.java).isInteractive || ctx.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked) {
            change(null)
            CaptureStatus.foreground("未授權或螢幕已鎖定，暫停收錄")
            lastQuery = now
            return
        }
        runCatching {
            val events = ctx.getSystemService(UsageStatsManager::class.java).queryEvents(lastQuery, now)
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> change(event.packageName)
                    UsageEvents.Event.ACTIVITY_PAUSED ->
                        if (current == event.packageName) change(null)
                    UsageEvents.Event.SCREEN_NON_INTERACTIVE -> change(null)
                }
            }
            lastQuery = now
        }.onFailure { change(null) }
        CaptureStatus.foreground(current?.let { name(it) } ?: "等待前景 App（請開啟或重新載入目標 App）")
    }

    private fun change(next: String?) {
        selection.change(next)
    }

    @Synchronized fun isActive(lease: CaptureLease): Boolean {
        refresh()
        return context != null && selection.accepts(lease.packageName, lease.generation)
    }

    @Synchronized fun owner(protocol: Int, srcIp: String, srcPort: Int, dstIp: String, dstPort: Int): CaptureLease? {
        refresh()
        val ctx = context ?: return null
        val pkg = current?.takeIf { it != ctx.packageName } ?: return null
        return runCatching {
            val uid = ctx.getSystemService(ConnectivityManager::class.java).getConnectionOwnerUid(
                protocol, InetSocketAddress(srcIp, srcPort), InetSocketAddress(dstIp, dstPort))
            val appUid = ctx.packageManager.getApplicationInfo(pkg, 0).uid
            if (uid >= 0 && uid == appUid) CaptureLease(pkg, generation) else null
        }.getOrNull()
    }

    fun name(pkg: String): String = runCatching {
        val pm = context!!.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
}

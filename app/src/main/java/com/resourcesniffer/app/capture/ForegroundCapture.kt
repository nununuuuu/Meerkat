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
    private val timeline = ForegroundTimeline()
    private var lastRefresh = 0L
    private val selection = ForegroundSelection()
    private val current get() = selection.packageName
    private val generation get() = selection.generation

    fun hasPermission(context: Context): Boolean = context.getSystemService(AppOpsManager::class.java)
        .unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED

    @Synchronized fun start(context: Context) {
        this.context = context.applicationContext
        timeline.reset()
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
            timeline.accept(now, null)
            return
        }
        runCatching {
            // UsageEvents can arrive late on OEM devices. Replay an overlapping
            // window and apply only the newest event instead of dropping delayed events.
            val since = timeline.queryStart(now)
            val events = ctx.getSystemService(UsageStatsManager::class.java).queryEvents(since, now)
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        timeline.accept(event.timeStamp, event.packageName)
                    }
                    UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                        timeline.accept(event.timeStamp, null)
                    }
                }
            }
            // A floating service window can pause an Activity without changing
            // the app underneath. Only a new resumed Activity changes the target.
            change(timeline.packageName)
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

    /** Resolve on the relay worker, allowing delayed foreground events to catch up. */
    fun owner(protocol: Int, srcIp: String, srcPort: Int, dstIp: String, dstPort: Int): CaptureLease? {
        CaptureStatus.observed()
        val ctx = synchronized(this) { context } ?: return null
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val local = InetSocketAddress(srcIp, srcPort)
        var uid = -1
        var reason = "系統未回傳連線所屬 App"
        repeat(3) { attempt ->
            uid = runCatching { cm.getConnectionOwnerUid(protocol, local, InetSocketAddress(dstIp, dstPort)) }
                .onFailure { reason = "連線歸屬：${it.javaClass.simpleName}" }.getOrDefault(-1)
            // Unconnected UDP sockets have a wildcard remote endpoint in the kernel.
            if (uid < 0 && protocol == 17) uid = runCatching {
                cm.getConnectionOwnerUid(protocol, local, InetSocketAddress(if (srcIp.contains(':')) "::" else "0.0.0.0", 0))
            }.getOrDefault(-1)
            val lease = synchronized(this) {
                lastRefresh = 0L
                refresh()
                val pkg = current?.takeIf { it != ctx.packageName }
                val appUid = pkg?.let { runCatching { ctx.packageManager.getApplicationInfo(it, 0).uid }.getOrNull() }
                if (uid >= 0 && appUid == uid && pkg != null) CaptureLease(pkg, generation) else null
            }
            if (lease != null) return lease
            if (attempt < 2) try { Thread.sleep(150) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return null }
        }
        if (uid < 0) CaptureStatus.unattributed(reason) else CaptureStatus.background()
        return null
    }

    fun name(pkg: String): String = runCatching {
        val pm = context!!.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
}

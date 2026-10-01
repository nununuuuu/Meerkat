package com.resourcesniffer.app.capture

/** Replay overlapping usage-event windows without reactivating stale targets. */
internal class ForegroundTimeline {
    var timestamp: Long = 0L
        private set
    var packageName: String? = null
        private set
    fun accept(time: Long, pkg: String?) {
        if (time < timestamp) return
        timestamp = time
        packageName = pkg
    }
    fun queryStart(now: Long): Long = now - if (timestamp == 0L) 86_400_000L else 120_000L
    fun reset() { timestamp = 0L; packageName = null }
}

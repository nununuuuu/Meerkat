package com.resourcesniffer.app.capture

/** Monotonic generations prevent an old connection resuming collection after an app switch. */
internal class ForegroundSelection {
    var packageName: String? = null
        private set
    var generation: Long = 0
        private set
    fun change(next: String?) {
        if (next != packageName) { packageName = next; generation++ }
    }
    fun reset() { packageName = null; generation++ }
    fun accepts(owner: String?, epoch: Long): Boolean = owner != null && owner == packageName && epoch == generation
}

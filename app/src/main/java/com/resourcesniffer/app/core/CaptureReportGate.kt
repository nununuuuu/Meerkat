package com.resourcesniffer.app.core

/** Suppress identical reports from DOM, fetch and performance observers. */
class CaptureReportGate(private val capacity: Int = 2048) {
    private val seen = object : LinkedHashMap<List<Any?>, Unit>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<List<Any?>, Unit>?): Boolean = size > capacity
    }

    @Synchronized
    fun accept(signature: List<Any?>): Boolean {
        if (seen.containsKey(signature)) return false
        seen[signature] = Unit
        return true
    }
}

package com.resourcesniffer.app.repository

import com.resourcesniffer.app.core.SniffSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

object SessionStore {
    private val nextId = AtomicLong(System.currentTimeMillis())
    private val _current = MutableStateFlow<SniffSession?>(null)
    val current: StateFlow<SniffSession?> = _current.asStateFlow()

    @Synchronized
    fun start(targetPackage: String?, targetName: String?): SniffSession {
        val session = SniffSession(
            id = nextId.incrementAndGet(),
            startedAt = System.currentTimeMillis(),
            targetPackage = targetPackage,
            targetAppName = targetName,
        )
        _current.value = session
        return session
    }

    @Synchronized
    fun ensureBrowserSession(): SniffSession {
        val existing = _current.value
        if (existing != null && existing.targetPackage == null) return existing
        return start(null, "內建瀏覽器")
    }

    fun idOrDefault(): Long = _current.value?.id ?: 0L

    @Synchronized
    fun stop() {
        _current.value = _current.value?.copy(endedAt = System.currentTimeMillis())
    }
}

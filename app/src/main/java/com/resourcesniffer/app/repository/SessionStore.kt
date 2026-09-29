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

    @Volatile private var browserSession: SniffSession? = null
    @Volatile private var externalSession: SniffSession? = null

    @Synchronized
    fun startExternal(targetPackage: String? = null, targetName: String? = "全域 App 嗅探"): SniffSession {
        val session = SniffSession(
            id = nextId.incrementAndGet(),
            startedAt = System.currentTimeMillis(),
            targetPackage = targetPackage,
            targetAppName = targetName,
        )
        externalSession = session
        _current.value = session
        return session
    }

    @Synchronized
    fun ensureBrowserSession(): SniffSession {
        val existing = browserSession
        if (existing != null && existing.endedAt == null) return existing

        val session = SniffSession(
            id = nextId.incrementAndGet(),
            startedAt = System.currentTimeMillis(),
            targetPackage = null,
            targetAppName = "內建瀏覽器",
        )
        browserSession = session

        // External capture remains the active "current sniff" while it is running.
        if (externalSession == null) {
            _current.value = session
        }
        return session
    }

    fun idOrDefault(): Long = _current.value?.id ?: 0L

    fun externalIdOrDefault(): Long = externalSession?.id ?: 0L

    @Synchronized
    fun stopExternal() {
        val stopped = externalSession?.copy(endedAt = System.currentTimeMillis())
        externalSession = null
        _current.value = browserSession ?: stopped
    }

    @Synchronized
    fun stopBrowser() {
        browserSession = browserSession?.copy(endedAt = System.currentTimeMillis())
        if (externalSession == null) {
            _current.value = browserSession
        }
    }
}

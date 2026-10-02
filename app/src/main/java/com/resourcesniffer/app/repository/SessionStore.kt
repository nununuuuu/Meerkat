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

    private val _browser = MutableStateFlow<SniffSession?>(null)
    val browser: StateFlow<SniffSession?> = _browser.asStateFlow()

    @Synchronized
    fun ensureBrowserSession(): SniffSession {
        _browser.value?.takeIf { it.endedAt == null }?.let { return it }
        val session = SniffSession(
            id = nextId.incrementAndGet(),
            startedAt = System.currentTimeMillis(),
            targetPackage = null,
            targetAppName = "內建瀏覽器",
        )
        _browser.value = session
        _current.value = session
        return session
    }

    fun idOrDefault(): Long = _current.value?.id ?: 0L
    fun browserIdOrDefault(): Long = _browser.value?.id ?: 0L
    @Synchronized
    fun stopBrowser() {
        val stopped = _browser.value?.copy(endedAt = System.currentTimeMillis())
        _browser.value = stopped
        _current.value = stopped
    }
}

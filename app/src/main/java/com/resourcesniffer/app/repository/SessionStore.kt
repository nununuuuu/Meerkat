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

    private val _external = MutableStateFlow<SniffSession?>(null)
    val external: StateFlow<SniffSession?> = _external.asStateFlow()

    @Synchronized
    fun startExternal(targetPackage: String? = null, targetName: String? = "全域 App 嗅探"): SniffSession {
        _external.value?.takeIf { it.endedAt == null }?.let {
            _current.value = it
            return it
        }
        val session = SniffSession(
            id = nextId.incrementAndGet(),
            startedAt = System.currentTimeMillis(),
            targetPackage = targetPackage,
            targetAppName = targetName,
        )
        _external.value = session
        _current.value = session
        return session
    }

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
        if (_external.value == null) _current.value = session
        return session
    }

    fun idOrDefault(): Long = _current.value?.id ?: 0L
    fun browserIdOrDefault(): Long = _browser.value?.id ?: 0L
    fun externalIdOrDefault(): Long = _external.value?.id ?: 0L

    @Synchronized
    fun stopExternal() {
        val stopped = _external.value?.copy(endedAt = System.currentTimeMillis())
        _external.value = null
        _current.value = _browser.value ?: stopped
    }

    @Synchronized
    fun stopBrowser() {
        val stopped = _browser.value?.copy(endedAt = System.currentTimeMillis())
        _browser.value = stopped
        if (_external.value == null) _current.value = stopped
    }
}

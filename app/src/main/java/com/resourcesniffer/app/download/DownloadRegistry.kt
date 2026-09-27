package com.resourcesniffer.app.download

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object DownloadRegistry {
    private const val MAX_ITEMS = 100
    private val _items = MutableStateFlow<List<DownloadRecord>>(emptyList())
    val items: StateFlow<List<DownloadRecord>> = _items.asStateFlow()

    @Synchronized
    fun add(record: DownloadRecord) {
        _items.value = (listOf(record) + _items.value.filterNot { it.id == record.id }).take(MAX_ITEMS)
    }

    @Synchronized
    fun update(id: String, transform: (DownloadRecord) -> DownloadRecord) {
        _items.value = _items.value.map { if (it.id == id) transform(it) else it }
    }

    @Synchronized
    fun clearCompleted() {
        _items.value = _items.value.filterNot {
            it.state == DownloadState.COMPLETED ||
                it.state == DownloadState.FAILED ||
                it.state == DownloadState.CANCELLED
        }
    }
}

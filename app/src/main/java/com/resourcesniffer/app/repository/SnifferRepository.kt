package com.resourcesniffer.app.repository

import com.resourcesniffer.app.core.Resource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object SnifferRepository {
    private val _resources = MutableStateFlow<List<Resource>>(emptyList())
    val resources: StateFlow<List<Resource>> = _resources.asStateFlow()

    fun add(resource: Resource) {
        if (_resources.value.any { it.host == resource.host && it.url == resource.url && it.type == resource.type }) return
        _resources.value = listOf(resource) + _resources.value
    }

    fun clear() {
        _resources.value = emptyList()
    }
}

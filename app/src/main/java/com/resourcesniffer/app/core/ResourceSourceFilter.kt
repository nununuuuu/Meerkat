package com.resourcesniffer.app.core

internal fun isMetaResourceHost(host: String): Boolean {
    val normalized = host.lowercase().trimEnd('.')
    return listOf("instagram.com", "cdninstagram.com", "fbcdn.net", "facebook.com", "threads.com", "threads.net")
        .any { normalized == it || normalized.endsWith(".$it") }
}

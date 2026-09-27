package com.resourcesniffer.app.core

enum class ResourceType {
    IMAGE, VIDEO, AUDIO, DOCUMENT, STREAM, ARCHIVE, OTHER
}

enum class StreamType { HLS, DASH, DIRECT }

data class Resource(
    val id: Long,
    val sessionId: Long,
    val sourceAppPackage: String?,
    val sourceAppName: String?,
    val url: String?,
    val host: String,
    val mimeType: String?,
    val extension: String?,
    val contentLength: Long?,
    val type: ResourceType,
    val streamType: StreamType? = null,
    val referer: String? = null,
    val userAgent: String? = null,
    val cookie: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val detectedAt: Long = System.currentTimeMillis(),
)

data class SniffSession(
    val id: Long,
    val startedAt: Long,
    val endedAt: Long? = null,
    val targetPackage: String? = null,
    val targetAppName: String? = null,
    val requestCount: Int = 0,
    val resourceCount: Int = 0,
)

data class InstalledApp(
    val label: String,
    val packageName: String,
)

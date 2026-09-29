package com.resourcesniffer.app.download

import com.resourcesniffer.app.core.StreamType

enum class DownloadQuality { HIGH, LOW }

enum class DownloadState {
    QUEUED, DOWNLOADING, COMPLETED, FAILED, CANCELLED
}

data class DownloadRecord(
    val id: String,
    val url: String,
    val displayName: String,
    val mimeType: String?,
    val streamType: StreamType?,
    val cookie: String?,
    val referer: String?,
    val userAgent: String?,
    val localSourcePath: String? = null,
    val quality: DownloadQuality = DownloadQuality.HIGH,
    val state: DownloadState,
    val progress: Int? = null,
    val detail: String? = null,
    val localUri: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

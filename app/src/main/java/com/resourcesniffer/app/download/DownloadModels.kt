package com.resourcesniffer.app.download

enum class DownloadState {
    QUEUED, DOWNLOADING, COMPLETED, FAILED, CANCELLED
}

data class DownloadRecord(
    val id: String,
    val url: String,
    val displayName: String,
    val mimeType: String?,
    val state: DownloadState,
    val progress: Int? = null,
    val detail: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

package com.resourcesniffer.app.core

object ResourceClassifier {
    private val imageExt = setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "bmp", "svg")
    private val videoExt = setOf("mp4", "webm", "mkv", "mov", "m4v", "avi")
    private val audioExt = setOf("mp3", "m4a", "aac", "ogg", "opus", "flac", "wav")
    private val docExt = setOf("pdf", "epub", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt")
    private val archiveExt = setOf("zip", "rar", "7z", "tar", "gz")

    data class Classification(val type: ResourceType, val streamType: StreamType? = null)

    fun classify(url: String?, mimeType: String?): Classification {
        val mime = mimeType?.lowercase()?.substringBefore(';')?.trim().orEmpty()
        val cleanUrl = url?.substringBefore('#')?.substringBefore('?').orEmpty()
        val ext = cleanUrl.substringAfterLast('.', "").lowercase()

        if (mime == "application/vnd.apple.mpegurl" || mime == "application/x-mpegurl" || ext == "m3u8") {
            return Classification(ResourceType.STREAM, StreamType.HLS)
        }
        if (mime == "application/dash+xml" || ext == "mpd") {
            return Classification(ResourceType.STREAM, StreamType.DASH)
        }
        if (mime.startsWith("image/") || ext in imageExt) return Classification(ResourceType.IMAGE)
        if (mime.startsWith("video/") || ext in videoExt) return Classification(ResourceType.VIDEO, StreamType.DIRECT)
        if (mime.startsWith("audio/") || ext in audioExt) return Classification(ResourceType.AUDIO)
        if (mime == "application/pdf" || ext in docExt) return Classification(ResourceType.DOCUMENT)
        if (ext in archiveExt) return Classification(ResourceType.ARCHIVE)
        return Classification(ResourceType.OTHER)
    }
}

package com.resourcesniffer.app.core

import android.net.Uri

object ResourceClassifier {
    private val imageExt = setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "bmp", "svg", "heic", "heif")
    private val videoExt = setOf("mp4", "webm", "mkv", "mov", "m4v", "avi", "ts", "m2ts")
    private val audioExt = setOf("mp3", "m4a", "aac", "ogg", "opus", "flac", "wav")
    private val docExt = setOf("pdf", "epub", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "rtf")
    private val archiveExt = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz")

    data class Classification(val type: ResourceType, val streamType: StreamType? = null)

    fun classify(url: String?, mimeType: String?): Classification {
        val mime = mimeType?.lowercase()?.substringBefore(';')?.trim().orEmpty()
        val ext = extensionFromUrl(url)

        if (mime in setOf("application/vnd.apple.mpegurl", "application/x-mpegurl") || ext == "m3u8") {
            return Classification(ResourceType.STREAM, StreamType.HLS)
        }
        if (mime == "application/dash+xml" || ext == "mpd") {
            return Classification(ResourceType.STREAM, StreamType.DASH)
        }
        if (mime.startsWith("image/") || ext in imageExt) return Classification(ResourceType.IMAGE)
        if (mime.startsWith("video/") || ext in videoExt) return Classification(ResourceType.VIDEO, StreamType.DIRECT)
        if (mime.startsWith("audio/") || ext in audioExt) return Classification(ResourceType.AUDIO)

        val documentMime = mime == "application/pdf" ||
            mime == "application/epub+zip" ||
            mime.startsWith("application/msword") ||
            mime.contains("officedocument") ||
            mime == "text/plain" ||
            mime == "text/csv" ||
            mime == "application/rtf"
        if (documentMime || ext in docExt) return Classification(ResourceType.DOCUMENT)

        val archiveMime = mime in setOf(
            "application/zip",
            "application/x-rar-compressed",
            "application/vnd.rar",
            "application/x-7z-compressed",
            "application/gzip",
            "application/x-tar",
        )
        if (archiveMime || ext in archiveExt) return Classification(ResourceType.ARCHIVE)

        return Classification(ResourceType.OTHER)
    }

    private fun extensionFromUrl(url: String?): String {
        if (url.isNullOrBlank()) return ""
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        val candidates = buildList {
            uri?.lastPathSegment?.let(::add)
            listOf("filename", "file", "name", "download", "path").forEach { key ->
                uri?.getQueryParameter(key)?.let(::add)
            }
        }
        return candidates.asSequence()
            .map { it.substringBefore('#').substringBefore('?') }
            .map { it.substringAfterLast('.', "").lowercase() }
            .firstOrNull { it.length in 2..6 }
            .orEmpty()
    }
}

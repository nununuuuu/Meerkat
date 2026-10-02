package com.resourcesniffer.app.core

import android.net.Uri

object ResourceClassifier {
    // Universal downloadable resource classification.
    private val imageExt = setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "bmp", "svg", "heic", "heif")
    private val videoExt = setOf("mp4", "webm", "mkv", "mov", "m4v", "avi", "ts", "m2ts")
    private val audioExt = setOf("mp3", "m4a", "aac", "ogg", "opus", "flac", "wav")
    private val docExt = setOf(
        "pdf", "epub",
        "doc", "docx", "docm", "dot", "dotx", "dotm",
        "xls", "xlsx", "xlsm", "xlsb", "xlt", "xltx", "xltm",
        "ppt", "pptx", "pptm", "pps", "ppsx", "ppsm", "pot", "potx", "potm",
        "odt", "ods", "odp", "pages", "numbers", "key",
        "txt", "csv", "tsv", "rtf", "md",
    )
    private val archiveExt = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz")

    data class Classification(val type: ResourceType, val streamType: StreamType? = null)

    fun normalizeMime(mimeType: String?): String =
        mimeType?.lowercase()?.substringBefore(';')?.trim().orEmpty()

    fun classify(url: String?, mimeType: String?): Classification {
        if (ResourceEligibility.isPageOrBackgroundResponse(url, mimeType)) return Classification(ResourceType.OTHER)
        val mime = normalizeMime(mimeType)
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
            mime == "application/msword" ||
            mime == "application/rtf" ||
            mime == "application/vnd.ms-excel" ||
            mime == "application/vnd.ms-powerpoint" ||
            mime.contains("officedocument") ||
            mime.contains("ms-word") ||
            mime.contains("ms-excel") ||
            mime.contains("ms-powerpoint") ||
            mime.startsWith("application/vnd.oasis.opendocument.") ||
            mime == "application/vnd.apple.pages" ||
            mime == "application/vnd.apple.numbers" ||
            mime == "application/vnd.apple.keynote" ||
            mime == "text/plain" ||
            mime == "text/csv" ||
            mime == "text/tab-separated-values" ||
            mime == "text/markdown"
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

    fun extensionFromUrl(url: String?): String {
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

package com.resourcesniffer.app.core

import java.net.URI
import java.net.URLDecoder

/** Distinguish downloadable files from page and API responses, independent of site. */
object ResourceEligibility {
    private val textFiles = setOf("txt", "csv", "tsv", "md", "rtf", "m3u8", "mpd")
    fun isPageOrBackgroundResponse(url: String?, mimeType: String?, fileName: String? = null): Boolean {
        val mime = mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        val uri = runCatching { URI(url.orEmpty()) }.getOrNull()
        val path = uri?.path.orEmpty()
        val ext = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()
        if (mime in setOf("text/html", "application/xhtml+xml")) return true
        if (ext in setOf("html", "htm") && (mime.isBlank() || mime.endsWith("/*"))) return true
        if (mime != "text/plain") return false
        val names = buildList {
            add(path)
            fileName?.let(::add)
            uri?.rawQuery?.split('&')?.forEach { part ->
                if (part.substringBefore('=') in setOf("filename", "file", "name", "download", "path")) {
                    runCatching { URLDecoder.decode(part.substringAfter('=', ""), "UTF-8") }.getOrNull()?.let(::add)
                }
            }
        }
        return names.none { it.substringAfterLast('.', "").lowercase() in textFiles }
    }
}

package com.resourcesniffer.app.capture

import android.net.Uri
import com.resourcesniffer.app.core.Resource
import com.resourcesniffer.app.core.ResourceClassifier
import com.resourcesniffer.app.core.ResourceType
import com.resourcesniffer.app.repository.SnifferRepository
import com.resourcesniffer.app.repository.SessionStore
import java.net.URLDecoder
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * Lightweight HTTP/1.x resource inspector.
 *
 * Generic connections are never emitted. Only requests/responses that can be
 * classified as downloadable resources reach the repository.
 */
class HttpResourceStreamInspector(
    private val sourcePackage: String?,
    private val sourceName: String? = null,
) {
    private data class PendingRequest(
        val url: String,
        val referer: String?,
        val userAgent: String?,
        val cookie: String?,
    )

    private val ids = AtomicLong(System.currentTimeMillis())
    private val requestBuffer = StringBuilder()
    private val responseBuffer = StringBuilder()
    private val pendingRequests = ArrayDeque<PendingRequest>()

    @Synchronized
    fun onClientBytes(bytes: ByteArray, length: Int) {
        if (length <= 0 || !looksTextual(bytes, length)) return
        requestBuffer.append(bytes.copyOfRange(0, length).toString(Charsets.ISO_8859_1))
        if (requestBuffer.length > MAX_HEADER_BUFFER) requestBuffer.delete(0, requestBuffer.length - MAX_HEADER_BUFFER)
        consumeRequests()
    }

    @Synchronized
    fun onServerBytes(bytes: ByteArray, length: Int) {
        if (length <= 0 || !looksTextual(bytes, length)) return
        responseBuffer.append(bytes.copyOfRange(0, length).toString(Charsets.ISO_8859_1))
        if (responseBuffer.length > MAX_HEADER_BUFFER) responseBuffer.delete(0, responseBuffer.length - MAX_HEADER_BUFFER)
        consumeResponses()
    }

    private fun consumeRequests() {
        while (true) {
            val end = requestBuffer.indexOf("\r\n\r\n")
            if (end < 0) return
            val header = requestBuffer.substring(0, end + 4)
            requestBuffer.delete(0, end + 4)

            val lines = header.split("\r\n")
            val requestLine = lines.firstOrNull() ?: continue
            val parts = requestLine.split(' ')
            if (parts.size < 2 || parts[0] !in METHODS) continue

            val target = parts[1]
            val host = headerValue(lines, "Host")
            val url = when {
                target.startsWith("http://") || target.startsWith("https://") -> target
                host != null && target.startsWith("/") -> "http://$host$target"
                else -> null
            } ?: continue

            val request = PendingRequest(
                url = url,
                referer = headerValue(lines, "Referer"),
                userAgent = headerValue(lines, "User-Agent"),
                cookie = headerValue(lines, "Cookie"),
            )
            pendingRequests.addLast(request)
            if (pendingRequests.size > MAX_PENDING) pendingRequests.removeFirst()

            publishIfResource(request, mime = null, contentLength = null, fileName = null)
        }
    }

    private fun consumeResponses() {
        while (true) {
            val httpStart = responseBuffer.indexOf("HTTP/")
            if (httpStart < 0) {
                if (responseBuffer.length > 8192) responseBuffer.clear()
                return
            }
            if (httpStart > 0) responseBuffer.delete(0, httpStart)

            val end = responseBuffer.indexOf("\r\n\r\n")
            if (end < 0) return
            val header = responseBuffer.substring(0, end + 4)
            responseBuffer.delete(0, end + 4)

            val lines = header.split("\r\n")
            val statusCode = lines.firstOrNull()
                ?.split(' ')
                ?.getOrNull(1)
                ?.toIntOrNull()
            val mime = headerValue(lines, "Content-Type")
            val length = headerValue(lines, "Content-Length")?.toLongOrNull()
            val disposition = headerValue(lines, "Content-Disposition")
            val fileName = parseFileName(disposition)
            val request = if (pendingRequests.isEmpty()) continue else pendingRequests.removeFirst()

            val location = headerValue(lines, "Location")
            if (statusCode != null && statusCode in 300..399 && !location.isNullOrBlank()) {
                val redirected = runCatching {
                    java.net.URI(request.url).resolve(location).toString()
                }.getOrNull()
                if (redirected != null) {
                    publishIfResource(
                        request.copy(url = redirected),
                        mime = null,
                        contentLength = null,
                        fileName = fileName,
                    )
                }
            }

            publishIfResource(request, mime, length, fileName)
        }
    }

    private fun publishIfResource(
        request: PendingRequest,
        mime: String?,
        contentLength: Long?,
        fileName: String?,
    ) {
        val classificationUrl = if (!fileName.isNullOrBlank()) {
            val separator = if (request.url.contains('?')) '&' else '?'
            request.url + separator + "filename=" + Uri.encode(fileName)
        } else {
            request.url
        }

        val classification = ResourceClassifier.classify(classificationUrl, mime)
        if (classification.type == ResourceType.OTHER) return

        val uri = runCatching { Uri.parse(request.url) }.getOrNull()
        val extension = fileName
            ?.substringAfterLast('.', "")
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
            ?: uri?.lastPathSegment
                ?.substringAfterLast('.', "")
                ?.lowercase()
                ?.takeIf { it.isNotBlank() }

        SnifferRepository.add(
            Resource(
                id = ids.getAndIncrement(),
                sessionId = SessionStore.idOrDefault(),
                sourceAppPackage = sourcePackage,
                sourceAppName = sourceName,
                url = request.url,
                host = uri?.host ?: "未知來源",
                mimeType = mime,
                extension = extension,
                contentLength = contentLength,
                type = classification.type,
                streamType = classification.streamType,
                referer = request.referer,
                userAgent = request.userAgent,
                cookie = request.cookie,
            )
        )
    }

    private fun headerValue(lines: List<String>, name: String): String? =
        lines.firstOrNull { it.startsWith("$name:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    private fun parseFileName(disposition: String?): String? {
        if (disposition.isNullOrBlank()) return null
        val encoded = Regex("""filename\*=UTF-8''([^;]+)""", RegexOption.IGNORE_CASE)
            .find(disposition)
            ?.groupValues
            ?.getOrNull(1)
        if (!encoded.isNullOrBlank()) {
            return runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrDefault(encoded)
        }
        return Regex("""filename="?([^";]+)"?""", RegexOption.IGNORE_CASE)
            .find(disposition)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
    }

    private fun looksTextual(bytes: ByteArray, length: Int): Boolean {
        val sample = minOf(length, 16)
        if (sample == 0) return false
        var printable = 0
        for (i in 0 until sample) {
            val c = bytes[i].toInt() and 0xff
            if (c == 9 || c == 10 || c == 13 || c in 32..126) printable++
        }
        return printable >= sample * 3 / 4
    }

    companion object {
        private const val MAX_HEADER_BUFFER = 128 * 1024
        private const val MAX_PENDING = 128
        private val METHODS = setOf("GET", "POST", "HEAD", "PUT", "DELETE", "OPTIONS", "PATCH")
    }
}

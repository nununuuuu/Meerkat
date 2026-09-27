package com.resourcesniffer.app.capture

import android.net.Uri
import com.resourcesniffer.app.core.Resource
import com.resourcesniffer.app.core.ResourceClassifier
import com.resourcesniffer.app.core.ResourceType
import com.resourcesniffer.app.repository.SnifferRepository
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * HTTP/1.x stream inspector.
 *
 * It never publishes generic connections. A record is emitted only when a URL or
 * response MIME can be classified as an actual downloadable resource.
 */
class HttpResourceStreamInspector(
    private val sourcePackage: String?,
    private val sourceName: String? = null,
) {
    private val ids = AtomicLong(System.currentTimeMillis())
    private val requestBuffer = StringBuilder()
    private val responseBuffer = StringBuilder()
    private val pendingUrls = ArrayDeque<String>()

    @Synchronized
    fun onClientBytes(bytes: ByteArray, length: Int) {
        if (length <= 0 || !looksTextual(bytes, length)) return
        requestBuffer.append(bytes.copyOfRange(0, length).toString(Charsets.ISO_8859_1))
        consumeRequests()
    }

    @Synchronized
    fun onServerBytes(bytes: ByteArray, length: Int) {
        if (length <= 0 || !looksTextual(bytes, length)) return
        responseBuffer.append(bytes.copyOfRange(0, length).toString(Charsets.ISO_8859_1))
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
            val host = lines.firstOrNull { it.startsWith("Host:", ignoreCase = true) }
                ?.substringAfter(':')?.trim()?.takeIf { it.isNotBlank() }

            val url = when {
                target.startsWith("http://") || target.startsWith("https://") -> target
                host != null && target.startsWith("/") -> "http://$host$target"
                else -> null
            } ?: continue

            pendingUrls.addLast(url)
            publishIfResource(url, null, null)
        }
    }

    private fun consumeResponses() {
        while (true) {
            val end = responseBuffer.indexOf("\r\n\r\n")
            if (end < 0) return
            val header = responseBuffer.substring(0, end + 4)
            responseBuffer.delete(0, end + 4)
            if (!header.startsWith("HTTP/")) continue

            val mime = header.lineSequence()
                .firstOrNull { it.startsWith("Content-Type:", ignoreCase = true) }
                ?.substringAfter(':')?.trim()
            val length = header.lineSequence()
                .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                ?.substringAfter(':')?.trim()?.toLongOrNull()
            val url = pendingUrls.removeFirstOrNull() ?: continue
            publishIfResource(url, mime, length)
        }
    }

    private fun publishIfResource(url: String, mime: String?, contentLength: Long?) {
        val classification = ResourceClassifier.classify(url, mime)
        if (classification.type == ResourceType.OTHER) return
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        val extension = uri?.lastPathSegment
            ?.substringAfterLast('.', "")
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }

        SnifferRepository.add(
            Resource(
                id = ids.getAndIncrement(),
                sessionId = 1,
                sourceAppPackage = sourcePackage,
                sourceAppName = sourceName,
                url = url,
                host = uri?.host ?: "未知來源",
                mimeType = mime,
                extension = extension,
                contentLength = contentLength,
                type = classification.type,
                streamType = classification.streamType,
            )
        )
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
        private val METHODS = setOf("GET", "POST", "HEAD", "PUT", "DELETE", "OPTIONS", "PATCH")
    }
}

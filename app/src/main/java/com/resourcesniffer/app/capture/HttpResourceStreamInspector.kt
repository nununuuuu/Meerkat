package com.resourcesniffer.app.capture

import android.net.Uri
import com.resourcesniffer.app.core.Resource
import com.resourcesniffer.app.core.ResourceClassifier
import com.resourcesniffer.app.core.ResourceType
import com.resourcesniffer.app.core.StreamType
import com.resourcesniffer.app.repository.SnifferRepository
import com.resourcesniffer.app.repository.SessionStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import org.brotli.dec.BrotliInputStream
import java.net.URI
import java.net.URLDecoder
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * HTTP/1.x stream inspector with framing awareness.
 *
 * It never buffers ordinary response bodies. Content-Length and chunked bodies
 * are skipped incrementally so keep-alive requests remain aligned. Small HLS
 * and DASH manifests are captured for metadata extraction.
 */
class HttpResourceStreamInspector(
    private val sourcePackage: String?,
    private val sourceName: String? = null,
    private val secure: Boolean = false,
) {
    private data class PendingRequest(
        val method: String,
        val url: String,
        val referer: String?,
        val userAgent: String?,
        val cookie: String?,
    )

    private data class ResponseContext(
        val request: PendingRequest,
        val resource: Resource?,
        val streamType: StreamType?,
        val contentType: String?,
        val contentEncoding: String?,
        val body: ByteArrayOutputStream? = null,
    )

    private enum class BodyMode { HEADER, FIXED, CHUNK_SIZE, CHUNK_DATA, CHUNK_CRLF, CHUNK_TRAILERS, UNTIL_CLOSE }

    private val ids = AtomicLong(System.currentTimeMillis())
    private val requestQueue = ByteQueue()
    private val responseQueue = ByteQueue()
    private val pendingRequests = ArrayDeque<PendingRequest>()

    private var requestMode = BodyMode.HEADER
    private var requestRemaining = 0L
    private var requestChunkRemaining = 0L

    private var responseMode = BodyMode.HEADER
    private var responseRemaining = 0L
    private var responseChunkRemaining = 0L
    private var responseContext: ResponseContext? = null

    private var webSocketMode = false
    private var webSocketRequest: PendingRequest? = null
    private val webSocketClientParser = WebSocketFrameParser { text ->
        webSocketRequest?.let { deepSearch(it, text) }
    }
    private val webSocketServerParser = WebSocketFrameParser { text ->
        webSocketRequest?.let { deepSearch(it, text) }
    }

    @Synchronized
    fun onClientBytes(bytes: ByteArray, length: Int) {
        if (length <= 0) return
        if (webSocketMode) {
            webSocketClientParser.feed(bytes, length)
            return
        }
        requestQueue.append(bytes, length)
        consumeRequests()
    }

    @Synchronized
    fun onServerBytes(bytes: ByteArray, length: Int) {
        if (length <= 0) return
        if (webSocketMode) {
            webSocketServerParser.feed(bytes, length)
            return
        }
        responseQueue.append(bytes, length)
        consumeResponses()
    }

    private fun consumeRequests() {
        while (true) {
            when (requestMode) {
                BodyMode.HEADER -> {
                    val headerEnd = requestQueue.indexOf(HEADER_END)
                    if (headerEnd < 0) {
                        requestQueue.trimTo(MAX_HEADER_BUFFER)
                        return
                    }
                    val headerBytes = requestQueue.take(headerEnd + HEADER_END.size)
                    val lines = headerBytes.toString(Charsets.ISO_8859_1).split("\r\n")
                    val requestLine = lines.firstOrNull().orEmpty()
                    val parts = requestLine.split(' ')
                    if (parts.size < 2 || parts[0] !in METHODS) continue

                    val method = parts[0]
                    val target = parts[1]
                    val host = headerValue(lines, "Host")
                    val url = when {
                        target.startsWith("http://", true) || target.startsWith("https://", true) -> target
                        host != null && target.startsWith("/") ->
                            (if (secure) "https://" else "http://") + host + target
                        else -> null
                    }

                    if (url != null) {
                        val request = PendingRequest(
                            method = method,
                            url = url,
                            referer = headerValue(lines, "Referer"),
                            userAgent = headerValue(lines, "User-Agent"),
                            cookie = headerValue(lines, "Cookie"),
                        )
                        pendingRequests.addLast(request)
                        while (pendingRequests.size > MAX_PENDING) pendingRequests.removeFirst()
                        publishIfResource(request, null, null, null)
                    }

                    val transfer = headerValue(lines, "Transfer-Encoding").orEmpty()
                    val contentLength = headerValue(lines, "Content-Length")?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                    when {
                        transfer.contains("chunked", true) -> requestMode = BodyMode.CHUNK_SIZE
                        contentLength > 0L -> {
                            requestRemaining = contentLength
                            requestMode = BodyMode.FIXED
                        }
                        else -> requestMode = BodyMode.HEADER
                    }
                }

                BodyMode.FIXED -> {
                    if (requestQueue.size == 0) return
                    val n = minOf(requestRemaining, requestQueue.size.toLong()).toInt()
                    requestQueue.drop(n)
                    requestRemaining -= n
                    if (requestRemaining <= 0L) requestMode = BodyMode.HEADER
                }

                BodyMode.CHUNK_SIZE -> {
                    val end = requestQueue.indexOf(CRLF)
                    if (end < 0) return
                    val line = requestQueue.take(end + 2).toString(Charsets.US_ASCII).trim()
                    val size = line.substringBefore(';').trim().toLongOrNull(16) ?: run {
                        requestMode = BodyMode.HEADER
                        continue
                    }
                    if (size == 0L) requestMode = BodyMode.CHUNK_TRAILERS
                    else {
                        requestChunkRemaining = size
                        requestMode = BodyMode.CHUNK_DATA
                    }
                }

                BodyMode.CHUNK_DATA -> {
                    if (requestQueue.size == 0) return
                    val n = minOf(requestChunkRemaining, requestQueue.size.toLong()).toInt()
                    requestQueue.drop(n)
                    requestChunkRemaining -= n
                    if (requestChunkRemaining <= 0L) requestMode = BodyMode.CHUNK_CRLF
                }

                BodyMode.CHUNK_CRLF -> {
                    if (requestQueue.size < 2) return
                    requestQueue.drop(2)
                    requestMode = BodyMode.CHUNK_SIZE
                }

                BodyMode.CHUNK_TRAILERS -> {
                    if (requestQueue.startsWith(CRLF)) {
                        requestQueue.drop(2)
                        requestMode = BodyMode.HEADER
                        continue
                    }
                    val end = requestQueue.indexOf(HEADER_END)
                    if (end < 0) return
                    requestQueue.drop(end + HEADER_END.size)
                    requestMode = BodyMode.HEADER
                }

                BodyMode.UNTIL_CLOSE -> return
            }
        }
    }

    private fun consumeResponses() {
        while (true) {
            when (responseMode) {
                BodyMode.HEADER -> {
                    val headerEnd = responseQueue.indexOf(HEADER_END)
                    if (headerEnd < 0) {
                        responseQueue.trimTo(MAX_HEADER_BUFFER)
                        return
                    }

                    val headerBytes = responseQueue.take(headerEnd + HEADER_END.size)
                    val lines = headerBytes.toString(Charsets.ISO_8859_1).split("\r\n")
                    val statusCode = lines.firstOrNull()
                        ?.split(' ')
                        ?.getOrNull(1)
                        ?.toIntOrNull()
                        ?: continue

                    if (statusCode in 100..199 && statusCode != 101) {
                        continue
                    }

                    val request = if (pendingRequests.isEmpty()) null else pendingRequests.removeFirst()
                    if (request == null) {
                        responseMode = BodyMode.UNTIL_CLOSE
                        responseQueue.clear()
                        return
                    }

                    val upgrade = headerValue(lines, "Upgrade")
                    if (statusCode == 101 && upgrade.equals("websocket", true)) {
                        webSocketRequest = request
                        webSocketMode = true
                        val leftover = responseQueue.take(responseQueue.size)
                        if (leftover.isNotEmpty()) {
                            webSocketServerParser.feed(leftover, leftover.size)
                        }
                        return
                    }

                    val mime = headerValue(lines, "Content-Type")
                    val contentEncoding = headerValue(lines, "Content-Encoding")
                    val contentLength = headerValue(lines, "Content-Length")?.toLongOrNull()?.takeIf { it >= 0L }
                    val disposition = headerValue(lines, "Content-Disposition")
                    val fileName = parseFileName(disposition)
                    val location = headerValue(lines, "Location")

                    if (statusCode in 300..399 && !location.isNullOrBlank()) {
                        val redirected = runCatching { URI(request.url).resolve(location).toString() }.getOrNull()
                        if (redirected != null) {
                            publishIfResource(request.copy(url = redirected), null, null, fileName)
                        }
                    }

                    val resource = publishIfResource(request, mime, contentLength, fileName)
                    val streamType = resource?.streamType
                    val shouldInspectBody = streamType != null || isDeepSearchTextMime(mime)
                    responseContext = ResponseContext(
                        request = request,
                        resource = resource,
                        streamType = streamType,
                        contentType = mime,
                        contentEncoding = contentEncoding,
                        body = if (shouldInspectBody) ByteArrayOutputStream() else null,
                    )

                    val noBody = request.method.equals("HEAD", true) ||
                        statusCode == 204 || statusCode == 304 ||
                        statusCode in 100..199

                    if (noBody) {
                        finishResponse()
                        responseMode = BodyMode.HEADER
                        continue
                    }

                    val transfer = headerValue(lines, "Transfer-Encoding").orEmpty()
                    when {
                        transfer.contains("chunked", true) -> responseMode = BodyMode.CHUNK_SIZE
                        contentLength != null -> {
                            responseRemaining = contentLength
                            if (contentLength == 0L) {
                                finishResponse()
                                responseMode = BodyMode.HEADER
                            } else {
                                responseMode = BodyMode.FIXED
                            }
                        }
                        headerValue(lines, "Connection").equals("close", true) -> {
                            responseMode = BodyMode.UNTIL_CLOSE
                            responseQueue.clear()
                            return
                        }
                        else -> {
                            responseMode = BodyMode.UNTIL_CLOSE
                            responseQueue.clear()
                            return
                        }
                    }
                }

                BodyMode.FIXED -> {
                    if (responseQueue.size == 0) return
                    val n = minOf(responseRemaining, responseQueue.size.toLong()).toInt()
                    captureResponseBody(responseQueue.peek(n))
                    responseQueue.drop(n)
                    responseRemaining -= n
                    if (responseRemaining <= 0L) {
                        finishResponse()
                        responseMode = BodyMode.HEADER
                    }
                }

                BodyMode.CHUNK_SIZE -> {
                    val end = responseQueue.indexOf(CRLF)
                    if (end < 0) return
                    val line = responseQueue.take(end + 2).toString(Charsets.US_ASCII).trim()
                    val size = line.substringBefore(';').trim().toLongOrNull(16) ?: run {
                        responseMode = BodyMode.UNTIL_CLOSE
                        responseQueue.clear()
                        return
                    }
                    if (size == 0L) responseMode = BodyMode.CHUNK_TRAILERS
                    else {
                        responseChunkRemaining = size
                        responseMode = BodyMode.CHUNK_DATA
                    }
                }

                BodyMode.CHUNK_DATA -> {
                    if (responseQueue.size == 0) return
                    val n = minOf(responseChunkRemaining, responseQueue.size.toLong()).toInt()
                    captureResponseBody(responseQueue.peek(n))
                    responseQueue.drop(n)
                    responseChunkRemaining -= n
                    if (responseChunkRemaining <= 0L) responseMode = BodyMode.CHUNK_CRLF
                }

                BodyMode.CHUNK_CRLF -> {
                    if (responseQueue.size < 2) return
                    responseQueue.drop(2)
                    responseMode = BodyMode.CHUNK_SIZE
                }

                BodyMode.CHUNK_TRAILERS -> {
                    if (responseQueue.startsWith(CRLF)) {
                        responseQueue.drop(2)
                        finishResponse()
                        responseMode = BodyMode.HEADER
                        continue
                    }
                    val end = responseQueue.indexOf(HEADER_END)
                    if (end < 0) return
                    responseQueue.drop(end + HEADER_END.size)
                    finishResponse()
                    responseMode = BodyMode.HEADER
                }

                BodyMode.UNTIL_CLOSE -> {
                    responseQueue.clear()
                    return
                }
            }
        }
    }

    private fun captureResponseBody(bytes: ByteArray) {
        val out = responseContext?.body ?: return
        if (out.size() >= MAX_INSPECT_BODY_BYTES) return
        val writable = minOf(bytes.size, MAX_INSPECT_BODY_BYTES - out.size())
        out.write(bytes, 0, writable)
    }

    private fun finishResponse() {
        val context = responseContext
        responseContext = null
        val resource = context?.resource ?: return
        val body = context.body?.toByteArray() ?: return
        if (body.isEmpty()) return

        val decoded = decodeContent(body, context.contentEncoding) ?: return
        val text = decoded.toString(Charsets.UTF_8)
            .removePrefix("\uFEFF")
            .take(MAX_INSPECT_BODY_CHARS)

        val enriched = when (context.streamType) {
            StreamType.HLS -> enrichHls(resource, text)
            StreamType.DASH -> enrichDash(resource, text)
            else -> resource
        }
        if (enriched != resource) SnifferRepository.add(enriched)

        deepSearch(context.request, text)
    }

    private fun decodeContent(bytes: ByteArray, encoding: String?): ByteArray? = runCatching {
        when (encoding?.lowercase()?.trim()) {
            null, "", "identity" -> bytes
            "gzip", "x-gzip" -> GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
            "deflate" -> InflaterInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
            "br" -> BrotliInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
            else -> bytes
        }
    }.getOrNull()

    private fun isDeepSearchTextMime(mime: String?): Boolean {
        val normalized = mime?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        return normalized.startsWith("text/") ||
            normalized == "application/json" ||
            normalized.endsWith("+json") ||
            normalized == "application/javascript" ||
            normalized == "application/x-javascript" ||
            normalized == "application/xml" ||
            normalized.endsWith("+xml")
    }

    private fun deepSearch(request: PendingRequest, text: String) {
        if (text.isBlank()) return
        val normalized = text
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u002f", "/")
            .replace("&amp;", "&")

        val candidates = LinkedHashSet<String>()

        ABSOLUTE_URL.findAll(normalized).take(MAX_DEEP_SEARCH_RESULTS).forEach { match ->
            candidates += match.value.trimEnd(')', ']', '}', ',', ';', '\'', '"')
        }

        QUOTED_RESOURCE_PATH.findAll(normalized).take(MAX_DEEP_SEARCH_RESULTS).forEach { match ->
            val raw = match.groupValues.getOrNull(1).orEmpty()
            if (raw.isBlank()) return@forEach
            val resolved = runCatching { URI(request.url).resolve(raw).toString() }.getOrNull()
            if (!resolved.isNullOrBlank()) candidates += resolved
        }

        candidates.asSequence()
            .take(MAX_DEEP_SEARCH_RESULTS)
            .forEach { candidate ->
                publishIfResource(request.copy(url = candidate), null, null, null)
            }
    }

    private fun enrichHls(resource: Resource, manifest: String): Resource {
        var maxWidth = resource.width
        var maxHeight = resource.height
        var durationSeconds = 0.0
        var variantCount = 0
        var audioTracks = 0
        var subtitleTracks = 0
        var maxBandwidth = resource.maxBandwidth ?: 0L
        var drm = false
        var sawMediaSegments = false

        manifest.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-STREAM-INF:", true) -> {
                    variantCount++
                    val resolution = Regex("""RESOLUTION=(\d+)x(\d+)""", RegexOption.IGNORE_CASE)
                        .find(line)
                    val width = resolution?.groupValues?.getOrNull(1)?.toIntOrNull()
                    val height = resolution?.groupValues?.getOrNull(2)?.toIntOrNull()
                    val bandwidth = Regex("""(?:^|,)\s*(?:AVERAGE-)?BANDWIDTH=(\d+)""", RegexOption.IGNORE_CASE)
                        .find(line.substringAfter(':'))
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toLongOrNull()
                    if (bandwidth != null) maxBandwidth = maxOf(maxBandwidth, bandwidth)
                    if (width != null && height != null &&
                        (width.toLong() * height) > ((maxWidth ?: 0).toLong() * (maxHeight ?: 0))
                    ) {
                        maxWidth = width
                        maxHeight = height
                    }
                }

                line.startsWith("#EXT-X-MEDIA:", true) -> {
                    val attrs = line.substringAfter(':')
                    when {
                        Regex("""(?:^|,)\s*TYPE=AUDIO(?:,|$)""", RegexOption.IGNORE_CASE).containsMatchIn(attrs) -> audioTracks++
                        Regex("""(?:^|,)\s*TYPE=SUBTITLES(?:,|$)""", RegexOption.IGNORE_CASE).containsMatchIn(attrs) -> subtitleTracks++
                    }
                }

                line.startsWith("#EXT-X-KEY:", true) -> {
                    val method = Regex("""METHOD=([^,]+)""", RegexOption.IGNORE_CASE)
                        .find(line)?.groupValues?.getOrNull(1)?.trim()?.trim('"').orEmpty()
                    if (method.isNotBlank() && !method.equals("NONE", true) && !method.equals("AES-128", true)) {
                        drm = true
                    }
                    if (line.contains("KEYFORMAT", true) && !line.contains("identity", true)) drm = true
                }

                line.startsWith("#EXTINF:", true) -> {
                    sawMediaSegments = true
                    durationSeconds += line.substringAfter(':').substringBefore(',').toDoubleOrNull() ?: 0.0
                }
            }
        }

        val isLive = when {
            variantCount > 0 && !sawMediaSegments -> null
            sawMediaSegments -> !manifest.contains("#EXT-X-ENDLIST", true)
            else -> resource.isLive
        }

        return resource.copy(
            width = maxWidth,
            height = maxHeight,
            durationMs = resource.durationMs ?: durationSeconds.takeIf { it > 0.0 }?.times(1000.0)?.toLong(),
            variantCount = variantCount.takeIf { it > 0 } ?: resource.variantCount,
            audioTrackCount = audioTracks.takeIf { it > 0 } ?: resource.audioTrackCount,
            subtitleTrackCount = subtitleTracks.takeIf { it > 0 } ?: resource.subtitleTrackCount,
            maxBandwidth = maxBandwidth.takeIf { it > 0L } ?: resource.maxBandwidth,
            isLive = isLive,
            drmDetected = if (drm) true else resource.drmDetected ?: false,
        )
    }

    private fun enrichDash(resource: Resource, manifest: String): Resource {
        var maxWidth = resource.width
        var maxHeight = resource.height
        var variants = 0
        var audioTracks = 0
        var subtitleTracks = 0
        var maxBandwidth = resource.maxBandwidth ?: 0L

        Regex("""<Representation\b[^>]*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .findAll(manifest)
            .forEach { match ->
                val tag = match.value
                variants++
                val width = Regex("""\bwidth=["'](\d+)["']""", RegexOption.IGNORE_CASE)
                    .find(tag)?.groupValues?.getOrNull(1)?.toIntOrNull()
                val height = Regex("""\bheight=["'](\d+)["']""", RegexOption.IGNORE_CASE)
                    .find(tag)?.groupValues?.getOrNull(1)?.toIntOrNull()
                val bandwidth = Regex("""\bbandwidth=["'](\d+)["']""", RegexOption.IGNORE_CASE)
                    .find(tag)?.groupValues?.getOrNull(1)?.toLongOrNull()
                if (bandwidth != null) maxBandwidth = maxOf(maxBandwidth, bandwidth)
                if (width != null && height != null &&
                    (width.toLong() * height) > ((maxWidth ?: 0).toLong() * (maxHeight ?: 0))
                ) {
                    maxWidth = width
                    maxHeight = height
                }
            }

        Regex("""<AdaptationSet\b[^>]*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .findAll(manifest)
            .forEach { match ->
                val tag = match.value.lowercase()
                when {
                    tag.contains("contenttype=\"audio\"") || tag.contains("mimetype=\"audio/") ||
                        tag.contains("contenttype='audio'") || tag.contains("mimetype='audio/") -> audioTracks++
                    tag.contains("contenttype=\"text\"") || tag.contains("mimetype=\"text/") ||
                        tag.contains("application/ttml") || tag.contains("application/mp4") && tag.contains("subtitle") -> subtitleTracks++
                }
            }

        val duration = Regex("""mediaPresentationDuration=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(manifest)
            ?.groupValues
            ?.getOrNull(1)
            ?.let(::parseIsoDurationMs)

        val mpdTag = Regex("""<MPD\b[^>]*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(manifest)?.value.orEmpty()
        val isLive = when {
            Regex("""\btype=["']dynamic["']""", RegexOption.IGNORE_CASE).containsMatchIn(mpdTag) -> true
            Regex("""\btype=["']static["']""", RegexOption.IGNORE_CASE).containsMatchIn(mpdTag) -> false
            else -> resource.isLive
        }

        val drm = manifest.contains("<ContentProtection", true) &&
            (
                manifest.contains("cenc:pssh", true) ||
                manifest.contains("widevine", true) ||
                manifest.contains("playready", true) ||
                manifest.contains("urn:uuid:", true)
            )

        return resource.copy(
            width = maxWidth,
            height = maxHeight,
            durationMs = resource.durationMs ?: duration,
            variantCount = variants.takeIf { it > 0 } ?: resource.variantCount,
            audioTrackCount = audioTracks.takeIf { it > 0 } ?: resource.audioTrackCount,
            subtitleTrackCount = subtitleTracks.takeIf { it > 0 } ?: resource.subtitleTrackCount,
            maxBandwidth = maxBandwidth.takeIf { it > 0L } ?: resource.maxBandwidth,
            isLive = isLive,
            drmDetected = if (drm) true else resource.drmDetected ?: false,
        )
    }

    private fun parseIsoDurationMs(value: String): Long? {
        val match = Regex(
            """P(?:(\d+(?:\.\d+)?)D)?(?:T(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?)?""",
            RegexOption.IGNORE_CASE,
        ).matchEntire(value) ?: return null
        val days = match.groupValues[1].toDoubleOrNull() ?: 0.0
        val hours = match.groupValues[2].toDoubleOrNull() ?: 0.0
        val minutes = match.groupValues[3].toDoubleOrNull() ?: 0.0
        val seconds = match.groupValues[4].toDoubleOrNull() ?: 0.0
        val total = days * 86400.0 + hours * 3600.0 + minutes * 60.0 + seconds
        return total.takeIf { it > 0.0 }?.times(1000.0)?.toLong()
    }

    private fun publishIfResource(
        request: PendingRequest,
        mime: String?,
        contentLength: Long?,
        fileName: String?,
    ): Resource? {
        val classificationUrl = if (!fileName.isNullOrBlank()) {
            val separator = if (request.url.contains('?')) '&' else '?'
            request.url + separator + "filename=" + Uri.encode(fileName)
        } else {
            request.url
        }

        val classification = ResourceClassifier.classify(classificationUrl, mime)
        if (classification.type == ResourceType.OTHER) return null
        if (isLikelySegment(request.url, classification.type)) return null

        val uri = runCatching { Uri.parse(request.url) }.getOrNull()
        val extension = fileName
            ?.substringAfterLast('.', "")
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
            ?: ResourceClassifier.extensionFromUrl(request.url).ifBlank { null }

        val resource = Resource(
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
        SnifferRepository.add(resource)
        return resource
    }

    private fun isLikelySegment(url: String, type: ResourceType): Boolean {
        if (type != ResourceType.VIDEO && type != ResourceType.AUDIO) return false
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val path = uri.path.orEmpty().lowercase()
        val file = uri.lastPathSegment.orEmpty().lowercase()
        val ext = ResourceClassifier.extensionFromUrl(url)

        if (ext in setOf("m4s", "cmfv", "cmfa")) return true
        if (ext == "ts") {
            val stem = file.substringBeforeLast('.', file)
            if (stem.all { it.isDigit() } || path.contains("/segment") || path.contains("/segments/") || path.contains("/chunk")) {
                return true
            }
        }
        if (path.contains("/segment/") || path.contains("/segments/") || path.contains("/chunk/") || path.contains("/fragments/")) {
            return true
        }
        return false
    }

    private fun headerValue(lines: List<String>, name: String): String? =
        lines.firstOrNull { it.startsWith(name + ":", ignoreCase = true) }
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

    private class WebSocketFrameParser(
        private val onText: (String) -> Unit,
    ) {
        private var buffer = ByteArray(0)
        private var fragmentedText = ByteArrayOutputStream()
        private var fragmented = false

        fun feed(bytes: ByteArray, length: Int) {
            if (length <= 0) return
            val incoming = bytes.copyOfRange(0, length)
            buffer = if (buffer.isEmpty()) incoming else buffer + incoming
            consume()
        }

        private fun consume() {
            while (true) {
                if (buffer.size < 2) return
                val b0 = buffer[0].toInt() and 0xff
                val b1 = buffer[1].toInt() and 0xff
                val fin = (b0 and 0x80) != 0
                val opcode = b0 and 0x0f
                val masked = (b1 and 0x80) != 0
                var offset = 2
                var payloadLength = (b1 and 0x7f).toLong()

                if (payloadLength == 126L) {
                    if (buffer.size < offset + 2) return
                    payloadLength = ((buffer[offset].toInt() and 0xff) shl 8 or
                        (buffer[offset + 1].toInt() and 0xff)).toLong()
                    offset += 2
                } else if (payloadLength == 127L) {
                    if (buffer.size < offset + 8) return
                    payloadLength = 0L
                    repeat(8) { index ->
                        payloadLength = (payloadLength shl 8) or (buffer[offset + index].toLong() and 0xffL)
                    }
                    offset += 8
                }

                if (payloadLength < 0L || payloadLength > MAX_WEBSOCKET_PAYLOAD) {
                    buffer = ByteArray(0)
                    fragmented = false
                    fragmentedText.reset()
                    return
                }

                val maskKey = if (masked) {
                    if (buffer.size < offset + 4) return
                    buffer.copyOfRange(offset, offset + 4).also { offset += 4 }
                } else null

                val total = offset.toLong() + payloadLength
                if (total > Int.MAX_VALUE || buffer.size < total.toInt()) return
                val payload = buffer.copyOfRange(offset, total.toInt())
                buffer = if (total.toInt() >= buffer.size) ByteArray(0) else buffer.copyOfRange(total.toInt(), buffer.size)

                if (maskKey != null) {
                    for (i in payload.indices) {
                        payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
                    }
                }

                when (opcode) {
                    0x1 -> {
                        if (fin) {
                            onText(payload.toString(Charsets.UTF_8))
                        } else {
                            fragmented = true
                            fragmentedText.reset()
                            fragmentedText.write(payload)
                        }
                    }
                    0x0 -> if (fragmented) {
                        if (fragmentedText.size() + payload.size <= MAX_WEBSOCKET_PAYLOAD.toInt()) {
                            fragmentedText.write(payload)
                            if (fin) {
                                onText(fragmentedText.toByteArray().toString(Charsets.UTF_8))
                                fragmented = false
                                fragmentedText.reset()
                            }
                        } else {
                            fragmented = false
                            fragmentedText.reset()
                        }
                    }
                    0x8 -> {
                        fragmented = false
                        fragmentedText.reset()
                    }
                }
            }
        }
    }
    private class ByteQueue {
        private var data = ByteArray(0)

        val size: Int get() = data.size

        fun append(bytes: ByteArray, length: Int) {
            if (length <= 0) return
            val incoming = bytes.copyOfRange(0, length)
            data = if (data.isEmpty()) incoming else data + incoming
        }

        fun indexOf(needle: ByteArray): Int {
            if (needle.isEmpty() || data.size < needle.size) return -1
            outer@ for (i in 0..data.size - needle.size) {
                for (j in needle.indices) {
                    if (data[i + j] != needle[j]) continue@outer
                }
                return i
            }
            return -1
        }

        fun startsWith(prefix: ByteArray): Boolean {
            if (data.size < prefix.size) return false
            return prefix.indices.all { data[it] == prefix[it] }
        }

        fun take(count: Int): ByteArray {
            val n = count.coerceIn(0, data.size)
            val out = data.copyOfRange(0, n)
            drop(n)
            return out
        }

        fun peek(count: Int): ByteArray {
            val n = count.coerceIn(0, data.size)
            return data.copyOfRange(0, n)
        }

        fun drop(count: Int) {
            val n = count.coerceIn(0, data.size)
            data = if (n >= data.size) ByteArray(0) else data.copyOfRange(n, data.size)
        }

        fun trimTo(maxBytes: Int) {
            if (data.size > maxBytes) data = data.copyOfRange(data.size - maxBytes, data.size)
        }

        fun clear() {
            data = ByteArray(0)
        }
    }

    companion object {
        private const val MAX_HEADER_BUFFER = 128 * 1024
        private const val MAX_PENDING = 128
        private const val MAX_INSPECT_BODY_BYTES = 4 * 1024 * 1024
        private const val MAX_INSPECT_BODY_CHARS = 4 * 1024 * 1024
        private const val MAX_DEEP_SEARCH_RESULTS = 256
        private const val MAX_WEBSOCKET_PAYLOAD = 2L * 1024 * 1024
        private val HEADER_END = "\r\n\r\n".toByteArray(Charsets.US_ASCII)
        private val CRLF = "\r\n".toByteArray(Charsets.US_ASCII)
        private val METHODS = setOf("GET", "POST", "HEAD", "PUT", "DELETE", "OPTIONS", "PATCH")
        private val ABSOLUTE_URL = Regex("""https?://[^\s"'<>\\]+""", RegexOption.IGNORE_CASE)
        private val QUOTED_RESOURCE_PATH = Regex(
            """["\']([^"\']+\.(?:m3u8|mpd|mp4|m4v|webm|mkv|mov|avi|m4a|mp3|aac|flac|ogg|opus|wav|jpg|jpeg|png|webp|gif|avif|bmp|svg|heic|heif|pdf|epub|doc|docx|docm|dot|dotx|xls|xlsx|xlsm|xlsb|ppt|pptx|pptm|pps|ppsx|odt|ods|odp|pages|numbers|key|txt|csv|tsv|rtf|md|zip|rar|7z|tar|gz)(?:\?[^"\']*)?)["\']""",
            RegexOption.IGNORE_CASE,
        )
    }
}

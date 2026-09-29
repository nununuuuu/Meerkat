package com.resourcesniffer.app.core

import android.graphics.BitmapFactory
import android.net.Uri
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

object MediaIdentity {
    private val authKeys = setOf(
        "token", "sig", "signature", "expires", "expiry", "auth", "auth_key",
        "policy", "key-pair-id", "x-amz-signature", "x-amz-credential",
        "x-amz-date", "x-amz-expires", "x-amz-security-token",
    )
    private val transformKeys = setOf(
        "w", "width", "h", "height", "q", "quality", "size",
        "resize", "crop", "fit", "dpr",
    )

    fun exactKey(url: String?): String? = normalize(url, false)
    fun groupKey(url: String?): String? = normalize(url, true)

    private fun normalize(url: String?, removeTransforms: Boolean): String? {
        if (url.isNullOrBlank()) return url
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return url
        val builder = uri.buildUpon().clearQuery().fragment(null)
        val names = runCatching { uri.queryParameterNames }.getOrDefault(emptySet())
        names.filterNot { key ->
            val k = key.lowercase()
            k in authKeys || k.startsWith("utm_") || (removeTransforms && k in transformKeys)
        }.sorted().forEach { key ->
            uri.getQueryParameters(key).forEach { value -> builder.appendQueryParameter(key, value) }
        }
        return builder.build().toString()
    }
}

object ResourceValidator {
    private val executor = Executors.newFixedThreadPool(3)
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    fun validate(resource: Resource, onResult: (Resource) -> Unit) {
        val rawUrl = resource.url ?: return
        if (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://")) return
        val key = resource.id.toString() + "|" + rawUrl
        if (!inFlight.add(key)) return
        executor.execute {
            try { onResult(validateNow(resource)) } finally { inFlight.remove(key) }
        }
    }

    private fun validateNow(resource: Resource): Resource {
        return runCatching {
            val head = open(resource, "HEAD")
            var finalUrl = head.url?.toString() ?: resource.url
            var mime = head.contentType?.substringBefore(';')?.trim() ?: resource.mimeType
            var length = head.getHeaderFieldLong("Content-Length", -1L).takeIf { it >= 0 } ?: resource.contentLength
            var etag = head.getHeaderField("ETag") ?: resource.etag
            var fileName = parseDispositionFileName(head.getHeaderField("Content-Disposition")) ?: resource.fileName
            val headCode = head.responseCode
            head.disconnect()

            var width = resource.width
            var height = resource.height

            fun classificationUrl(): String {
                val base = finalUrl ?: resource.url.orEmpty()
                return if (!fileName.isNullOrBlank()) {
                    base + (if (base.contains('?')) "&" else "?") + "filename=" + Uri.encode(fileName)
                } else base
            }

            var classification = ResourceClassifier.classify(classificationUrl(), mime)
            val needsProbe =
                headCode !in 200..399 ||
                    mime.isNullOrBlank() ||
                    mime.equals("application/octet-stream", true) ||
                    length == null ||
                    fileName == null ||
                    classification.type == ResourceType.IMAGE

            var sniffedType: ResourceType? = null
            var sniffedMime: String? = null
            var sniffedExtension: String? = null

            if (needsProbe) {
                val isUnknownBinary = classification.type == ResourceType.OTHER ||
                    mime.equals("application/octet-stream", true)
                val range = when {
                    classification.type == ResourceType.IMAGE -> "bytes=0-262143"
                    isUnknownBinary -> "bytes=0-65535"
                    else -> "bytes=0-0"
                }
                val get = open(resource.copy(url = finalUrl), "GET", range)
                if (get.responseCode in 200..299 || get.responseCode == HttpURLConnection.HTTP_PARTIAL) {
                    finalUrl = get.url?.toString() ?: finalUrl
                    mime = get.contentType?.substringBefore(';')?.trim() ?: mime
                    fileName = parseDispositionFileName(get.getHeaderField("Content-Disposition")) ?: fileName
                    etag = get.getHeaderField("ETag") ?: etag

                    val rangeTotal = get.getHeaderField("Content-Range")
                        ?.substringAfterLast('/')
                        ?.toLongOrNull()
                    val responseLength = get.getHeaderFieldLong("Content-Length", -1L)
                        .takeIf { it >= 0 }
                    length = when {
                        rangeTotal != null -> rangeTotal
                        get.responseCode == HttpURLConnection.HTTP_OK -> responseLength ?: length
                        else -> length
                    }

                    when {
                        classification.type == ResourceType.IMAGE -> {
                            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            get.inputStream.use { BitmapFactory.decodeStream(it, null, options) }
                            if (options.outWidth > 0 && options.outHeight > 0) {
                                width = options.outWidth
                                height = options.outHeight
                            }
                        }

                        isUnknownBinary -> {
                            val prefix = get.inputStream.use { input ->
                                val out = ByteArray(65_536)
                                var offset = 0
                                while (offset < out.size) {
                                    val read = input.read(out, offset, out.size - offset)
                                    if (read <= 0) break
                                    offset += read
                                }
                                out.copyOf(offset)
                            }
                            sniffSignature(prefix)?.let { sniff ->
                                sniffedType = sniff.type
                                sniffedMime = sniff.mime
                                sniffedExtension = sniff.extension
                                if (mime.isNullOrBlank() || mime.equals("application/octet-stream", true)) {
                                    mime = sniff.mime
                                }
                            }
                        }

                        else -> runCatching { get.inputStream.close() }
                    }
                }
                get.disconnect()
            }

            classification = ResourceClassifier.classify(classificationUrl(), mime)
            if (classification.type == ResourceType.OTHER && sniffedType != null) {
                classification = ResourceClassifier.Classification(sniffedType!!, null)
            }
            val parsed = runCatching { Uri.parse(finalUrl) }.getOrNull()
            val extension = fileName
                ?.substringAfterLast('.', "")
                ?.lowercase()
                ?.takeIf { it.isNotBlank() }
                ?: ResourceClassifier.extensionFromUrl(finalUrl).takeIf { it.isNotBlank() }
                ?: sniffedExtension
                ?: resource.extension

            resource.copy(
                finalUrl = finalUrl,
                host = parsed?.host ?: resource.host,
                mimeType = mime,
                extension = extension,
                fileName = fileName,
                contentLength = length,
                type = classification.type,
                streamType = classification.streamType,
                width = width,
                height = height,
                etag = etag,
                mediaGroupKey = MediaIdentity.groupKey(finalUrl),
                validationState = ValidationState.VERIFIED,
                verifiedAt = System.currentTimeMillis(),
            )
        }.getOrElse {
            resource.copy(
                mediaGroupKey = resource.mediaGroupKey ?: MediaIdentity.groupKey(resource.url),
                validationState = ValidationState.FAILED,
                verifiedAt = System.currentTimeMillis(),
            )
        }
    }

    private data class Signature(
        val type: ResourceType,
        val mime: String,
        val extension: String?,
    )

    private fun sniffSignature(bytes: ByteArray): Signature? {
        if (bytes.isEmpty()) return null

        fun starts(vararg values: Int): Boolean =
            bytes.size >= values.size && values.indices.all { index ->
                (bytes[index].toInt() and 0xff) == values[index]
            }

        if (bytes.size >= 5 && bytes.copyOfRange(0, 5).toString(Charsets.US_ASCII) == "%PDF-") {
            return Signature(ResourceType.DOCUMENT, "application/pdf", "pdf")
        }
        if (starts(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
            return Signature(ResourceType.IMAGE, "image/png", "png")
        }
        if (starts(0xFF, 0xD8, 0xFF)) {
            return Signature(ResourceType.IMAGE, "image/jpeg", "jpg")
        }
        if (bytes.size >= 6) {
            val sig = bytes.copyOfRange(0, 6).toString(Charsets.US_ASCII)
            if (sig == "GIF87a" || sig == "GIF89a") {
                return Signature(ResourceType.IMAGE, "image/gif", "gif")
            }
        }
        if (bytes.size >= 12 &&
            bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" &&
            bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP"
        ) {
            return Signature(ResourceType.IMAGE, "image/webp", "webp")
        }
        if (bytes.size >= 12 && bytes.copyOfRange(4, 8).toString(Charsets.US_ASCII) == "ftyp") {
            return Signature(ResourceType.VIDEO, "video/mp4", "mp4")
        }
        if (starts(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07)) {
            return Signature(ResourceType.ARCHIVE, "application/vnd.rar", "rar")
        }
        if (starts(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C)) {
            return Signature(ResourceType.ARCHIVE, "application/x-7z-compressed", "7z")
        }
        if (starts(0x1F, 0x8B)) {
            return Signature(ResourceType.ARCHIVE, "application/gzip", "gz")
        }
        if (starts(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1)) {
            return Signature(ResourceType.DOCUMENT, "application/x-ole-storage", null)
        }
        if (starts(0x50, 0x4B, 0x03, 0x04)) {
            val text = bytes.toString(Charsets.ISO_8859_1)
            return when {
                text.contains("word/") ->
                    Signature(
                        ResourceType.DOCUMENT,
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        "docx",
                    )
                text.contains("ppt/") ->
                    Signature(
                        ResourceType.DOCUMENT,
                        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                        "pptx",
                    )
                text.contains("xl/") ->
                    Signature(
                        ResourceType.DOCUMENT,
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "xlsx",
                    )
                text.contains("mimetypeapplication/epub+zip") ->
                    Signature(ResourceType.DOCUMENT, "application/epub+zip", "epub")
                else -> Signature(ResourceType.ARCHIVE, "application/zip", "zip")
            }
        }
        return null
    }

    private fun parseDispositionFileName(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val encoded = Regex("""filename\*=UTF-8''([^;]+)""", RegexOption.IGNORE_CASE)
            .find(value)
            ?.groupValues
            ?.getOrNull(1)
        if (!encoded.isNullOrBlank()) {
            return runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrDefault(encoded)
        }
        return Regex("""filename="?([^";]+)"?""", RegexOption.IGNORE_CASE)
            .find(value)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
    }

    private fun open(resource: Resource, method: String, range: String? = null): HttpURLConnection {
        val connection = (URL(resource.url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = true
            connectTimeout = 10_000
            readTimeout = 15_000
            useCaches = false
            setRequestProperty("Accept", "*/*")
            resource.userAgent?.takeIf { it.isNotBlank() }?.let { setRequestProperty("User-Agent", it) }
            resource.referer?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Referer", it) }
            resource.cookie?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Cookie", it) }
            range?.let { setRequestProperty("Range", it) }
        }
        connection.connect()
        return connection
    }
}

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

            if (needsProbe) {
                val range = if (classification.type == ResourceType.IMAGE) "bytes=0-262143" else "bytes=0-0"
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

                    if (classification.type == ResourceType.IMAGE) {
                        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        get.inputStream.use { BitmapFactory.decodeStream(it, null, options) }
                        if (options.outWidth > 0 && options.outHeight > 0) {
                            width = options.outWidth
                            height = options.outHeight
                        }
                    } else {
                        runCatching { get.inputStream.close() }
                    }
                }
                get.disconnect()
            }

            classification = ResourceClassifier.classify(classificationUrl(), mime)
            val parsed = runCatching { Uri.parse(finalUrl) }.getOrNull()
            val extension = fileName
                ?.substringAfterLast('.', "")
                ?.lowercase()
                ?.takeIf { it.isNotBlank() }
                ?: ResourceClassifier.extensionFromUrl(finalUrl).takeIf { it.isNotBlank() }
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

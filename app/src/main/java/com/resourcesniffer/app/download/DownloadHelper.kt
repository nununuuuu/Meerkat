package com.resourcesniffer.app.download

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.content.ContentValues
import java.net.HttpURLConnection
import java.net.URL
import android.webkit.CookieManager
import android.webkit.URLUtil
import androidx.core.content.ContextCompat
import com.resourcesniffer.app.core.Resource
import com.resourcesniffer.app.core.ResourceType
import com.resourcesniffer.app.core.StreamType
import java.util.UUID

object DownloadHelper {

    fun enqueue(
        context: Context,
        resource: Resource,
        quality: DownloadQuality = DownloadQuality.HIGH,
    ) {
        val url = resource.url ?: error("缺少資源網址")
        val cookie = resource.cookie ?: CookieManager.getInstance().getCookie(url)
        val record = DownloadRecord(
            id = UUID.randomUUID().toString(),
            url = url,
            displayName = URLUtil.guessFileName(url, null, resource.mimeType)
                .ifBlank { "meerkat-resource-${System.currentTimeMillis()}" },
            mimeType = resource.mimeType,
            streamType = resource.streamType,
            cookie = cookie,
            referer = resource.referer,
            userAgent = resource.userAgent,
            quality = quality,
            state = DownloadState.QUEUED,
            detail = "等待下載",
        )
        enqueueRecord(context, record)
    }

    fun retry(context: Context, record: DownloadRecord) {
        val retried = record.copy(
            id = UUID.randomUUID().toString(),
            state = DownloadState.QUEUED,
            progress = null,
            detail = "等待重新下載",
            createdAt = System.currentTimeMillis(),
        )
        enqueueRecord(context, retried)
    }

    fun cancel(context: Context, record: DownloadRecord) {
        if (DirectDownloadTracker.cancel(context, record.id)) return
        DownloadRegistry.cancel(record.id)
    }

    private fun enqueueRecord(context: Context, record: DownloadRecord) {
        DownloadRegistry.add(record)

        val isHls = record.streamType == StreamType.HLS ||
            record.url.substringBefore('?').endsWith(".m3u8", true)
        val isDash = record.streamType == StreamType.DASH ||
            record.url.substringBefore('?').endsWith(".mpd", true)

        when {
            record.mimeType?.startsWith("image/") == true -> enqueueImage(context, record)
            isHls -> {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, HlsDownloadService::class.java).apply {
                        putExtra(HlsDownloadService.EXTRA_RECORD_ID, record.id)
                        putExtra(HlsDownloadService.EXTRA_URL, record.url)
                        putExtra(HlsDownloadService.EXTRA_COOKIE, record.cookie)
                        putExtra(HlsDownloadService.EXTRA_REFERER, record.referer)
                        putExtra(HlsDownloadService.EXTRA_USER_AGENT, record.userAgent)
                    putExtra(HlsDownloadService.EXTRA_QUALITY, record.quality.name)
                    }
                )
            }

            isDash -> {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, DashDownloadService::class.java).apply {
                        putExtra(DashDownloadService.EXTRA_RECORD_ID, record.id)
                        putExtra(DashDownloadService.EXTRA_URL, record.url)
                        putExtra(DashDownloadService.EXTRA_COOKIE, record.cookie)
                        putExtra(DashDownloadService.EXTRA_REFERER, record.referer)
                        putExtra(DashDownloadService.EXTRA_USER_AGENT, record.userAgent)
                    putExtra(DashDownloadService.EXTRA_QUALITY, record.quality.name)
                    }
                )
            }

            else -> enqueueDirect(context, record)
        }
    }

    private fun enqueueImage(context: Context, record: DownloadRecord) {
        DownloadRegistry.update(record.id) {
            it.copy(state = DownloadState.DOWNLOADING, detail = "下載圖片中")
        }

        Thread {
            var outputUri: Uri? = null
            try {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, record.displayName)
                    put(MediaStore.Images.Media.MIME_TYPE, record.mimeType ?: "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Meerkat")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                outputUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: error("無法建立圖片檔案")

                val connection = (URL(record.url).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    setRequestProperty("Accept", "image/*,*/*;q=0.8")
                    record.cookie?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Cookie", it) }
                    record.userAgent?.takeIf { it.isNotBlank() }?.let { setRequestProperty("User-Agent", it) }
                    record.referer?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Referer", it) }
                }
                connection.connect()
                if (connection.responseCode !in 200..299) {
                    error("HTTP ${connection.responseCode}")
                }

                resolver.openOutputStream(outputUri, "w")!!.use { out ->
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var read: Int
                        var total = 0L
                        val length = connection.contentLengthLong.takeIf { it > 0 }
                        while (input.read(buffer).also { read = it } >= 0) {
                            if (read == 0) continue
                            out.write(buffer, 0, read)
                            total += read
                            length?.let { expected ->
                                val progress = ((total * 100L) / expected).toInt().coerceIn(0, 100)
                                DownloadRegistry.update(record.id) { current ->
                                    current.copy(progress = progress, detail = "下載圖片中")
                                }
                            }
                        }
                        out.flush()
                    }
                }

                resolver.update(
                    outputUri,
                    ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                    null,
                    null,
                )
                DownloadRegistry.update(record.id) {
                    it.copy(
                        state = DownloadState.COMPLETED,
                        progress = 100,
                        detail = "下載完成",
                        localUri = outputUri.toString(),
                    )
                }
            } catch (t: Throwable) {
                outputUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
                DownloadRegistry.update(record.id) {
                    it.copy(
                        state = DownloadState.FAILED,
                        detail = "圖片下載失敗：${t.message ?: "未知錯誤"}",
                    )
                }
            }
        }.start()
    }
    private fun enqueueDirect(context: Context, record: DownloadRecord) {
        val request = DownloadManager.Request(Uri.parse(record.url))
            .setTitle(record.displayName)
            .setDescription("Meerkat 資源下載")
            .setMimeType(record.mimeType)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, record.displayName)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        record.cookie?.takeIf { it.isNotBlank() }?.let { request.addRequestHeader("Cookie", it) }
        record.userAgent?.takeIf { it.isNotBlank() }?.let { request.addRequestHeader("User-Agent", it) }
        record.referer?.takeIf { it.isNotBlank() }?.let { request.addRequestHeader("Referer", it) }

        val manager = context.getSystemService(DownloadManager::class.java)
        val systemId = manager.enqueue(request)
        DirectDownloadTracker.track(systemId, record.id)
        DownloadRegistry.update(record.id) {
            it.copy(state = DownloadState.DOWNLOADING, detail = "下載中")
        }
    }

    fun asResource(record: DownloadRecord): Resource =
        Resource(
            id = 0L,
            sessionId = 0L,
            sourceAppPackage = null,
            sourceAppName = null,
            url = record.url,
            host = Uri.parse(record.url).host ?: "未知來源",
            mimeType = record.mimeType,
            extension = null,
            contentLength = null,
            type = when {
                record.mimeType?.startsWith("image/") == true -> ResourceType.IMAGE
                record.mimeType?.startsWith("video/") == true -> ResourceType.VIDEO
                record.mimeType?.startsWith("audio/") == true -> ResourceType.AUDIO
                record.streamType != null -> ResourceType.STREAM
                else -> ResourceType.OTHER
            },
            streamType = record.streamType,
            referer = record.referer,
            userAgent = record.userAgent,
            cookie = record.cookie,
        )
}

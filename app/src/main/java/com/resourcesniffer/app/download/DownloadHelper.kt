package com.resourcesniffer.app.download

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
        if (resource.drmDetected == true) error("此串流偵測到 DRM/受保護內容，Meerkat 不會嘗試解密")
        val url = resource.finalUrl ?: resource.url ?: error("缺少資源網址")
        val cookie = resource.cookie ?: CookieManager.getInstance().getCookie(url)
        val record = DownloadRecord(
            id = UUID.randomUUID().toString(),
            url = url,
            displayName = resource.fileName
                ?.takeIf { it.isNotBlank() }
                ?: URLUtil.guessFileName(url, null, resource.mimeType)
                    .ifBlank { "meerkat-resource-${System.currentTimeMillis()}" },
            mimeType = resource.mimeType,
            streamType = resource.streamType,
            cookie = cookie,
            referer = resource.referer,
            userAgent = resource.userAgent,
            localSourcePath = resource.localCachePath,
            expectedLength = resource.contentLength,
            etag = resource.etag,
            quality = quality,
            state = DownloadState.QUEUED,
            detail = "等待下載",
        )
        enqueueRecord(context, record)
    }

    fun retry(context: Context, record: DownloadRecord) {
        val retried = record.copy(
            state = DownloadState.QUEUED,
            progress = null,
            detail = "等待重新下載",
            createdAt = System.currentTimeMillis(),
        )
        DownloadRegistry.add(retried)
        enqueueExistingRecord(context, retried)
    }

    fun cancel(context: Context, record: DownloadRecord) {
        DownloadRegistry.cancel(record.id)
    }

    private fun enqueueRecord(context: Context, record: DownloadRecord) {
        DownloadRegistry.add(record)
        enqueueExistingRecord(context, record)
    }

    private fun enqueueExistingRecord(context: Context, record: DownloadRecord) {
        val isHls = record.streamType == StreamType.HLS ||
            record.url.substringBefore('?').endsWith(".m3u8", true)
        val isDash = record.streamType == StreamType.DASH ||
            record.url.substringBefore('?').endsWith(".mpd", true)

        when {
            !record.localSourcePath.isNullOrBlank() &&
                java.io.File(record.localSourcePath).isFile -> enqueueLocalCopy(context, record)
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

    private fun enqueueLocalCopy(context: Context, record: DownloadRecord) {
        DownloadRegistry.update(record.id) {
            it.copy(state = DownloadState.DOWNLOADING, progress = 0, detail = "正在保存本機資源")
        }
        Thread {
            var outputUri: Uri? = null
            try {
                val source = java.io.File(record.localSourcePath ?: error("缺少本機資源"))
                require(source.isFile) { "本機暫存資源已不存在" }
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, record.displayName)
                    put(MediaStore.Downloads.MIME_TYPE, record.mimeType ?: "application/octet-stream")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Meerkat")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                outputUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("無法建立下載檔案")
                resolver.openOutputStream(outputUri, "w")?.use { output ->
                    source.inputStream().use { input ->
                        val total = source.length().coerceAtLeast(1L)
                        val buffer = ByteArray(128 * 1024)
                        var copied = 0L
                        while (true) {
                            if (DownloadRegistry.find(record.id)?.state == DownloadState.CANCELLED) error("下載已取消")
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            output.write(buffer, 0, read)
                            copied += read
                            val progress = ((copied * 100L) / total).toInt().coerceIn(0, 100)
                            DownloadRegistry.update(record.id) { current ->
                                current.copy(progress = progress, detail = "正在保存本機資源")
                            }
                        }
                    }
                } ?: error("無法寫入下載檔案")
                resolver.update(
                    outputUri,
                    ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
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
            } catch (error: Throwable) {
                outputUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
                DownloadRegistry.update(record.id) { current ->
                    if (current.state == DownloadState.CANCELLED) current
                    else current.copy(state = DownloadState.FAILED, detail = error.message ?: "保存失敗")
                }
            }
        }.start()
    }
    private fun enqueueDirect(context: Context, record: DownloadRecord) {
        ContextCompat.startForegroundService(
            context,
            Intent(context, DirectHttpDownloadService::class.java).apply {
                putExtra(DirectHttpDownloadService.EXTRA_RECORD_ID, record.id)
            }
        )
        DownloadRegistry.update(record.id) {
            it.copy(state = DownloadState.DOWNLOADING, detail = "準備下載")
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
            fileName = record.displayName,
            localCachePath = record.localSourcePath,
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

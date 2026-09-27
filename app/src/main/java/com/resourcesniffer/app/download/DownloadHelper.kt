package com.resourcesniffer.app.download

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.URLUtil
import androidx.core.content.ContextCompat
import com.resourcesniffer.app.core.Resource
import com.resourcesniffer.app.core.StreamType
import java.util.UUID

object DownloadHelper {

    fun enqueue(context: Context, resource: Resource) {
        val url = resource.url ?: error("缺少資源網址")
        val recordId = UUID.randomUUID().toString()
        val displayName = URLUtil.guessFileName(url, null, resource.mimeType)
            .ifBlank { "meerkat-resource-${System.currentTimeMillis()}" }

        DownloadRegistry.add(
            DownloadRecord(
                id = recordId,
                url = url,
                displayName = displayName,
                mimeType = resource.mimeType,
                state = DownloadState.QUEUED,
                detail = "等待下載",
            )
        )

        if (resource.streamType == StreamType.HLS || url.substringBefore('?').endsWith(".m3u8", true)) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, HlsDownloadService::class.java).apply {
                    putExtra(HlsDownloadService.EXTRA_RECORD_ID, recordId)
                    putExtra(HlsDownloadService.EXTRA_URL, url)
                    putExtra(HlsDownloadService.EXTRA_COOKIE, resource.cookie ?: CookieManager.getInstance().getCookie(url))
                    putExtra(HlsDownloadService.EXTRA_REFERER, resource.referer)
                    putExtra(HlsDownloadService.EXTRA_USER_AGENT, resource.userAgent)
                }
            )
            return
        }

        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(displayName)
            .setDescription("Meerkat 資源下載")
            .setMimeType(resource.mimeType)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, displayName)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val cookie = resource.cookie ?: CookieManager.getInstance().getCookie(url)
        cookie?.takeIf { it.isNotBlank() }?.let { request.addRequestHeader("Cookie", it) }
        resource.userAgent?.takeIf { it.isNotBlank() }?.let { request.addRequestHeader("User-Agent", it) }
        resource.referer?.takeIf { it.isNotBlank() }?.let { request.addRequestHeader("Referer", it) }

        val manager = context.getSystemService(DownloadManager::class.java)
        val systemId = manager.enqueue(request)
        DirectDownloadTracker.track(systemId, recordId)
        DownloadRegistry.update(recordId) { it.copy(state = DownloadState.DOWNLOADING, detail = "下載中") }
    }
}

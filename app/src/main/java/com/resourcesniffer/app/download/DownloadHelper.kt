package com.resourcesniffer.app.download

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.URLUtil

object DownloadHelper {
    fun enqueue(context: Context, url: String, userAgent: String? = null) {
        val uri = Uri.parse(url)
        val fileName = URLUtil.guessFileName(url, null, null)
            .ifBlank { "meerkat-resource-${System.currentTimeMillis()}" }

        val request = DownloadManager.Request(uri)
            .setTitle(fileName)
            .setDescription("Meerkat 資源下載")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }?.let {
            request.addRequestHeader("Cookie", it)
        }
        userAgent?.takeIf { it.isNotBlank() }?.let {
            request.addRequestHeader("User-Agent", it)
        }

        val manager = context.getSystemService(DownloadManager::class.java)
        manager.enqueue(request)
    }
}

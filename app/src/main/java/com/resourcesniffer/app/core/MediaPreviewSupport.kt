package com.resourcesniffer.app.core

import java.net.URI
import java.net.URL
import java.net.HttpURLConnection

object MediaPreviewSupport {
    /** CDN byte-range query parameters otherwise force a fragment even when the player requests byte zero. */
    fun playbackUrl(raw: String): String = runCatching {
        val uri = URI(raw)
        val host = uri.host.orEmpty().lowercase()
        if (host != "fbcdn.net" && !host.endsWith(".fbcdn.net")) return raw
        val query = uri.rawQuery ?: return raw
        val parts = query.split('&')
        val kept = parts.filterNot { it.substringBefore('=').lowercase() in setOf("bytestart", "byteend") }
        if (kept.size == parts.size) raw else raw.substringBefore('?') +
            (if (kept.isEmpty()) "" else "?" + kept.joinToString("&")) +
            (uri.rawFragment?.let { "#$it" } ?: "")
    }.getOrDefault(raw)

    fun diagnose(url: String, headers: Map<String, String>): String = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            headers.forEach { (key, value) -> conn.setRequestProperty(key, value) }
            conn.setRequestProperty("Range", "bytes=0-4095")
            val code = conn.responseCode
            if (code !in 200..299) return "媒體要求被伺服器拒絕（HTTP $code）。請重新載入來源頁面取得新連結。"
            val bytes = conn.inputStream.use { input ->
                val buffer = ByteArray(4096)
                var size = 0
                while (size < buffer.size) {
                    val n = input.read(buffer, size, buffer.size - size)
                    if (n <= 0) break
                    size += n
                }
                buffer.copyOf(size)
            }
            val text = bytes.toString(Charsets.ISO_8859_1).trimStart()
            val box = if (bytes.size >= 8) bytes.copyOfRange(4, 8).toString(Charsets.US_ASCII) else ""
            when {
                conn.contentType.orEmpty().contains("text/html", true) || text.startsWith("<!DOCTYPE", true) || text.startsWith("<html", true) ->
                    "此網址回傳網頁而非影片，可能是登入頁或連結已失效。請重新載入來源頁面。"
                box == "moof" || box == "styp" ->
                    "此網址回傳影片分段，無法單獨預覽。請選擇同頁的完整 MP4、M3U8 或 MPD。"
                bytes.isEmpty() -> "媒體伺服器回傳空內容，請重新載入來源頁面。"
                else -> "已取得媒體回應（HTTP $code，${conn.contentType.orEmpty().take(60)}），但播放器無法解析；可能是缺少初始化資料的分段或不支援的容器。"
            }
        } finally { conn.disconnect() }
    }.getOrElse { "無法解析媒體，也無法重新檢查連結：${it.javaClass.simpleName}。請重新載入來源頁面。" }
}

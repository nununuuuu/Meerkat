package com.resourcesniffer.app.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.Executors
import kotlin.math.min

class DirectHttpDownloadService : Service() {

    companion object {
        private const val CHANNEL_ID = "direct_http_download"
        private const val MAX_ATTEMPTS = 3
        const val EXTRA_RECORD_ID = "record_id"
    }

    private val executor = Executors.newFixedThreadPool(2)

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "一般檔案下載",
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val recordId = intent?.getStringExtra(EXTRA_RECORD_ID) ?: run {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val record = DownloadRegistry.find(recordId) ?: run {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val notificationId = 3000 + (recordId.hashCode() and 0x3fff)
        startForeground(
            notificationId,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(record.displayName)
                .setContentText("正在下載")
                .setOngoing(true)
                .setProgress(0, 0, true)
                .build()
        )

        executor.execute {
            val result = runCatching { download(recordId) }
            result.onSuccess { localUri ->
                DownloadRegistry.update(recordId) { current ->
                    current.copy(
                        state = DownloadState.COMPLETED,
                        progress = 100,
                        detail = "下載完成",
                        localUri = localUri,
                    )
                }
                notifyFinished(notificationId, record.displayName, "下載完成")
            }.onFailure { error ->
                DownloadRegistry.update(recordId) { current ->
                    if (current.state == DownloadState.CANCELLED) current
                    else current.copy(
                        state = DownloadState.FAILED,
                        detail = error.message ?: "下載失敗",
                    )
                }
                if (DownloadRegistry.find(recordId)?.state != DownloadState.CANCELLED) {
                    notifyFinished(notificationId, record.displayName, "下載失敗")
                }
            }
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf(startId)
        }

        return START_NOT_STICKY
    }

    private fun download(recordId: String): String {
        val initial = DownloadRegistry.find(recordId) ?: error("找不到下載工作")
        val dir = File(cacheDir, "direct-downloads").apply { mkdirs() }
        val temp = File(dir, recordId + ".part")

        var lastError: Throwable? = null
        for (attempt in 1..MAX_ATTEMPTS) {
            ensureActive(recordId)
            try {
                return downloadAttempt(recordId, initial, temp, attempt)
            } catch (error: Throwable) {
                if (DownloadRegistry.find(recordId)?.state == DownloadState.CANCELLED) throw error
                lastError = error
                if (attempt < MAX_ATTEMPTS) {
                    DownloadRegistry.update(recordId) { current ->
                        current.copy(detail = "連線中斷，準備重試 " + (attempt + 1) + "/" + MAX_ATTEMPTS)
                    }
                    Thread.sleep(800L * attempt)
                }
            }
        }
        throw lastError ?: IllegalStateException("下載失敗")
    }

    private fun downloadAttempt(
        recordId: String,
        record: DownloadRecord,
        temp: File,
        attempt: Int,
    ): String {
        var resumeFrom = temp.takeIf { it.exists() }?.length() ?: 0L
        var connection = openConnection(record, resumeFrom)
        var code = connection.responseCode

        if (code == 416) {
            val total = connection.getHeaderField("Content-Range")
                ?.substringAfterLast('/')
                ?.toLongOrNull()
            connection.disconnect()
            if (total != null && resumeFrom == total && total > 0L) {
                return publish(recordId, record, temp, null, null)
            }
            temp.delete()
            resumeFrom = 0L
            connection = openConnection(record, 0L)
            code = connection.responseCode
        }

        if (code !in 200..299) {
            connection.disconnect()
            error("HTTP " + code)
        }

        val append = resumeFrom > 0L && code == HttpURLConnection.HTTP_PARTIAL
        if (resumeFrom > 0L && !append) {
            temp.delete()
            resumeFrom = 0L
        }

        val responseMime = connection.contentType?.substringBefore(';')?.trim()
        val dispositionName = parseDispositionFileName(connection.getHeaderField("Content-Disposition"))
        val expectedTotal = when {
            code == HttpURLConnection.HTTP_PARTIAL -> connection.getHeaderField("Content-Range")
                ?.substringAfterLast('/')
                ?.toLongOrNull()
            connection.contentLengthLong > 0L -> connection.contentLengthLong
            else -> null
        }

        if (!dispositionName.isNullOrBlank() || !responseMime.isNullOrBlank()) {
            DownloadRegistry.update(recordId) { current ->
                current.copy(
                    displayName = dispositionName?.let(::sanitizeFileName) ?: current.displayName,
                    mimeType = responseMime ?: current.mimeType,
                )
            }
        }

        DownloadRegistry.update(recordId) { current ->
            current.copy(
                state = DownloadState.DOWNLOADING,
                detail = if (resumeFrom > 0L) "續傳中（第 " + attempt + " 次）" else "下載中",
            )
        }

        FileOutputStream(temp, append).use { output ->
            connection.inputStream.use { input ->
                val buffer = ByteArray(128 * 1024)
                var downloaded = resumeFrom
                while (true) {
                    ensureActive(recordId)
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    output.write(buffer, 0, read)
                    downloaded += read
                    expectedTotal?.takeIf { it > 0L }?.let { total ->
                        val progress = ((downloaded * 100L) / total)
                            .toInt()
                            .coerceIn(0, 100)
                        DownloadRegistry.update(recordId) { current ->
                            current.copy(
                                state = DownloadState.DOWNLOADING,
                                progress = progress,
                                detail = if (resumeFrom > 0L) "續傳中" else "下載中",
                            )
                        }
                    }
                }
                output.fd.sync()
            }
        }
        connection.disconnect()

        if (expectedTotal != null && expectedTotal > 0L && temp.length() != expectedTotal) {
            error("檔案長度不完整：" + temp.length() + " / " + expectedTotal)
        }

        val current = DownloadRegistry.find(recordId) ?: record
        return publish(
            recordId,
            current,
            temp,
            dispositionName,
            responseMime,
        )
    }

    private fun openConnection(record: DownloadRecord, resumeFrom: Long): HttpURLConnection {
        return (URL(record.url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 30_000
            useCaches = false
            setRequestProperty("Accept", "*/*")
            record.cookie?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Cookie", it) }
            record.userAgent?.takeIf { it.isNotBlank() }?.let { setRequestProperty("User-Agent", it) }
            record.referer?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Referer", it) }
            if (resumeFrom > 0L) setRequestProperty("Range", "bytes=" + resumeFrom + "-")
            connect()
        }
    }

    private fun publish(
        recordId: String,
        record: DownloadRecord,
        temp: File,
        dispositionName: String?,
        responseMime: String?,
    ): String {
        ensureActive(recordId)
        val latest = DownloadRegistry.find(recordId) ?: record
        val name = sanitizeFileName(
            dispositionName
                ?: latest.displayName
                .ifBlank { "Meerkat-" + System.currentTimeMillis() }
        )
        val mime = responseMime ?: latest.mimeType ?: "application/octet-stream"

        val collection = when {
            mime.startsWith("image/") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            mime.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            mime.startsWith("audio/") -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
        }
        val relativePath = when {
            mime.startsWith("image/") -> Environment.DIRECTORY_PICTURES + "/Meerkat"
            mime.startsWith("video/") -> Environment.DIRECTORY_MOVIES + "/Meerkat"
            mime.startsWith("audio/") -> Environment.DIRECTORY_MUSIC + "/Meerkat"
            else -> Environment.DIRECTORY_DOWNLOADS + "/Meerkat"
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(collection, values)
            ?: error("無法建立下載檔案")

        try {
            contentResolver.openOutputStream(uri, "w")?.use { output ->
                temp.inputStream().use { input -> input.copyTo(output, 128 * 1024) }
            } ?: error("無法寫入下載檔案")

            contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            temp.delete()

            DownloadRegistry.update(recordId) { current ->
                current.copy(displayName = name, mimeType = mime)
            }
            return uri.toString()
        } catch (error: Throwable) {
            runCatching { contentResolver.delete(uri, null, null) }
            throw error
        }
    }

    private fun ensureActive(recordId: String) {
        if (DownloadRegistry.find(recordId)?.state == DownloadState.CANCELLED) {
            error("下載已取消")
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

    private fun sanitizeFileName(value: String): String {
        val clean = value
            .replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_")
            .trim()
            .trim('.')
        return clean.take(180).ifBlank { "Meerkat-" + System.currentTimeMillis() }
    }

    private fun notifyFinished(id: Int, title: String, text: String) {
        getSystemService(NotificationManager::class.java).notify(
            id,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .build()
        )
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

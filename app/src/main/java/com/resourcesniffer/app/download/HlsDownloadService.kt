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
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class HlsDownloadService : Service() {

    companion object {
        private const val CHANNEL_ID = "hls_download"
        private const val NOTIFICATION_ID = 2101
        const val EXTRA_URL = "url"
        const val EXTRA_COOKIE = "cookie"
        const val EXTRA_REFERER = "referer"
        const val EXTRA_USER_AGENT = "user_agent"
    }

    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra(EXTRA_URL) ?: run {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val headers = buildMap {
            intent.getStringExtra(EXTRA_COOKIE)?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
            intent.getStringExtra(EXTRA_REFERER)?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
            intent.getStringExtra(EXTRA_USER_AGENT)?.takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }
        }

        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("正在下載串流")
                .setContentText("正在解析 HLS 播放清單")
                .setOngoing(true)
                .setProgress(0, 0, true)
                .build()
        )

        executor.execute {
            runCatching { downloadHls(url, headers) }
                .onSuccess { notifyDone("串流下載完成") }
                .onFailure { notifyDone("串流下載失敗：${it.message ?: "未知錯誤"}") }
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf(startId)
        }

        return START_NOT_STICKY
    }

    private fun downloadHls(initialUrl: String, headers: Map<String, String>) {
        var playlistUrl = initialUrl
        var manifest = fetchText(playlistUrl, headers)

        val masterVariant = chooseHighestVariant(manifest, playlistUrl)
        if (masterVariant != null) {
            playlistUrl = masterVariant
            manifest = fetchText(playlistUrl, headers)
        }

        val parsed = parseMediaPlaylist(manifest, playlistUrl)
        require(parsed.segments.isNotEmpty()) { "找不到可下載的 HLS 分段" }

        val extension = if (parsed.initSegment != null || parsed.segments.any { it.url.contains(".m4s", true) }) "mp4" else "ts"
        val outputName = "Meerkat-${System.currentTimeMillis()}.$extension"

        openOutput(outputName).use { output ->
            parsed.initSegment?.let { init -> output.write(fetchBytes(init, headers)) }

            parsed.segments.forEachIndexed { index, segment ->
                var bytes = fetchBytes(segment.url, headers)
                val key = segment.key
                if (key != null && key.method.equals("AES-128", true)) {
                    val keyBytes = fetchBytes(key.uri, headers)
                    require(keyBytes.size >= 16) { "HLS 金鑰長度無效" }
                    val iv = key.iv ?: sequenceIv(segment.sequence)
                    bytes = decryptAes128(bytes, keyBytes.copyOf(16), iv)
                }
                output.write(bytes)
                if (index % 5 == 0 || index == parsed.segments.lastIndex) {
                    updateProgress(index + 1, parsed.segments.size)
                }
            }
            output.flush()
        }
    }

    private fun chooseHighestVariant(manifest: String, baseUrl: String): String? {
        val lines = manifest.lineSequence().map { it.trim() }.toList()
        var bestBandwidth = -1L
        var bestUrl: String? = null
        for (i in lines.indices) {
            val line = lines[i]
            if (!line.startsWith("#EXT-X-STREAM-INF:", true)) continue
            val bandwidth = Regex("""(?:^|,)BANDWIDTH=(\\d+)""", RegexOption.IGNORE_CASE)
                .find(line.substringAfter(':'))?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
            val next = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: continue
            if (bandwidth > bestBandwidth) {
                bestBandwidth = bandwidth
                bestUrl = resolve(baseUrl, next)
            }
        }
        return bestUrl
    }

    private data class HlsKey(val method: String, val uri: String, val iv: ByteArray?)
    private data class Segment(val url: String, val sequence: Long, val key: HlsKey?)
    private data class Playlist(val initSegment: String?, val segments: List<Segment>)

    private fun parseMediaPlaylist(manifest: String, baseUrl: String): Playlist {
        var sequence = 0L
        var currentKey: HlsKey? = null
        var initSegment: String? = null
        val segments = mutableListOf<Segment>()

        manifest.lineSequence().map { it.trim() }.forEach { line ->
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:", true) -> {
                    sequence = line.substringAfter(':').trim().toLongOrNull() ?: sequence
                }
                line.startsWith("#EXT-X-MAP:", true) -> {
                    attribute(line.substringAfter(':'), "URI")?.let { initSegment = resolve(baseUrl, it) }
                }
                line.startsWith("#EXT-X-KEY:", true) -> {
                    val attrs = line.substringAfter(':')
                    val method = attribute(attrs, "METHOD") ?: "NONE"
                    if (method.equals("NONE", true)) {
                        currentKey = null
                    } else {
                        attribute(attrs, "URI")?.let { keyUri ->
                            currentKey = HlsKey(
                                method = method,
                                uri = resolve(baseUrl, keyUri),
                                iv = attribute(attrs, "IV")?.let(::hexIv),
                            )
                        }
                    }
                }
                line.isNotBlank() && !line.startsWith("#") -> {
                    segments += Segment(resolve(baseUrl, line), sequence, currentKey)
                    sequence++
                }
            }
        }
        return Playlist(initSegment, segments)
    }

    private fun attribute(attrs: String, name: String): String? {
        val quoted = Regex("""(?:^|,)\\s*${Regex.escape(name)}="([^"]*)"""", RegexOption.IGNORE_CASE)
            .find(attrs)?.groupValues?.getOrNull(1)
        if (quoted != null) return quoted
        return Regex("""(?:^|,)\\s*${Regex.escape(name)}=([^,]*)""", RegexOption.IGNORE_CASE)
            .find(attrs)?.groupValues?.getOrNull(1)?.trim()
    }

    private fun resolve(baseUrl: String, relative: String): String = URI(baseUrl).resolve(relative).toString()

    private fun fetchText(url: String, headers: Map<String, String>): String =
        fetchBytes(url, headers).toString(Charsets.UTF_8)

    private fun fetchBytes(url: String, headers: Map<String, String>): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
        connection.inputStream.use { return it.readBytes() }
    }

    private fun decryptAes128(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    private fun sequenceIv(sequence: Long): ByteArray =
        ByteBuffer.allocate(16).putLong(0L).putLong(sequence).array()

    private fun hexIv(value: String): ByteArray {
        val clean = value.removePrefix("0x").removePrefix("0X").padStart(32, '0').takeLast(32)
        return ByteArray(16) { index ->
            clean.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun openOutput(fileName: String): OutputStream {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, if (fileName.endsWith(".mp4")) "video/mp4" else "video/mp2t")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Meerkat")
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("無法建立下載檔案")
            return contentResolver.openOutputStream(uri) ?: error("無法開啟下載檔案")
        }

        @Suppress("DEPRECATION")
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val targetDir = File(dir, "Meerkat").apply { mkdirs() }
        return File(targetDir, fileName).outputStream()
    }

    private fun updateProgress(done: Int, total: Int) {
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("正在下載串流")
                .setContentText("$done / $total 分段")
                .setOngoing(true)
                .setProgress(total, done, false)
                .build()
        )
    }

    private fun notifyDone(text: String) {
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Meerkat")
                .setContentText(text)
                .setAutoCancel(true)
                .build()
        )
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "串流下載", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

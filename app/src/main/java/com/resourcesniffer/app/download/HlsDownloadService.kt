package com.resourcesniffer.app.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.net.Uri
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
        const val EXTRA_RECORD_ID = "record_id"
        const val EXTRA_URL = "url"
        const val EXTRA_COOKIE = "cookie"
        const val EXTRA_REFERER = "referer"
        const val EXTRA_USER_AGENT = "user_agent"
        const val EXTRA_QUALITY = "quality"
    }

    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val recordId = intent?.getStringExtra(EXTRA_RECORD_ID)
        val url = intent?.getStringExtra(EXTRA_URL) ?: run {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (recordId != null) {
            DownloadRegistry.update(recordId) {
                it.copy(state = DownloadState.DOWNLOADING, detail = "正在解析 HLS", progress = 0)
            }
        }
        val quality = intent?.getStringExtra(EXTRA_QUALITY)
            ?.let { runCatching { DownloadQuality.valueOf(it) }.getOrNull() }
            ?: DownloadQuality.HIGH
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
            runCatching { downloadHls(url, headers, recordId, quality) }
                .onSuccess { localUri ->
                    if (recordId != null) {
                        DownloadRegistry.update(recordId) { old ->
                            old.copy(
                                state = DownloadState.COMPLETED,
                                progress = 100,
                                detail = "串流下載完成",
                                localUri = localUri,
                            )
                        }
                    }
                    notifyDone("串流下載完成")
                }
                .onFailure { error ->
                    if (recordId != null) {
                        DownloadRegistry.update(recordId) { old ->
                            if (old.state == DownloadState.CANCELLED) old
                            else old.copy(state = DownloadState.FAILED, detail = error.message ?: "未知錯誤")
                        }
                    }
                    if (recordId == null || DownloadRegistry.find(recordId)?.state != DownloadState.CANCELLED) {
                        notifyDone("串流下載失敗：${error.message ?: "未知錯誤"}")
                    }
                }
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf(startId)
        }

        return START_NOT_STICKY
    }

    private fun downloadHls(
        initialUrl: String,
        headers: Map<String, String>,
        recordId: String?,
        quality: DownloadQuality,
    ): String {
        var playlistUrl = initialUrl
        var manifest = fetchText(playlistUrl, headers)

        val masterVariant = chooseVariant(manifest, playlistUrl, quality)
        if (masterVariant != null) {
            playlistUrl = masterVariant
            manifest = fetchText(playlistUrl, headers)
        }

        val parsed = parseMediaPlaylist(manifest, playlistUrl)
        require(parsed.segments.isNotEmpty()) { "找不到可下載的 HLS 分段" }

        val extension = if (parsed.segments.any { it.init != null || it.url.contains(".m4s", true) }) "mp4" else "ts"
        val outputName = "Meerkat-${System.currentTimeMillis()}.$extension"

        val target = openOutput(outputName)
        try {
            target.stream.use { output ->
                var lastInit: RangedResource? = null

            parsed.segments.forEachIndexed { index, segment ->
                if (recordId != null && DownloadRegistry.find(recordId)?.state == DownloadState.CANCELLED) {
                    error("下載已取消")
                }
                segment.init?.let { init ->
                    if (init != lastInit) {
                        output.write(fetchBytes(init.url, headers, init.range))
                        lastInit = init
                    }
                }

                var bytes = fetchBytes(segment.url, headers, segment.range)
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
                    if (recordId != null) {
                        val progress = ((index + 1) * 100 / parsed.segments.size).coerceIn(0, 100)
                        DownloadRegistry.update(recordId) { old ->
                            old.copy(
                                state = DownloadState.DOWNLOADING,
                                progress = progress,
                                detail = "${index + 1} / ${parsed.segments.size} 分段",
                            )
                        }
                    }
                }
            }
                output.flush()
            }
            publishOutput(target.uri)
            return target.uri.toString()
        } catch (error: Throwable) {
            runCatching { target.stream.close() }
            runCatching { contentResolver.delete(target.uri, null, null) }
            throw error
        }
    }

    private data class MasterVariant(
        val url: String,
        val bandwidth: Long,
        val resolution: String?,
        val audioGroup: String?,
        val subtitleGroup: String?,
    )

    private data class MasterMedia(
        val type: String,
        val groupId: String?,
        val name: String?,
        val language: String?,
        val default: Boolean,
        val uri: String?,
    )

    private data class MasterPlaylist(
        val variants: List<MasterVariant>,
        val media: List<MasterMedia>,
    )

    private fun chooseVariant(
        manifest: String,
        baseUrl: String,
        quality: DownloadQuality,
    ): String? {
        val master = parseMasterPlaylist(manifest, baseUrl)
        val comparator = compareBy<MasterVariant> {
            it.resolution
                ?.substringAfter('x', "")
                ?.toIntOrNull()
                ?: 0
        }.thenBy { it.bandwidth }
        val selected = when (quality) {
            DownloadQuality.HIGH -> master.variants.maxWithOrNull(comparator)
            DownloadQuality.LOW -> master.variants.minWithOrNull(comparator)
        }
        return selected?.url
    }

    private fun parseMasterPlaylist(manifest: String, baseUrl: String): MasterPlaylist {
        val lines = manifest.lineSequence().map { it.trim() }.toList()
        val variants = mutableListOf<MasterVariant>()
        val media = mutableListOf<MasterMedia>()

        for (i in lines.indices) {
            val line = lines[i]
            when {
                line.startsWith("#EXT-X-STREAM-INF:", true) -> {
                    val attrs = line.substringAfter(':')
                    val next = lines.drop(i + 1)
                        .firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                        ?: continue
                    variants += MasterVariant(
                        url = resolve(baseUrl, next),
                        bandwidth = attribute(attrs, "BANDWIDTH")?.toLongOrNull() ?: 0L,
                        resolution = attribute(attrs, "RESOLUTION"),
                        audioGroup = attribute(attrs, "AUDIO"),
                        subtitleGroup = attribute(attrs, "SUBTITLES"),
                    )
                }

                line.startsWith("#EXT-X-MEDIA:", true) -> {
                    val attrs = line.substringAfter(':')
                    media += MasterMedia(
                        type = attribute(attrs, "TYPE").orEmpty(),
                        groupId = attribute(attrs, "GROUP-ID"),
                        name = attribute(attrs, "NAME"),
                        language = attribute(attrs, "LANGUAGE"),
                        default = attribute(attrs, "DEFAULT").equals("YES", true),
                        uri = attribute(attrs, "URI")?.let { resolve(baseUrl, it) },
                    )
                }
            }
        }
        return MasterPlaylist(variants, media)
    }

    private data class HlsKey(val method: String, val uri: String, val iv: ByteArray?)
    private data class ByteRange(val start: Long, val length: Long)
    private data class RangedResource(val url: String, val range: ByteRange?)
    private data class Segment(
        val url: String,
        val sequence: Long,
        val key: HlsKey?,
        val range: ByteRange?,
        val init: RangedResource?,
    )
    private data class Playlist(val segments: List<Segment>)

    private fun parseMediaPlaylist(manifest: String, baseUrl: String): Playlist {
        var sequence = 0L
        var currentKey: HlsKey? = null
        var currentInit: RangedResource? = null
        var pendingRangeSpec: String? = null
        val previousEndByUrl = mutableMapOf<String, Long>()
        val segments = mutableListOf<Segment>()

        manifest.lineSequence().map { it.trim() }.forEach { line ->
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:", true) -> {
                    sequence = line.substringAfter(':').trim().toLongOrNull() ?: sequence
                }

                line.startsWith("#EXT-X-MAP:", true) -> {
                    val attrs = line.substringAfter(':')
                    val uri = attribute(attrs, "URI") ?: return@forEach
                    val url = resolve(baseUrl, uri)
                    val range = attribute(attrs, "BYTERANGE")?.let {
                        parseByteRange(it, url, previousEndByUrl)
                    }
                    currentInit = RangedResource(url, range)
                }

                line.startsWith("#EXT-X-BYTERANGE:", true) -> {
                    pendingRangeSpec = line.substringAfter(':').trim()
                }

                line.startsWith("#EXT-X-KEY:", true) -> {
                    val attrs = line.substringAfter(':')
                    val method = attribute(attrs, "METHOD") ?: "NONE"
                    when {
                        method.equals("NONE", true) -> currentKey = null
                        method.equals("AES-128", true) -> {
                            val keyUri = attribute(attrs, "URI")
                                ?: error("HLS AES-128 缺少金鑰 URI")
                            currentKey = HlsKey(
                                method = method,
                                uri = resolve(baseUrl, keyUri),
                                iv = attribute(attrs, "IV")?.let(::hexIv),
                            )
                        }
                        else -> error("不支援的 HLS 加密方式：$method")
                    }
                }

                line.startsWith("#EXT-X-DISCONTINUITY", true) -> Unit

                line.isNotBlank() && !line.startsWith("#") -> {
                    val url = resolve(baseUrl, line)
                    val range = pendingRangeSpec?.let {
                        parseByteRange(it, url, previousEndByUrl)
                    }
                    pendingRangeSpec = null
                    segments += Segment(
                        url = url,
                        sequence = sequence,
                        key = currentKey,
                        range = range,
                        init = currentInit,
                    )
                    sequence++
                }
            }
        }
        return Playlist(segments)
    }

    private fun parseByteRange(
        spec: String,
        url: String,
        previousEndByUrl: MutableMap<String, Long>,
    ): ByteRange? {
        val clean = spec.trim().trim('"')
        val length = clean.substringBefore('@').toLongOrNull() ?: return null
        if (length <= 0) return null
        val explicit = clean.substringAfter('@', "").takeIf { it.isNotBlank() }?.toLongOrNull()
        val start = explicit ?: previousEndByUrl[url] ?: 0L
        previousEndByUrl[url] = start + length
        return ByteRange(start, length)
    }

    private fun attribute(attrs: String, name: String): String? {
        val quoted = Regex("""(?:^|,)\s*${Regex.escape(name)}="([^"]*)"""", RegexOption.IGNORE_CASE)
            .find(attrs)?.groupValues?.getOrNull(1)
        if (quoted != null) return quoted
        return Regex("""(?:^|,)\s*${Regex.escape(name)}=([^,]*)""", RegexOption.IGNORE_CASE)
            .find(attrs)?.groupValues?.getOrNull(1)?.trim()
    }

    private fun resolve(baseUrl: String, relative: String): String = URI(baseUrl).resolve(relative).toString()

    private fun fetchText(url: String, headers: Map<String, String>): String =
        fetchBytes(url, headers).toString(Charsets.UTF_8)

    private fun fetchBytes(
        url: String,
        headers: Map<String, String>,
        range: ByteRange? = null,
    ): ByteArray {
        var lastError: Throwable? = null
        repeat(3) { attempt ->
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.instanceFollowRedirects = true
                headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
                range?.let {
                    val end = it.start + it.length - 1
                    connection.setRequestProperty("Range", "bytes=${it.start}-$end")
                }
                val code = connection.responseCode
                if (code in 200..299) {
                    connection.inputStream.use { return it.readBytes() }
                }
                val retryable = code == 408 || code == 429 || code in 500..599
                if (!retryable) error("HTTP $code：$url")
                lastError = IllegalStateException("HTTP $code：$url")
            } catch (error: Throwable) {
                lastError = error
            } finally {
                connection.disconnect()
            }
            if (attempt < 2) Thread.sleep(400L * (attempt + 1))
        }
        throw lastError ?: IllegalStateException("下載失敗：$url")
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

    private data class OutputTarget(val uri: Uri, val stream: OutputStream)

    private fun openOutput(fileName: String): OutputTarget {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, if (fileName.endsWith(".mp4")) "video/mp4" else "video/mp2t")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Meerkat")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("無法建立下載檔案")
        val stream = contentResolver.openOutputStream(uri)
            ?: error("無法開啟下載檔案")
        return OutputTarget(uri, stream)
    }

    private fun publishOutput(uri: Uri) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.IS_PENDING, 0)
        }
        contentResolver.update(uri, values, null, null)
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

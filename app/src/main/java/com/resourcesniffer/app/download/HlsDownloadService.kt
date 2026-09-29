package com.resourcesniffer.app.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.net.Uri
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream
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
        private const val MAX_MASTER_DEPTH = 4
        private const val LIVE_MAX_RECORD_MS = 6L * 60L * 60L * 1000L
        private const val LIVE_MIN_REFRESH_MS = 1_000L
        private const val LIVE_MAX_REFRESH_MS = 10_000L
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
                            if (old.state == DownloadState.CANCELLED) {
                                old.copy(
                                    progress = null,
                                    detail = "已停止直播錄製，已保留目前內容",
                                    localUri = localUri,
                                )
                            } else {
                                old.copy(
                                    state = DownloadState.COMPLETED,
                                    progress = 100,
                                    detail = "串流下載完成",
                                    localUri = localUri,
                                )
                            }
                        }
                    }
                    val cancelled = recordId != null &&
                        DownloadRegistry.find(recordId)?.state == DownloadState.CANCELLED
                    notifyDone(if (cancelled) "直播錄製已停止，內容已保留" else "串流下載完成")
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
        val resolved = resolveMediaSelection(
            initialUrl = initialUrl,
            headers = headers,
            quality = quality,
        )
        val videoPlaylistUrl = resolved.videoUrl
        val videoManifest = resolved.videoManifest
        val videoPlaylist = parseMediaPlaylist(videoManifest, videoPlaylistUrl)
        require(videoPlaylist.segments.isNotEmpty()) { "找不到可下載的 HLS 視訊分段" }

        val audioUrl = resolved.audio?.uri
        if (audioUrl.isNullOrBlank()) {
            val localUri = if (videoPlaylist.isLive) {
                writeLiveSinglePlaylist(
                    playlistUrl = videoPlaylistUrl,
                    initialPlaylist = videoPlaylist,
                    headers = headers,
                    recordId = recordId,
                )
            } else {
                writeSinglePlaylist(videoPlaylist, headers, recordId)
            }
            if (!isCancelled(recordId)) {
                saveSubtitleSidecar(resolved.subtitle, headers, recordId)
            }
            return localUri
        }

        val audioManifest = fetchText(audioUrl, headers)
        val audioPlaylist = parseMediaPlaylist(audioManifest, audioUrl)
        require(audioPlaylist.segments.isNotEmpty()) { "找不到可下載的 HLS 音訊分段" }
        require(!videoPlaylist.isLive && !audioPlaylist.isLive) {
            "此直播使用獨立音訊 playlist；需要同步雙軌時間軸，暫不以快照方式下載"
        }

        val stamp = recordId ?: System.currentTimeMillis().toString()
        val videoTemp = File(cacheDir, "hls-" + stamp + "-video.bin")
        val audioTemp = File(cacheDir, "hls-" + stamp + "-audio.bin")
        val muxed = File(cacheDir, "hls-" + stamp + "-muxed.mp4")

        try {
            val total = videoPlaylist.segments.size + audioPlaylist.segments.size
            var done = 0
            writePlaylistToFile(videoPlaylist, headers, videoTemp, recordId) {
                done++
                updateCombinedProgress(recordId, done, total)
            }
            writePlaylistToFile(audioPlaylist, headers, audioTemp, recordId) {
                done++
                updateCombinedProgress(recordId, done, total)
            }

            ensureNotCancelled(recordId)
            muxTracks(videoTemp, audioTemp, muxed)
            val localUri = copyFileToDownloads(
                muxed,
                "Meerkat-" + System.currentTimeMillis() + ".mp4",
                "video/mp4",
            )
            saveSubtitleSidecar(resolved.subtitle, headers, recordId)
            return localUri
        } finally {
            videoTemp.delete()
            audioTemp.delete()
            muxed.delete()
        }
    }

    private fun writeLiveSinglePlaylist(
        playlistUrl: String,
        initialPlaylist: Playlist,
        headers: Map<String, String>,
        recordId: String?,
    ): String {
        val extension = if (
            initialPlaylist.segments.any { it.init != null || it.url.contains(".m4s", true) }
        ) "mp4" else "ts"
        val outputName = "Meerkat-live-" + System.currentTimeMillis() + "." + extension
        val target = openOutput(outputName)
        val seen = linkedSetOf<String>()
        var lastInit: RangedResource? = null
        var totalWritten = 0
        var playlist = initialPlaylist
        val startedAt = System.currentTimeMillis()

        try {
            target.stream.use { output ->
                while (true) {
                    val newSegments = playlist.segments.filter { segment ->
                        val key = segmentIdentity(segment)
                        if (key in seen) false else {
                            seen += key
                            true
                        }
                    }

                    for (segment in newSegments) {
                        if (isCancelled(recordId)) break

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
                        output.flush()
                        totalWritten++

                        recordId?.let { id ->
                            DownloadRegistry.update(id) { old ->
                                if (old.state == DownloadState.CANCELLED) old
                                else old.copy(
                                    state = DownloadState.DOWNLOADING,
                                    progress = null,
                                    detail = "直播錄製中 · 已保存 " + totalWritten + " 分段",
                                )
                            }
                        }
                        updateLiveNotification(totalWritten)
                    }

                    if (isCancelled(recordId)) break
                    if (playlist.endList) break
                    if (System.currentTimeMillis() - startedAt >= LIVE_MAX_RECORD_MS) {
                        recordId?.let { id ->
                            DownloadRegistry.update(id) { old ->
                                old.copy(detail = "已達直播錄製安全上限，正在保存")
                            }
                        }
                        break
                    }

                    val refreshMs = ((playlist.targetDurationSeconds ?: 4.0) * 500.0)
                        .toLong()
                        .coerceIn(LIVE_MIN_REFRESH_MS, LIVE_MAX_REFRESH_MS)
                    Thread.sleep(refreshMs)

                    if (isCancelled(recordId)) break
                    val manifest = fetchText(playlistUrl, headers)
                    playlist = parseMediaPlaylist(manifest, playlistUrl)
                    require(playlist.segments.isNotEmpty() || playlist.endList) {
                        "直播 playlist 暫時沒有可下載分段"
                    }
                }
                output.flush()
            }

            require(totalWritten > 0) { "直播期間沒有取得可保存的 HLS 分段" }
            publishOutput(target.uri)
            return target.uri.toString()
        } catch (error: Throwable) {
            runCatching { target.stream.close() }
            if (totalWritten > 0 && isCancelled(recordId)) {
                runCatching { publishOutput(target.uri) }
                return target.uri.toString()
            }
            runCatching { contentResolver.delete(target.uri, null, null) }
            throw error
        }
    }

    private fun segmentIdentity(segment: Segment): String =
        buildString {
            append(segment.sequence)
            append('|')
            append(segment.url)
            segment.range?.let {
                append('|')
                append(it.start)
                append(':')
                append(it.length)
            }
        }

    private fun writeSinglePlaylist(
        playlist: Playlist,
        headers: Map<String, String>,
        recordId: String?,
    ): String {
        val extension = if (playlist.segments.any { it.init != null || it.url.contains(".m4s", true) }) "mp4" else "ts"
        val outputName = "Meerkat-" + System.currentTimeMillis() + "." + extension
        val target = openOutput(outputName)
        try {
            target.stream.use { output ->
                writePlaylist(playlist, headers, output, recordId) { done, total ->
                    updateProgress(done, total)
                    recordId?.let { id ->
                        val progress = (done * 100 / total.coerceAtLeast(1)).coerceIn(0, 100)
                        DownloadRegistry.update(id) { old ->
                            old.copy(
                                state = DownloadState.DOWNLOADING,
                                progress = progress,
                                detail = done.toString() + " / " + total + " 分段",
                            )
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

    private fun writePlaylistToFile(
        playlist: Playlist,
        headers: Map<String, String>,
        file: File,
        recordId: String?,
        onSegment: () -> Unit,
    ) {
        FileOutputStream(file).use { output ->
            writePlaylist(playlist, headers, output, recordId) { _, _ -> onSegment() }
        }
    }

    private fun writePlaylist(
        playlist: Playlist,
        headers: Map<String, String>,
        output: OutputStream,
        recordId: String?,
        onProgress: (Int, Int) -> Unit,
    ) {
        var lastInit: RangedResource? = null
        playlist.segments.forEachIndexed { index, segment ->
            ensureNotCancelled(recordId)
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
            onProgress(index + 1, playlist.segments.size)
        }
        output.flush()
    }

    private fun isCancelled(recordId: String?): Boolean =
        recordId != null && DownloadRegistry.find(recordId)?.state == DownloadState.CANCELLED

    private fun ensureNotCancelled(recordId: String?) {
        if (isCancelled(recordId)) error("下載已取消")
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

    private data class MasterSelection(
        val variant: MasterVariant,
        val audio: MasterMedia?,
        val subtitle: MasterMedia?,
    )

    private data class ResolvedMediaSelection(
        val videoUrl: String,
        val videoManifest: String,
        val audio: MasterMedia?,
        val subtitle: MasterMedia?,
    )

    private fun resolveMediaSelection(
        initialUrl: String,
        headers: Map<String, String>,
        quality: DownloadQuality,
    ): ResolvedMediaSelection {
        var currentUrl = initialUrl
        var currentManifest = fetchText(currentUrl, headers)
        var selectedAudio: MasterMedia? = null
        var selectedSubtitle: MasterMedia? = null
        val visited = linkedSetOf<String>()

        repeat(MAX_MASTER_DEPTH) {
            require(visited.add(currentUrl)) { "HLS master playlist 發生循環引用" }

            val master = parseMasterPlaylist(currentManifest, currentUrl)
            if (master.variants.isEmpty()) {
                return ResolvedMediaSelection(
                    videoUrl = currentUrl,
                    videoManifest = currentManifest,
                    audio = selectedAudio,
                    subtitle = selectedSubtitle,
                )
            }

            val selection = chooseSelection(currentManifest, currentUrl, quality)
                ?: error("找不到可用的 HLS 畫質")
            selectedAudio = selection.audio ?: selectedAudio
            selectedSubtitle = selection.subtitle ?: selectedSubtitle
            currentUrl = selection.variant.url
            currentManifest = fetchText(currentUrl, headers)
        }

        val finalMaster = parseMasterPlaylist(currentManifest, currentUrl)
        require(finalMaster.variants.isEmpty()) {
            "HLS master playlist 巢狀層級超過 " + MAX_MASTER_DEPTH
        }
        return ResolvedMediaSelection(
            videoUrl = currentUrl,
            videoManifest = currentManifest,
            audio = selectedAudio,
            subtitle = selectedSubtitle,
        )
    }

    private fun chooseSelection(
        manifest: String,
        baseUrl: String,
        quality: DownloadQuality,
    ): MasterSelection? {
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
        } ?: return null

        fun chooseMedia(type: String, groupId: String?): MasterMedia? {
            if (groupId.isNullOrBlank()) return null
            val candidates = master.media.filter {
                it.type.equals(type, true) &&
                    it.groupId == groupId &&
                    !it.uri.isNullOrBlank()
            }
            return candidates.firstOrNull { it.default } ?: candidates.firstOrNull()
        }

        return MasterSelection(
            variant = selected,
            audio = chooseMedia("AUDIO", selected.audioGroup),
            subtitle = chooseMedia("SUBTITLES", selected.subtitleGroup),
        )
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
    private data class Playlist(
        val segments: List<Segment>,
        val mediaSequence: Long,
        val targetDurationSeconds: Double?,
        val endList: Boolean,
    ) {
        val isLive: Boolean get() = !endList
    }

    private fun parseMediaPlaylist(manifest: String, baseUrl: String): Playlist {
        var sequence = 0L
        var mediaSequence = 0L
        var targetDurationSeconds: Double? = null
        var endList = false
        var currentKey: HlsKey? = null
        var currentInit: RangedResource? = null
        var pendingRangeSpec: String? = null
        val previousEndByUrl = mutableMapOf<String, Long>()
        val segments = mutableListOf<Segment>()

        manifest.lineSequence().map { it.trim() }.forEach { line ->
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:", true) -> {
                    val parsed = line.substringAfter(':').trim().toLongOrNull()
                    if (parsed != null) {
                        mediaSequence = parsed
                        sequence = parsed
                    }
                }

                line.startsWith("#EXT-X-TARGETDURATION:", true) -> {
                    targetDurationSeconds = line.substringAfter(':').trim().toDoubleOrNull()
                }

                line.startsWith("#EXT-X-ENDLIST", true) -> {
                    endList = true
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
        return Playlist(
            segments = segments,
            mediaSequence = mediaSequence,
            targetDurationSeconds = targetDurationSeconds,
            endList = endList,
        )
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

    private fun muxTracks(videoFile: File, audioFile: File, outputFile: File) {
        val videoExtractor = MediaExtractor().apply { setDataSource(videoFile.absolutePath) }
        val audioExtractor = MediaExtractor().apply { setDataSource(audioFile.absolutePath) }
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        try {
            var videoTrack = -1
            var audioTrack = -1
            var videoMuxTrack = -1
            var audioMuxTrack = -1

            for (i in 0 until videoExtractor.trackCount) {
                val format = videoExtractor.getTrackFormat(i)
                val mime = format.getString("mime").orEmpty()
                if (mime.startsWith("video/")) {
                    videoTrack = i
                    videoMuxTrack = muxer.addTrack(format)
                    break
                }
            }
            for (i in 0 until audioExtractor.trackCount) {
                val format = audioExtractor.getTrackFormat(i)
                val mime = format.getString("mime").orEmpty()
                if (mime.startsWith("audio/")) {
                    audioTrack = i
                    audioMuxTrack = muxer.addTrack(format)
                    break
                }
            }

            require(videoTrack >= 0) { "HLS 視訊軌解析失敗" }
            require(audioTrack >= 0) { "HLS 外掛音訊軌解析失敗" }

            videoExtractor.selectTrack(videoTrack)
            audioExtractor.selectTrack(audioTrack)
            muxer.start()

            fun copyTrack(extractor: MediaExtractor, muxTrack: Int) {
                val buffer = ByteBuffer.allocate(2 * 1024 * 1024)
                val info = MediaCodec.BufferInfo()
                while (true) {
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    info.offset = 0
                    info.size = size
                    info.presentationTimeUs = extractor.sampleTime
                    info.flags = extractor.sampleFlags
                    muxer.writeSampleData(muxTrack, buffer, info)
                    extractor.advance()
                }
            }

            copyTrack(videoExtractor, videoMuxTrack)
            copyTrack(audioExtractor, audioMuxTrack)
        } finally {
            runCatching { muxer.stop() }
            muxer.release()
            videoExtractor.release()
            audioExtractor.release()
        }
    }

    private fun saveSubtitleSidecar(
        subtitle: MasterMedia?,
        headers: Map<String, String>,
        recordId: String?,
    ) {
        val url = subtitle?.uri ?: return
        ensureNotCancelled(recordId)

        val manifest = fetchText(url, headers)
        val safeLabel = (subtitle.language ?: subtitle.name ?: "subtitle")
            .replace(Regex("""[^A-Za-z0-9._-]+"""), "_")
            .take(40)
            .ifBlank { "subtitle" }

        if (!manifest.trimStart().startsWith("#EXTM3U", true)) {
            copyBytesToDownloads(
                manifest.toByteArray(Charsets.UTF_8),
                "Meerkat-" + System.currentTimeMillis() + "-" + safeLabel + ".vtt",
                "text/vtt",
            )
            return
        }

        val playlist = parseMediaPlaylist(manifest, url)
        if (playlist.segments.isEmpty()) {
            copyBytesToDownloads(
                manifest.toByteArray(Charsets.UTF_8),
                "Meerkat-" + System.currentTimeMillis() + "-" + safeLabel + ".m3u8",
                "application/vnd.apple.mpegurl",
            )
            return
        }

        val firstPath = playlist.segments.first().url.substringBefore('?').lowercase()
        val isWebVtt = firstPath.endsWith(".vtt") || firstPath.endsWith(".webvtt")
        if (!isWebVtt) {
            copyBytesToDownloads(
                manifest.toByteArray(Charsets.UTF_8),
                "Meerkat-" + System.currentTimeMillis() + "-" + safeLabel + ".m3u8",
                "application/vnd.apple.mpegurl",
            )
            return
        }

        val output = StringBuilder("WEBVTT\n\n")
        playlist.segments.forEach { segment ->
            ensureNotCancelled(recordId)
            var text = fetchBytes(segment.url, headers, segment.range)
                .toString(Charsets.UTF_8)
                .removePrefix("\uFEFF")
                .trim()
            if (text.startsWith("WEBVTT", true)) {
                text = text.substringAfter('\n', "").trimStart()
            }
            if (text.isNotBlank()) {
                output.append(text)
                if (!text.endsWith("\n")) output.append('\n')
                output.append('\n')
            }
        }

        copyBytesToDownloads(
            output.toString().toByteArray(Charsets.UTF_8),
            "Meerkat-" + System.currentTimeMillis() + "-" + safeLabel + ".vtt",
            "text/vtt",
        )
    }

    private fun copyBytesToDownloads(bytes: ByteArray, displayName: String, mimeType: String): String {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Meerkat")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("無法建立字幕檔案")
        try {
            contentResolver.openOutputStream(uri, "w")?.use { output ->
                output.write(bytes)
                output.flush()
            } ?: error("無法寫入字幕檔案")
            contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null,
                null,
            )
            return uri.toString()
        } catch (error: Throwable) {
            runCatching { contentResolver.delete(uri, null, null) }
            throw error
        }
    }

    private fun copyFileToDownloads(file: File, displayName: String, mimeType: String): String {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Meerkat")
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("無法建立下載檔案")
        contentResolver.openOutputStream(uri)?.use { output ->
            file.inputStream().use { input -> input.copyTo(output) }
        } ?: error("無法寫入下載檔案")
        return uri.toString()
    }

    private fun updateCombinedProgress(recordId: String?, done: Int, total: Int) {
        val safeTotal = total.coerceAtLeast(1)
        val progress = (done * 100 / safeTotal).coerceIn(0, 100)
        recordId?.let { id ->
            DownloadRegistry.update(id) { old ->
                old.copy(
                    state = DownloadState.DOWNLOADING,
                    progress = progress,
                    detail = done.toString() + " / " + safeTotal + " 分段（影音）",
                )
            }
        }
        updateProgress(done, safeTotal)
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

    private fun updateLiveNotification(segmentCount: Int) {
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("正在錄製 HLS 直播")
                .setContentText("已保存 " + segmentCount + " 分段")
                .setOngoing(true)
                .setProgress(0, 0, true)
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

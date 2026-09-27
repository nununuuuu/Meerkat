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
import android.provider.MediaStore
import android.util.Xml
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.math.ceil
import org.xmlpull.v1.XmlPullParser

class DashDownloadService : Service() {
    companion object {
        private const val CHANNEL_ID = "dash_download"
        private const val NOTIFICATION_ID = 2201
        const val EXTRA_RECORD_ID = "record_id"
        const val EXTRA_URL = "url"
        const val EXTRA_COOKIE = "cookie"
        const val EXTRA_REFERER = "referer"
        const val EXTRA_USER_AGENT = "user_agent"

        private fun resolve(base: String, relative: String): String =
            URI(base).resolve(relative).toString()

        private fun replaceTemplate(template: String, id: String, number: Long, time: Long): String =
            template
                .replace("\$RepresentationID\$", id)
                .replace("\$Number\$", number.toString())
                .replace("\$Time\$", time.toString())
                .replace(Regex("""[$]Number%0(\\d+)d[$]""")) { match ->
                    number.toString().padStart(match.groupValues[1].toInt(), '0')
                }
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
        val headers = buildMap {
            intent.getStringExtra(EXTRA_COOKIE)?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
            intent.getStringExtra(EXTRA_REFERER)?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
            intent.getStringExtra(EXTRA_USER_AGENT)?.takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }
        }

        recordId?.let {
            DownloadRegistry.update(it) { old ->
                old.copy(state = DownloadState.DOWNLOADING, progress = 0, detail = "正在解析 DASH")
            }
        }

        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("正在下載 DASH")
                .setContentText("正在解析 MPD")
                .setProgress(0, 0, true)
                .setOngoing(true)
                .build()
        )

        executor.execute {
            runCatching { downloadDash(url, headers, recordId) }
                .onSuccess { localUri ->
                    recordId?.let {
                        DownloadRegistry.update(it) { old ->
                            old.copy(
                                state = DownloadState.COMPLETED,
                                progress = 100,
                                detail = "DASH 下載完成",
                                localUri = localUri,
                            )
                        }
                    }
                    notifyDone("DASH 下載完成")
                }
                .onFailure { error ->
                    recordId?.let {
                        DownloadRegistry.update(it) { old ->
                            if (old.state == DownloadState.CANCELLED) old
                            else old.copy(state = DownloadState.FAILED, detail = error.message ?: "未知錯誤")
                        }
                    }
                    if (recordId == null || DownloadRegistry.find(recordId)?.state != DownloadState.CANCELLED) {
                        notifyDone("DASH 下載失敗：${error.message ?: "未知錯誤"}")
                    }
                }
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private enum class TrackKind { VIDEO, AUDIO, OTHER }

    private data class TimelineEntry(val t: Long?, val d: Long, val r: Int)

    private data class Representation(
        val kind: TrackKind,
        val id: String,
        val bandwidth: Long,
        val width: Int?,
        val height: Int?,
        val baseUrl: String,
        val initialization: String?,
        val media: String?,
        val explicitInitialization: String?,
        val explicitSegments: List<String>,
        val startNumber: Long,
        val timescale: Long,
        val segmentDuration: Long?,
        val manifestDurationSeconds: Double?,
        val timeline: List<TimelineEntry>,
    ) {
        fun initializationUrl(): String? =
            explicitInitialization?.let { resolve(baseUrl, it) }
                ?: initialization?.let { resolve(baseUrl, replaceTemplate(it, id, startNumber, 0L)) }

        fun segmentUrls(): List<String> {
            if (explicitSegments.isNotEmpty()) {
                return explicitSegments.map { resolve(baseUrl, it) }
            }
            val mediaTemplate = media ?: return emptyList()
            if (timeline.isNotEmpty()) {
                val out = mutableListOf<String>()
                var number = startNumber
                var currentTime = timeline.first().t ?: 0L
                timeline.forEach { entry ->
                    entry.t?.let { currentTime = it }
                    val count = if (entry.r < 0) 1 else entry.r + 1
                    repeat(count) {
                        out += resolve(baseUrl, replaceTemplate(mediaTemplate, id, number, currentTime))
                        number++
                        currentTime += entry.d
                    }
                }
                return out
            }

            val d = segmentDuration ?: return emptyList()
            val total = manifestDurationSeconds ?: return emptyList()
            val count = ceil(total * timescale / d.toDouble()).toInt().coerceAtLeast(1)
            return (0 until count).map { index ->
                val number = startNumber + index
                val time = index.toLong() * d
                resolve(baseUrl, replaceTemplate(mediaTemplate, id, number, time))
            }
        }
    }

    private data class Template(
        val initialization: String?,
        val media: String?,
        val startNumber: Long,
        val timescale: Long,
        val duration: Long?,
        val timeline: MutableList<TimelineEntry>,
    )

    private data class SegmentListData(
        var initialization: String? = null,
        val segments: MutableList<String> = mutableListOf(),
    )

    private data class RepBuilder(
        val kind: TrackKind,
        val id: String,
        val bandwidth: Long,
        val width: Int?,
        val height: Int?,
        val mimeType: String?,
        var baseUrl: String?,
        var template: Template?,
        var segmentList: SegmentListData?,
    )

    private data class Manifest(
        val durationSeconds: Double?,
        val representations: List<Representation>,
    )

    private fun downloadDash(mpdUrl: String, headers: Map<String, String>, recordId: String?): String {
        val xml = fetchText(mpdUrl, headers)
        require(!xml.contains("urn:uuid:edef8ba9", true) && !xml.contains("cenc:pssh", true)) {
            "此 DASH 使用 DRM/CENC，Meerkat 不支援解密"
        }

        val manifest = parseMpd(xml, mpdUrl)
        val selected = selectRepresentations(manifest)
        require(selected.isNotEmpty()) { "找不到可下載的 DASH 軌道" }

        val segmentLists = selected.associateWith { it.segmentUrls() }
        require(segmentLists.values.any { it.isNotEmpty() }) { "此 MPD 的分段格式目前不支援" }

        val total = selected.sumOf { (if (it.initializationUrl() != null) 1 else 0) + (segmentLists[it]?.size ?: 0) }
        var done = 0
        val tempFiles = mutableListOf<Pair<TrackKind, File>>()

        try {
            selected.forEach { rep ->
                ensureNotCancelled(recordId)
                val suffix = rep.kind.name.lowercase()
                val temp = File(cacheDir, "dash-${recordId ?: System.currentTimeMillis()}-$suffix.mp4")
                FileOutputStream(temp).use { output ->
                    rep.initializationUrl()?.let { initUrl ->
                        output.write(fetchBytes(initUrl, headers))
                        done++
                        updateProgress(recordId, done, total)
                    }

                    segmentLists[rep].orEmpty().forEach { segment ->
                        ensureNotCancelled(recordId)
                        output.write(fetchBytes(segment, headers))
                        done++
                        updateProgress(recordId, done, total)
                    }
                }
                tempFiles += rep.kind to temp
            }

            ensureNotCancelled(recordId)
            val outputName = "Meerkat-${System.currentTimeMillis()}.mp4"
            val video = tempFiles.firstOrNull { it.first == TrackKind.VIDEO }?.second
            val audio = tempFiles.firstOrNull { it.first == TrackKind.AUDIO }?.second

            val localUri = if (video != null && audio != null) {
                val muxed = File(cacheDir, "dash-${recordId ?: System.currentTimeMillis()}-muxed.mp4")
                try {
                    muxMp4(video, audio, muxed)
                    copyToDownloads(muxed, outputName)
                } finally {
                    muxed.delete()
                }
            } else {
                copyToDownloads(tempFiles.first().second, outputName)
            }
            return localUri
        } finally {
            tempFiles.forEach { it.second.delete() }
        }
    }

    private fun parseMpd(xml: String, mpdUrl: String): Manifest {
        val parser = Xml.newPullParser()
        parser.setInput(xml.reader())

        var mpdDuration: Double? = null
        var periodDuration: Double? = null
        var adaptationKind = TrackKind.OTHER
        var adaptationMime: String? = null
        var adaptationBase: String? = null
        var adaptationTemplate: Template? = null
        var adaptationSegmentList: SegmentListData? = null
        var currentRep: RepBuilder? = null
        var inTimeline = false
        var baseTarget: String? = null
        val reps = mutableListOf<Representation>()

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "MPD" -> mpdDuration = parseIsoDuration(parser.getAttributeValue(null, "mediaPresentationDuration"))
                    "Period" -> periodDuration = parseIsoDuration(parser.getAttributeValue(null, "duration"))
                    "AdaptationSet" -> {
                        adaptationMime = parser.getAttributeValue(null, "mimeType")
                        adaptationKind = kindFor(
                            parser.getAttributeValue(null, "contentType"),
                            adaptationMime,
                        )
                        adaptationBase = null
                        adaptationTemplate = null
                        adaptationSegmentList = null
                    }
                    "Representation" -> {
                        currentRep = RepBuilder(
                            kind = adaptationKind,
                            id = parser.getAttributeValue(null, "id") ?: "representation",
                            bandwidth = parser.getAttributeValue(null, "bandwidth")?.toLongOrNull() ?: 0L,
                            width = parser.getAttributeValue(null, "width")?.toIntOrNull(),
                            height = parser.getAttributeValue(null, "height")?.toIntOrNull(),
                            mimeType = parser.getAttributeValue(null, "mimeType") ?: adaptationMime,
                            baseUrl = null,
                            template = null,
                            segmentList = null,
                        )
                    }
                    "BaseURL" -> baseTarget = if (currentRep != null) "rep" else "adapt"
                    "SegmentTemplate" -> {
                        val template = Template(
                            initialization = parser.getAttributeValue(null, "initialization"),
                            media = parser.getAttributeValue(null, "media"),
                            startNumber = parser.getAttributeValue(null, "startNumber")?.toLongOrNull() ?: 1L,
                            timescale = parser.getAttributeValue(null, "timescale")?.toLongOrNull() ?: 1L,
                            duration = parser.getAttributeValue(null, "duration")?.toLongOrNull(),
                            timeline = mutableListOf(),
                        )
                        if (currentRep != null) currentRep?.template = template else adaptationTemplate = template
                    }
                    "SegmentList" -> {
                        val list = SegmentListData()
                        if (currentRep != null) currentRep?.segmentList = list else adaptationSegmentList = list
                    }
                    "Initialization" -> {
                        val source = parser.getAttributeValue(null, "sourceURL")
                        if (!source.isNullOrBlank()) {
                            (currentRep?.segmentList ?: adaptationSegmentList)?.initialization = source
                        }
                    }
                    "SegmentURL" -> {
                        val media = parser.getAttributeValue(null, "media")
                        if (!media.isNullOrBlank()) {
                            (currentRep?.segmentList ?: adaptationSegmentList)?.segments?.add(media)
                        }
                    }
                    "SegmentTimeline" -> inTimeline = true
                    "S" -> if (inTimeline) {
                        val d = parser.getAttributeValue(null, "d")?.toLongOrNull()
                        if (d != null && d > 0) {
                            (currentRep?.template ?: adaptationTemplate)?.timeline?.add(
                                TimelineEntry(
                                    t = parser.getAttributeValue(null, "t")?.toLongOrNull(),
                                    d = d,
                                    r = parser.getAttributeValue(null, "r")?.toIntOrNull() ?: 0,
                                )
                            )
                        }
                    }
                }

                XmlPullParser.TEXT -> {
                    val value = parser.text.trim()
                    if (value.isNotEmpty()) {
                        when (baseTarget) {
                            "rep" -> currentRep?.baseUrl = value
                            "adapt" -> adaptationBase = value
                        }
                    }
                }

                XmlPullParser.END_TAG -> when (parser.name) {
                    "BaseURL" -> baseTarget = null
                    "SegmentTimeline" -> inTimeline = false
                    "Representation" -> {
                        val b = currentRep
                        val template = b?.template ?: adaptationTemplate
                        val segmentList = b?.segmentList ?: adaptationSegmentList
                        if (
                            b != null &&
                            (
                                (template?.initialization != null && template.media != null) ||
                                    (segmentList != null && segmentList.segments.isNotEmpty())
                            )
                        ) {
                            val durationSeconds = periodDuration ?: mpdDuration
                            reps += Representation(
                                kind = if (b.kind == TrackKind.OTHER) kindFor(null, b.mimeType) else b.kind,
                                id = b.id,
                                bandwidth = b.bandwidth,
                                width = b.width,
                                height = b.height,
                                baseUrl = resolve(mpdUrl, b.baseUrl ?: adaptationBase ?: "."),
                                initialization = template?.initialization,
                                media = template?.media,
                                explicitInitialization = segmentList?.initialization,
                                explicitSegments = segmentList?.segments?.toList().orEmpty(),
                                startNumber = template?.startNumber ?: 1L,
                                timescale = template?.timescale ?: 1L,
                                segmentDuration = template?.duration,
                                manifestDurationSeconds = durationSeconds,
                                timeline = template?.timeline?.toList().orEmpty(),
                            )
                        }
                        currentRep = null
                    }
                }
            }
            parser.next()
        }
        return Manifest(periodDuration ?: mpdDuration, reps)
    }

    private fun selectRepresentations(manifest: Manifest): List<Representation> {
        val video = manifest.representations
            .filter { it.kind == TrackKind.VIDEO }
            .maxWithOrNull(compareBy<Representation> { it.height ?: 0 }.thenBy { it.bandwidth })
        val audio = manifest.representations
            .filter { it.kind == TrackKind.AUDIO }
            .maxByOrNull { it.bandwidth }

        return listOfNotNull(video, audio).ifEmpty {
            listOfNotNull(manifest.representations.maxByOrNull { it.bandwidth })
        }
    }

    private fun muxMp4(videoFile: File, audioFile: File, outputFile: File) {
        val videoExtractor = MediaExtractor().apply { setDataSource(videoFile.absolutePath) }
        val audioExtractor = MediaExtractor().apply { setDataSource(audioFile.absolutePath) }
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        try {
            val mappings = mutableListOf<Pair<MediaExtractor, Int>>()

            fun addTrack(extractor: MediaExtractor, prefix: String) {
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString("mime").orEmpty()
                    if (!mime.startsWith(prefix)) continue
                    extractor.selectTrack(i)
                    mappings += extractor to muxer.addTrack(format)
                    break
                }
            }

            addTrack(videoExtractor, "video/")
            addTrack(audioExtractor, "audio/")
            require(mappings.isNotEmpty()) { "無法解析 DASH 音視訊軌" }

            muxer.start()
            val buffer = ByteBuffer.allocate(2 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()

            mappings.forEach { (extractor, muxTrack) ->
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
        } finally {
            runCatching { muxer.stop() }
            muxer.release()
            videoExtractor.release()
            audioExtractor.release()
        }
    }

    private fun copyToDownloads(file: File, displayName: String): String {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, "video/mp4")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Meerkat")
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("無法建立下載檔案")
        contentResolver.openOutputStream(uri)?.use { output ->
            file.inputStream().use { input -> input.copyTo(output) }
        } ?: error("無法寫入下載檔案")
        return uri.toString()
    }

    private fun updateProgress(recordId: String?, done: Int, total: Int) {
        val progress = if (total <= 0) 0 else ((done * 100) / total).coerceIn(0, 100)
        recordId?.let {
            DownloadRegistry.update(it) { old ->
                old.copy(
                    state = DownloadState.DOWNLOADING,
                    progress = progress,
                    detail = "$done / $total 分段",
                )
            }
        }
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("正在下載 DASH")
                .setContentText("$done / $total 分段")
                .setProgress(total, done, false)
                .setOngoing(true)
                .build()
        )
    }

    private fun ensureNotCancelled(recordId: String?) {
        if (recordId != null && DownloadRegistry.find(recordId)?.state == DownloadState.CANCELLED) {
            error("下載已取消")
        }
    }

    private fun fetchText(url: String, headers: Map<String, String>): String =
        fetchBytes(url, headers).toString(Charsets.UTF_8)

    private fun fetchBytes(url: String, headers: Map<String, String>): ByteArray {
        var lastError: Throwable? = null
        repeat(3) { attempt ->
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.instanceFollowRedirects = true
                headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
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
                NotificationChannel(CHANNEL_ID, "DASH 下載", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun kindFor(contentType: String?, mime: String?): TrackKind {
        val value = (contentType ?: mime).orEmpty().lowercase()
        return when {
            value.contains("video") -> TrackKind.VIDEO
            value.contains("audio") -> TrackKind.AUDIO
            else -> TrackKind.OTHER
        }
    }

    private fun parseIsoDuration(value: String?): Double? {
        if (value.isNullOrBlank()) return null
        val match = Regex("""P(?:(\d+(?:\.\d+)?)D)?(?:T(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?)?""")
            .matchEntire(value) ?: return null
        val days = match.groupValues[1].toDoubleOrNull() ?: 0.0
        val hours = match.groupValues[2].toDoubleOrNull() ?: 0.0
        val minutes = match.groupValues[3].toDoubleOrNull() ?: 0.0
        val seconds = match.groupValues[4].toDoubleOrNull() ?: 0.0
        return days * 86400 + hours * 3600 + minutes * 60 + seconds
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

}

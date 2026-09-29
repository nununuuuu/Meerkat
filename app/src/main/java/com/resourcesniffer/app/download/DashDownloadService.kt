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
        const val EXTRA_QUALITY = "quality"

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
        val quality = intent?.getStringExtra(EXTRA_QUALITY)
            ?.let { runCatching { DownloadQuality.valueOf(it) }.getOrNull() }
            ?: DownloadQuality.HIGH
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
            runCatching { downloadDash(url, headers, recordId, quality) }
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

    private enum class TrackKind { VIDEO, AUDIO, TEXT, OTHER }

    private data class TimelineEntry(val t: Long?, val d: Long, val r: Int)

    private data class Representation(
        val periodIndex: Int,
        val kind: TrackKind,
        val id: String,
        val bandwidth: Long,
        val width: Int?,
        val height: Int?,
        val mimeType: String?,
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
        val singleFileUrl: String?,
    ) {
        fun initializationUrl(): String? {
            if (singleFileUrl != null) return null
            return explicitInitialization?.let { resolve(baseUrl, it) }
                ?: initialization?.let { resolve(baseUrl, replaceTemplate(it, id, startNumber, 0L)) }
        }

        fun segmentUrls(): List<String> {
            singleFileUrl?.let { return listOf(it) }
            if (explicitSegments.isNotEmpty()) {
                return explicitSegments.map { resolve(baseUrl, it) }
            }
            val mediaTemplate = media ?: return emptyList()
            if (timeline.isNotEmpty()) {
                val out = mutableListOf<String>()
                var number = startNumber
                var currentTime = timeline.first().t ?: 0L
                timeline.forEachIndexed { index, entry ->
                    entry.t?.let { currentTime = it }
                    val count = if (entry.r >= 0) {
                        entry.r + 1
                    } else {
                        val nextTime = timeline.getOrNull(index + 1)?.t
                        when {
                            nextTime != null && nextTime > currentTime -> {
                                ceil((nextTime - currentTime).toDouble() / entry.d.toDouble())
                                    .toInt()
                                    .coerceAtLeast(1)
                            }
                            manifestDurationSeconds != null -> {
                                val endTicks = (manifestDurationSeconds * timescale).toLong()
                                ceil((endTicks - currentTime).coerceAtLeast(entry.d).toDouble() / entry.d.toDouble())
                                    .toInt()
                                    .coerceAtLeast(1)
                            }
                            else -> 1
                        }
                    }
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
        val periodIndex: Int,
        val kind: TrackKind,
        val id: String,
        val bandwidth: Long,
        val width: Int?,
        val height: Int?,
        val mimeType: String?,
        var baseUrl: String?,
        var template: Template?,
        var segmentList: SegmentListData?,
        var segmentBase: Boolean,
    )

    private data class Manifest(
        val durationSeconds: Double?,
        val representations: List<Representation>,
    )

    private fun downloadDash(
        mpdUrl: String,
        headers: Map<String, String>,
        recordId: String?,
        quality: DownloadQuality,
    ): String {
        val xml = fetchText(mpdUrl, headers)
        require(!xml.contains("urn:uuid:edef8ba9", true) && !xml.contains("cenc:pssh", true)) {
            "此 DASH 使用 DRM/CENC，Meerkat 不支援解密"
        }

        val manifest = parseMpd(xml, mpdUrl)
        val selectedByPeriod = selectRepresentationsByPeriod(manifest, quality)
        require(selectedByPeriod.isNotEmpty()) { "找不到可下載的 DASH 軌道" }

        val flattened = selectedByPeriod.values.flatten()
        val segmentLists = flattened.associateWith { it.segmentUrls() }
        require(segmentLists.values.any { it.isNotEmpty() }) { "此 MPD 的分段格式目前不支援" }

        val total = flattened.sumOf {
            (if (it.initializationUrl() != null) 1 else 0) +
                (segmentLists[it]?.size ?: 0)
        }
        var done = 0
        val allTempFiles = mutableListOf<File>()
        val periodPrimaryFiles = mutableListOf<File>()
        val periodSubtitlePairs = mutableListOf<Pair<Representation, File>>()
        val stamp = recordId ?: System.currentTimeMillis().toString()

        try {
            selectedByPeriod.toSortedMap().forEach { (periodIndex, selected) ->
                ensureNotCancelled(recordId)
                val tempFiles = mutableListOf<Pair<TrackKind, File>>()

                selected.forEachIndexed { repIndex, rep ->
                    ensureNotCancelled(recordId)
                    val suffix = rep.kind.name.lowercase()
                    val temp = File(
                        cacheDir,
                        "dash-" + stamp + "-p" + periodIndex + "-" + suffix + "-" + repIndex + ".bin",
                    )
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
                        output.fd.sync()
                    }
                    allTempFiles += temp
                    tempFiles += rep.kind to temp
                    if (rep.kind == TrackKind.TEXT) {
                        periodSubtitlePairs += rep to temp
                    }
                }

                val video = tempFiles.firstOrNull { it.first == TrackKind.VIDEO }?.second
                val audio = tempFiles.firstOrNull { it.first == TrackKind.AUDIO }?.second
                val primary = tempFiles.filter { it.first != TrackKind.TEXT }

                when {
                    video != null && audio != null -> {
                        val muxed = File(cacheDir, "dash-" + stamp + "-p" + periodIndex + "-muxed.mp4")
                        muxMp4(video, audio, muxed)
                        allTempFiles += muxed
                        periodPrimaryFiles += muxed
                    }
                    primary.isNotEmpty() -> periodPrimaryFiles += primary.first().second
                }
            }

            ensureNotCancelled(recordId)

            periodSubtitlePairs.forEachIndexed { index, (rep, file) ->
                copySubtitleTrackToDownloads(
                    representation = rep,
                    file = file,
                    suffix = if (selectedByPeriod.size > 1) "-part" + (index + 1) else "",
                )
            }

            if (periodPrimaryFiles.isEmpty()) {
                val textOnly = periodSubtitlePairs.firstOrNull()
                    ?: error("沒有可保存的 DASH 軌道")
                return copySubtitleTrackToDownloads(textOnly.first, textOnly.second)
            }

            val outputName = "Meerkat-" + System.currentTimeMillis() + ".mp4"
            if (periodPrimaryFiles.size == 1) {
                return copyToDownloads(periodPrimaryFiles.first(), outputName)
            }

            val concatenated = File(cacheDir, "dash-" + stamp + "-all-periods.mp4")
            val merged = runCatching {
                concatenateMp4Periods(periodPrimaryFiles, concatenated)
                copyToDownloads(concatenated, outputName)
            }.getOrNull()
            allTempFiles += concatenated

            if (merged != null) return merged

            var firstUri: String? = null
            periodPrimaryFiles.forEachIndexed { index, part ->
                val uri = copyToDownloads(
                    part,
                    "Meerkat-" + System.currentTimeMillis() + "-part" + (index + 1) + ".mp4",
                )
                if (firstUri == null) firstUri = uri
            }
            recordId?.let { id ->
                DownloadRegistry.update(id) { old ->
                    old.copy(detail = "DASH 多 Period 格式不同，已分段保存")
                }
            }
            return firstUri ?: error("無法保存 DASH Multi-Period")
        } finally {
            allTempFiles.distinct().forEach { it.delete() }
        }
    }

    private fun parseMpd(xml: String, mpdUrl: String): Manifest {
        val parser = Xml.newPullParser()
        parser.setInput(xml.reader())

        var mpdDuration: Double? = null
        var periodDuration: Double? = null
        var periodIndex = -1
        var adaptationKind = TrackKind.OTHER
        var adaptationMime: String? = null
        var adaptationBase: String? = null
        var adaptationTemplate: Template? = null
        var adaptationSegmentList: SegmentListData? = null
        var adaptationSegmentBase = false
        var currentRep: RepBuilder? = null
        var inTimeline = false
        var baseTarget: String? = null
        val reps = mutableListOf<Representation>()

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "MPD" -> mpdDuration = parseIsoDuration(parser.getAttributeValue(null, "mediaPresentationDuration"))
                    "Period" -> {
                        periodIndex++
                        periodDuration = parseIsoDuration(parser.getAttributeValue(null, "duration"))
                    }
                    "AdaptationSet" -> {
                        adaptationMime = parser.getAttributeValue(null, "mimeType")
                        adaptationKind = kindFor(
                            parser.getAttributeValue(null, "contentType"),
                            adaptationMime,
                        )
                        adaptationBase = null
                        adaptationTemplate = null
                        adaptationSegmentList = null
                        adaptationSegmentBase = false
                    }
                    "Role" -> {
                        val role = parser.getAttributeValue(null, "value").orEmpty().lowercase()
                        if (
                            role.contains("subtitle") ||
                            role.contains("caption") ||
                            role.contains("text")
                        ) {
                            adaptationKind = TrackKind.TEXT
                        }
                    }
                    "Representation" -> {
                        currentRep = RepBuilder(
                            periodIndex = periodIndex.coerceAtLeast(0),
                            kind = adaptationKind,
                            id = parser.getAttributeValue(null, "id") ?: "representation",
                            bandwidth = parser.getAttributeValue(null, "bandwidth")?.toLongOrNull() ?: 0L,
                            width = parser.getAttributeValue(null, "width")?.toIntOrNull(),
                            height = parser.getAttributeValue(null, "height")?.toIntOrNull(),
                            mimeType = parser.getAttributeValue(null, "mimeType") ?: adaptationMime,
                            baseUrl = null,
                            template = null,
                            segmentList = null,
                            segmentBase = false,
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
                    "SegmentBase" -> {
                        if (currentRep != null) {
                            currentRep?.segmentBase = true
                        } else {
                            adaptationSegmentBase = true
                        }
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
                        if (b != null) {
                            val durationSeconds = periodDuration ?: mpdDuration
                            val resolvedBase = resolve(mpdUrl, b.baseUrl ?: adaptationBase ?: ".")
                            val hasTemplate =
                                template?.initialization != null && template.media != null
                            val hasList =
                                segmentList != null && segmentList.segments.isNotEmpty()
                            val hasSegmentBase =
                                b.segmentBase || adaptationSegmentBase
                            val looksLikeSingleFile =
                                hasSegmentBase || looksLikeDirectMediaUrl(resolvedBase, b.mimeType)

                            if (hasTemplate || hasList || looksLikeSingleFile) {
                                reps += Representation(
                                    periodIndex = b.periodIndex,
                                    kind = if (b.kind == TrackKind.OTHER) kindFor(null, b.mimeType) else b.kind,
                                    id = b.id,
                                    bandwidth = b.bandwidth,
                                    width = b.width,
                                    height = b.height,
                                    mimeType = b.mimeType,
                                    baseUrl = resolvedBase,
                                    initialization = template?.initialization,
                                    media = template?.media,
                                    explicitInitialization = segmentList?.initialization,
                                    explicitSegments = segmentList?.segments?.toList().orEmpty(),
                                    startNumber = template?.startNumber ?: 1L,
                                    timescale = template?.timescale ?: 1L,
                                    segmentDuration = template?.duration,
                                    manifestDurationSeconds = durationSeconds,
                                    timeline = template?.timeline?.toList().orEmpty(),
                                    singleFileUrl = resolvedBase.takeIf { looksLikeSingleFile && !hasTemplate && !hasList },
                                )
                            }
                        }
                        currentRep = null
                    }
                }
            }
            parser.next()
        }
        return Manifest(periodDuration ?: mpdDuration, reps)
    }

    private fun selectRepresentationsByPeriod(
        manifest: Manifest,
        quality: DownloadQuality,
    ): Map<Int, List<Representation>> {
        val comparator = compareBy<Representation> { it.height ?: 0 }.thenBy { it.bandwidth }

        return manifest.representations
            .groupBy { it.periodIndex }
            .toSortedMap()
            .mapValues { (_, reps) ->
                val videoCandidates = reps.filter { it.kind == TrackKind.VIDEO }
                val audioCandidates = reps.filter { it.kind == TrackKind.AUDIO }
                val textCandidates = reps.filter { it.kind == TrackKind.TEXT }

                val video = when (quality) {
                    DownloadQuality.HIGH -> videoCandidates.maxWithOrNull(comparator)
                    DownloadQuality.LOW -> videoCandidates.minWithOrNull(comparator)
                }
                val audio = when (quality) {
                    DownloadQuality.HIGH -> audioCandidates.maxByOrNull { it.bandwidth }
                    DownloadQuality.LOW -> audioCandidates.minByOrNull { it.bandwidth }
                }
                val text = textCandidates.maxByOrNull { it.bandwidth }

                listOfNotNull(video, audio, text).ifEmpty {
                    listOfNotNull(
                        when (quality) {
                            DownloadQuality.HIGH -> reps.maxByOrNull { it.bandwidth }
                            DownloadQuality.LOW -> reps.minByOrNull { it.bandwidth }
                        }
                    )
                }
            }
            .filterValues { it.isNotEmpty() }
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

    private fun concatenateMp4Periods(
        periodFiles: List<File>,
        outputFile: File,
    ) {
        require(periodFiles.size >= 2) { "至少需要兩個 DASH Period" }

        val firstExtractor = MediaExtractor().apply {
            setDataSource(periodFiles.first().absolutePath)
        }
        val muxer = MediaMuxer(
            outputFile.absolutePath,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
        )

        val outputTrackByMime = linkedMapOf<String, Int>()
        try {
            for (track in 0 until firstExtractor.trackCount) {
                val format = firstExtractor.getTrackFormat(track)
                val mime = format.getString("mime").orEmpty()
                if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
                if (mime !in outputTrackByMime) {
                    outputTrackByMime[mime] = muxer.addTrack(format)
                }
            }
            require(outputTrackByMime.isNotEmpty()) { "無法解析 DASH Period 軌道" }
            muxer.start()

            val buffer = ByteBuffer.allocate(2 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            val nextOffsetByMime = mutableMapOf<String, Long>()

            periodFiles.forEach { file ->
                val extractor = MediaExtractor().apply { setDataSource(file.absolutePath) }
                try {
                    val tracks = mutableListOf<Triple<Int, String, Int>>()
                    for (track in 0 until extractor.trackCount) {
                        val format = extractor.getTrackFormat(track)
                        val mime = format.getString("mime").orEmpty()
                        val muxTrack = outputTrackByMime[mime] ?: continue
                        extractor.selectTrack(track)
                        tracks += Triple(track, mime, muxTrack)
                    }
                    require(tracks.isNotEmpty()) { "DASH Period 軌道格式不相容" }

                    tracks.forEach { (trackIndex, mime, muxTrack) ->
                        extractor.unselectAllTracksCompat()
                        extractor.selectTrack(trackIndex)
                        extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

                        val offset = nextOffsetByMime[mime] ?: 0L
                        var maxPts = offset
                        while (true) {
                            buffer.clear()
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) break
                            val sampleTime = extractor.sampleTime
                            if (sampleTime < 0L) break
                            info.offset = 0
                            info.size = size
                            info.presentationTimeUs = offset + sampleTime
                            info.flags = extractor.sampleFlags
                            muxer.writeSampleData(muxTrack, buffer, info)
                            maxPts = maxOf(maxPts, info.presentationTimeUs)
                            extractor.advance()
                        }
                        nextOffsetByMime[mime] = maxPts + 1L
                    }
                } finally {
                    extractor.release()
                }
            }
        } finally {
            firstExtractor.release()
            runCatching { muxer.stop() }
            muxer.release()
        }
    }

    private fun MediaExtractor.unselectAllTracksCompat() {
        for (track in 0 until trackCount) {
            runCatching { unselectTrack(track) }
        }
    }

    private fun copySubtitleTrackToDownloads(
        representation: Representation,
        file: File,
        suffix: String = "",
    ): String {
        val mime = representation.mimeType?.substringBefore(';')?.lowercase().orEmpty()
        val extension = when {
            mime == "text/vtt" -> "vtt"
            mime.contains("ttml") || mime.contains("xml") -> "ttml"
            mime == "application/mp4" -> "mp4"
            else -> "bin"
        }
        val outputMime = when (extension) {
            "vtt" -> "text/vtt"
            "ttml" -> "application/ttml+xml"
            "mp4" -> "application/mp4"
            else -> "application/octet-stream"
        }
        val displayName = "Meerkat-" + System.currentTimeMillis() + suffix + "-subtitle." + extension

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, outputMime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Meerkat")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("無法建立 DASH 字幕檔案")
        try {
            contentResolver.openOutputStream(uri, "w")?.use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
            } ?: error("無法寫入 DASH 字幕檔案")
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

    private fun looksLikeDirectMediaUrl(url: String, mimeType: String?): Boolean {
        val clean = url.substringBefore('?').lowercase()
        if (
            clean.endsWith(".mp4") ||
            clean.endsWith(".m4a") ||
            clean.endsWith(".m4v") ||
            clean.endsWith(".webm") ||
            clean.endsWith(".mp3") ||
            clean.endsWith(".vtt") ||
            clean.endsWith(".ttml")
        ) return true

        val mime = mimeType.orEmpty().lowercase()
        return mime.startsWith("video/") ||
            mime.startsWith("audio/") ||
            mime == "application/mp4" ||
            mime == "text/vtt" ||
            mime.contains("ttml")
    }

    private fun kindFor(contentType: String?, mime: String?): TrackKind {
        val value = (contentType ?: mime).orEmpty().lowercase()
        return when {
            value.contains("video") -> TrackKind.VIDEO
            value.contains("audio") -> TrackKind.AUDIO
            value.contains("text") ||
                value.contains("subtitle") ||
                value.contains("caption") ||
                value.contains("ttml") ||
                value.contains("vtt") -> TrackKind.TEXT
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

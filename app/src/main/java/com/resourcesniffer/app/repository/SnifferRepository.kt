package com.resourcesniffer.app.repository

import android.content.Context
import android.net.Uri
import com.resourcesniffer.app.core.MediaIdentity
import com.resourcesniffer.app.core.Resource
import com.resourcesniffer.app.core.ValidationState
import com.resourcesniffer.app.core.ResourceType
import com.resourcesniffer.app.core.StreamType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

object SnifferRepository {
    private const val PREFS = "meerkat_resources"
    private const val KEY_HISTORY = "history"
    private const val MAX_HISTORY = 500

    @Volatile var generation: Long = 0
        private set

    private val _resources = MutableStateFlow<List<Resource>>(emptyList())
    val resources: StateFlow<List<Resource>> = _resources.asStateFlow()

    private val _preferredResources = MutableStateFlow<List<Resource>>(emptyList())
    val preferredResources: StateFlow<List<Resource>> = _preferredResources.asStateFlow()

    @Volatile private var appContext: Context? = null
    private val persistenceExecutor = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var pendingPersist: ScheduledFuture<*>? = null

    @Synchronized
    fun initialize(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        val loaded = loadHistory()
        _resources.value = loaded
        recomputePreferred(loaded)
    }

    @Synchronized
    fun add(resource: Resource) {
        val current = _resources.value
        val resourceKey = MediaIdentity.exactKey(resource.finalUrl ?: resource.url)
        val existing = current.indexOfFirst {
            it.id == resource.id || (
                MediaIdentity.exactKey(it.finalUrl ?: it.url) == resourceKey &&
                    it.type == resource.type &&
                    it.sourceAppPackage == resource.sourceAppPackage
            )
        }

        val merged = if (existing >= 0) {
            val old = current[existing]
            val preferred = if (qualityScore(resource) >= qualityScore(old)) resource else old
            val secondary = if (preferred === resource) old else resource
            val replacement = preferred.copy(
                id = old.id,
                sessionId = resource.sessionId,
                detectedAt = maxOf(old.detectedAt, resource.detectedAt),
                mimeType = preferred.mimeType ?: secondary.mimeType,
                fileName = resource.fileName ?: old.fileName,
                localCachePath = resource.localCachePath ?: old.localCachePath,
                contentLength = maxOfNullable(old.contentLength, resource.contentLength),
                referer = preferred.referer ?: secondary.referer,
                userAgent = preferred.userAgent ?: secondary.userAgent,
                cookie = preferred.cookie ?: secondary.cookie,
                width = maxOfNullable(old.width, resource.width),
                height = maxOfNullable(old.height, resource.height),
                durationMs = maxOfNullable(old.durationMs, resource.durationMs),
                videoCodec = preferred.videoCodec ?: secondary.videoCodec,
                audioCodec = preferred.audioCodec ?: secondary.audioCodec,
                variantCount = maxOfNullable(old.variantCount, resource.variantCount),
                audioTrackCount = maxOfNullable(old.audioTrackCount, resource.audioTrackCount),
                subtitleTrackCount = maxOfNullable(old.subtitleTrackCount, resource.subtitleTrackCount),
                maxBandwidth = maxOfNullable(old.maxBandwidth, resource.maxBandwidth),
                isLive = resource.isLive ?: old.isLive,
                drmDetected = when {
                    resource.drmDetected == true || old.drmDetected == true -> true
                    resource.drmDetected == false || old.drmDetected == false -> false
                    else -> null
                },
                finalUrl = resource.finalUrl ?: old.finalUrl,
                etag = resource.etag ?: old.etag,
                mediaGroupKey = resource.mediaGroupKey ?: old.mediaGroupKey ?: MediaIdentity.groupKey(resource.finalUrl ?: resource.url),
                validationState = when {
                    resource.validationState == ValidationState.VERIFIED -> ValidationState.VERIFIED
                    old.validationState == ValidationState.VERIFIED -> ValidationState.VERIFIED
                    resource.validationState == ValidationState.FAILED && old.validationState == ValidationState.FAILED -> ValidationState.FAILED
                    else -> ValidationState.UNVERIFIED
                },
                verifiedAt = maxOfNullable(old.verifiedAt, resource.verifiedAt),
            )
            listOf(replacement) + current.filterIndexed { index, _ -> index != existing }
        } else {
            listOf(resource.copy(mediaGroupKey = resource.mediaGroupKey ?: MediaIdentity.groupKey(resource.finalUrl ?: resource.url))) + current
        }.take(MAX_HISTORY)

        _resources.value = merged
        recomputePreferred(merged)
        schedulePersist()
    }

    @Synchronized
    fun addIfGeneration(resource: Resource, expectedGeneration: Long) {
        if (generation == expectedGeneration) add(resource)
    }

    @Synchronized
    fun clear() {
        generation++
        pendingPersist?.cancel(false)
        deleteLocalFiles(_resources.value)
        _resources.value = emptyList()
        _preferredResources.value = emptyList()
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()?.remove(KEY_HISTORY)?.apply()
    }

    @Synchronized
    fun clearSession(sessionId: Long) {
        if (sessionId == 0L) return
        val removed = _resources.value.filter { it.sessionId == sessionId }
        val kept = _resources.value.filterNot { it.sessionId == sessionId }
        deleteLocalFiles(removed)
        _resources.value = kept
        recomputePreferred(kept)
        persistHistory(kept)
    }

    private fun deleteLocalFiles(resources: List<Resource>) {
        resources.asSequence()
            .mapNotNull { it.localCachePath }
            .distinct()
            .forEach { path ->
                runCatching { java.io.File(path).delete() }
            }
    }

    private fun qualityScore(resource: Resource): Long {
        val verifiedBonus = if (resource.validationState == ValidationState.VERIFIED) 9_000_000_000_000_000L else 0L
        val pixels = (resource.width?.toLong() ?: 0L) * (resource.height?.toLong() ?: 0L)
        val size = resource.contentLength ?: 0L
        return when (resource.type) {
            ResourceType.IMAGE, ResourceType.VIDEO -> verifiedBonus + pixels.coerceAtMost(8_000_000_000L) * 1_000_000L + size.coerceAtMost(999_999L)
            else -> verifiedBonus + size
        }
    }

    private fun maxOfNullable(a: Long?, b: Long?): Long? = when {
        a == null -> b
        b == null -> a
        else -> maxOf(a, b)
    }

    private fun maxOfNullable(a: Int?, b: Int?): Int? = when {
        a == null -> b
        b == null -> a
        else -> maxOf(a, b)
    }

    private fun recomputePreferred(all: List<Resource>) {
        _preferredResources.value = all
            .groupBy { it.mediaGroupKey ?: MediaIdentity.groupKey(it.finalUrl ?: it.url) ?: "id:" + it.id }
            .values
            .mapNotNull { variants -> variants.maxByOrNull(::qualityScore) }
            .sortedByDescending { it.detectedAt }
    }
    private fun schedulePersist() {
        pendingPersist?.cancel(false)
        pendingPersist = persistenceExecutor.schedule({
            synchronized(this) {
                persistHistory(_resources.value)
            }
        }, 750, TimeUnit.MILLISECONDS)
    }

    private fun persistHistory(resources: List<Resource>) {
        val context = appContext ?: return
        val array = JSONArray()
        resources.take(MAX_HISTORY).forEach { resource ->
            array.put(
                JSONObject().apply {
                    put("id", resource.id)
                    put("sessionId", resource.sessionId)
                    put("sourceAppPackage", resource.sourceAppPackage)
                    put("sourceAppName", resource.sourceAppName)
                    put("url", resource.url)
                    put("host", resource.host)
                    put("mimeType", resource.mimeType)
                    put("extension", resource.extension)
                    put("fileName", resource.fileName)
                    put("localCachePath", resource.localCachePath)
                    put("contentLength", resource.contentLength)
                    put("type", resource.type.name)
                    put("streamType", resource.streamType?.name)
                    put("referer", resource.referer)
                    put("width", resource.width)
                    put("height", resource.height)
                    put("durationMs", resource.durationMs)
                    put("videoCodec", resource.videoCodec)
                    put("audioCodec", resource.audioCodec)
                    put("variantCount", resource.variantCount)
                    put("audioTrackCount", resource.audioTrackCount)
                    put("subtitleTrackCount", resource.subtitleTrackCount)
                    put("maxBandwidth", resource.maxBandwidth)
                    put("isLive", resource.isLive)
                    put("drmDetected", resource.drmDetected)
                    put("finalUrl", resource.finalUrl)
                    put("etag", resource.etag)
                    put("mediaGroupKey", resource.mediaGroupKey)
                    put("validationState", resource.validationState.name)
                    put("verifiedAt", resource.verifiedAt)
                    put("detectedAt", resource.detectedAt)
                }
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_HISTORY, array.toString())
            .apply()
    }

    private fun loadHistory(): List<Resource> {
        val context = appContext ?: return emptyList()
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_HISTORY, null)
            ?: return emptyList()

        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    add(
                        Resource(
                            id = item.optLong("id", System.currentTimeMillis() + i),
                            sessionId = item.optLong("sessionId", 1L),
                            sourceAppPackage = item.optString("sourceAppPackage").takeIf { it.isNotBlank() && it != "null" },
                            sourceAppName = item.optString("sourceAppName").takeIf { it.isNotBlank() && it != "null" },
                            url = item.optString("url").takeIf { it.isNotBlank() && it != "null" },
                            host = item.optString("host", "未知來源"),
                            mimeType = item.optString("mimeType").takeIf { it.isNotBlank() && it != "null" },
                            extension = item.optString("extension").takeIf { it.isNotBlank() && it != "null" },
                            fileName = item.optString("fileName").takeIf { it.isNotBlank() && it != "null" },
                            localCachePath = item.optString("localCachePath").takeIf { it.isNotBlank() && it != "null" },
                            contentLength = if (item.isNull("contentLength")) null else item.optLong("contentLength"),
                            type = runCatching { ResourceType.valueOf(item.getString("type")) }.getOrDefault(ResourceType.OTHER),
                            streamType = item.optString("streamType").takeIf { it.isNotBlank() && it != "null" }
                                ?.let { runCatching { StreamType.valueOf(it) }.getOrNull() },
                            referer = item.optString("referer").takeIf { it.isNotBlank() && it != "null" },
                            width = if (item.isNull("width")) null else item.optInt("width"),
                            height = if (item.isNull("height")) null else item.optInt("height"),
                            durationMs = if (item.isNull("durationMs")) null else item.optLong("durationMs"),
                            videoCodec = item.optString("videoCodec").takeIf { it.isNotBlank() && it != "null" },
                            audioCodec = item.optString("audioCodec").takeIf { it.isNotBlank() && it != "null" },
                            variantCount = if (item.isNull("variantCount")) null else item.optInt("variantCount"),
                            audioTrackCount = if (item.isNull("audioTrackCount")) null else item.optInt("audioTrackCount"),
                            subtitleTrackCount = if (item.isNull("subtitleTrackCount")) null else item.optInt("subtitleTrackCount"),
                            maxBandwidth = if (item.isNull("maxBandwidth")) null else item.optLong("maxBandwidth"),
                            isLive = if (item.isNull("isLive")) null else item.optBoolean("isLive"),
                            drmDetected = if (item.isNull("drmDetected")) null else item.optBoolean("drmDetected"),
                            finalUrl = item.optString("finalUrl").takeIf { it.isNotBlank() && it != "null" },
                            etag = item.optString("etag").takeIf { it.isNotBlank() && it != "null" },
                            mediaGroupKey = item.optString("mediaGroupKey").takeIf { it.isNotBlank() && it != "null" },
                            validationState = item.optString("validationState").takeIf { it.isNotBlank() && it != "null" }
                                ?.let { runCatching { ValidationState.valueOf(it) }.getOrNull() }
                                ?: ValidationState.UNVERIFIED,
                            verifiedAt = if (item.isNull("verifiedAt")) null else item.optLong("verifiedAt"),
                            detectedAt = item.optLong("detectedAt", System.currentTimeMillis()),
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
}


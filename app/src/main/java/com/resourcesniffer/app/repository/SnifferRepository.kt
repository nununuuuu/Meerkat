package com.resourcesniffer.app.repository

import android.content.Context
import android.net.Uri
import com.resourcesniffer.app.core.Resource
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

    private val _resources = MutableStateFlow<List<Resource>>(emptyList())
    val resources: StateFlow<List<Resource>> = _resources.asStateFlow()

    @Volatile private var appContext: Context? = null
    private val persistenceExecutor = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var pendingPersist: ScheduledFuture<*>? = null

    @Synchronized
    fun initialize(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        _resources.value = loadHistory()
    }

    @Synchronized
    fun add(resource: Resource) {
        val current = _resources.value
        val resourceKey = canonicalKey(resource.url)
        val existing = current.indexOfFirst {
            canonicalKey(it.url) == resourceKey &&
                it.type == resource.type &&
                it.sourceAppPackage == resource.sourceAppPackage
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
                contentLength = maxOfNullable(old.contentLength, resource.contentLength),
                referer = preferred.referer ?: secondary.referer,
                userAgent = preferred.userAgent ?: secondary.userAgent,
                cookie = preferred.cookie ?: secondary.cookie,
                width = maxOfNullable(old.width, resource.width),
                height = maxOfNullable(old.height, resource.height),
                durationMs = maxOfNullable(old.durationMs, resource.durationMs),
                videoCodec = preferred.videoCodec ?: secondary.videoCodec,
                audioCodec = preferred.audioCodec ?: secondary.audioCodec,
            )
            listOf(replacement) + current.filterIndexed { index, _ -> index != existing }
        } else {
            listOf(resource) + current
        }.take(MAX_HISTORY)

        _resources.value = merged
        schedulePersist()
    }

    @Synchronized
    fun clear() {
        _resources.value = emptyList()
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()?.remove(KEY_HISTORY)?.apply()
    }

    @Synchronized
    fun clearSession(sessionId: Long) {
        if (sessionId == 0L) return
        val kept = _resources.value.filterNot { it.sessionId == sessionId }
        _resources.value = kept
        persistHistory(kept)
    }

    private fun canonicalKey(url: String?): String? {
        if (url.isNullOrBlank()) return url
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return url
        val builder = uri.buildUpon().clearQuery()
        val stableNames = runCatching { uri.queryParameterNames }.getOrDefault(emptySet())
        stableNames
            .filterNot { key ->
                val k = key.lowercase()
                k in setOf(
                    "token", "sig", "signature", "expires", "expiry", "auth", "auth_key",
                    "policy", "key-pair-id", "x-amz-signature", "x-amz-credential",
                    "x-amz-date", "x-amz-expires", "x-amz-security-token"
                ) || k.startsWith("utm_")
            }
            .sorted()
            .forEach { key ->
                uri.getQueryParameters(key).forEach { value ->
                    builder.appendQueryParameter(key, value)
                }
            }
        return builder.build().toString()
    }

    private fun qualityScore(resource: Resource): Long {
        val pixels = (resource.width?.toLong() ?: 0L) * (resource.height?.toLong() ?: 0L)
        val size = resource.contentLength ?: 0L
        return when (resource.type) {
            ResourceType.IMAGE, ResourceType.VIDEO -> pixels * 1_000_000L + size.coerceAtMost(999_999L)
            else -> size
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

    private fun schedulePersist() {
        pendingPersist?.cancel(false)
        pendingPersist = persistenceExecutor.schedule({
            val snapshot = _resources.value
            persistHistory(snapshot)
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
                    put("contentLength", resource.contentLength)
                    put("type", resource.type.name)
                    put("streamType", resource.streamType?.name)
                    put("referer", resource.referer)
                    put("width", resource.width)
                    put("height", resource.height)
                    put("durationMs", resource.durationMs)
                    put("videoCodec", resource.videoCodec)
                    put("audioCodec", resource.audioCodec)
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
                            detectedAt = item.optLong("detectedAt", System.currentTimeMillis()),
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
}

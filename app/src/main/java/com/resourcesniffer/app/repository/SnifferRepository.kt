package com.resourcesniffer.app.repository

import android.content.Context
import com.resourcesniffer.app.core.Resource
import com.resourcesniffer.app.core.ResourceType
import com.resourcesniffer.app.core.StreamType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

object SnifferRepository {
    private const val PREFS = "meerkat_resources"
    private const val KEY_HISTORY = "history"
    private const val MAX_HISTORY = 500

    private val _resources = MutableStateFlow<List<Resource>>(emptyList())
    val resources: StateFlow<List<Resource>> = _resources.asStateFlow()

    @Volatile private var appContext: Context? = null

    @Synchronized
    fun initialize(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        _resources.value = loadHistory()
    }

    @Synchronized
    fun add(resource: Resource) {
        val current = _resources.value
        val existing = current.indexOfFirst {
            it.url == resource.url &&
                it.type == resource.type &&
                it.sourceAppPackage == resource.sourceAppPackage
        }

        val merged = if (existing >= 0) {
            val old = current[existing]
            val replacement = resource.copy(
                id = old.id,
                detectedAt = maxOf(old.detectedAt, resource.detectedAt),
                mimeType = resource.mimeType ?: old.mimeType,
                contentLength = resource.contentLength ?: old.contentLength,
                referer = resource.referer ?: old.referer,
                userAgent = resource.userAgent ?: old.userAgent,
                cookie = resource.cookie ?: old.cookie,
                width = resource.width ?: old.width,
                height = resource.height ?: old.height,
                durationMs = resource.durationMs ?: old.durationMs,
                videoCodec = resource.videoCodec ?: old.videoCodec,
                audioCodec = resource.audioCodec ?: old.audioCodec,
            )
            listOf(replacement) + current.filterIndexed { index, _ -> index != existing }
        } else {
            listOf(resource) + current
        }.take(MAX_HISTORY)

        _resources.value = merged
        persistHistory(merged)
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

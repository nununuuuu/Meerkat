package com.resourcesniffer.app.download

import android.content.Context
import com.resourcesniffer.app.core.StreamType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

object DownloadRegistry {
    private const val MAX_ITEMS = 100
    private const val PREFS = "meerkat_downloads"
    private const val KEY_ITEMS = "items"

    private val _items = MutableStateFlow<List<DownloadRecord>>(emptyList())
    val items: StateFlow<List<DownloadRecord>> = _items.asStateFlow()

    @Volatile
    private var appContext: Context? = null

    @Synchronized
    fun initialize(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        _items.value = load().map { item ->
            if (item.state == DownloadState.QUEUED || item.state == DownloadState.DOWNLOADING) {
                item.copy(
                    state = DownloadState.FAILED,
                    detail = "App 曾被關閉，下載已中斷",
                    progress = null,
                )
            } else item
        }
        persist()
    }

    @Synchronized
    fun add(record: DownloadRecord) {
        _items.value = (listOf(record) + _items.value.filterNot { it.id == record.id }).take(MAX_ITEMS)
        persist()
    }

    @Synchronized
    fun update(id: String, transform: (DownloadRecord) -> DownloadRecord) {
        _items.value = _items.value.map { if (it.id == id) transform(it) else it }
        persist()
    }

    fun find(id: String): DownloadRecord? = _items.value.firstOrNull { it.id == id }

    @Synchronized
    fun cancel(id: String) {
        update(id) { it.copy(state = DownloadState.CANCELLED, detail = "已取消") }
    }

    @Synchronized
    fun clearCompleted() {
        _items.value = _items.value.filterNot {
            it.state == DownloadState.COMPLETED ||
                it.state == DownloadState.FAILED ||
                it.state == DownloadState.CANCELLED
        }
        persist()
    }

    private fun persist() {
        val context = appContext ?: return
        val array = JSONArray()
        _items.value.take(MAX_ITEMS).forEach { item ->
            array.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("url", item.url)
                    put("displayName", item.displayName)
                    put("mimeType", item.mimeType)
                    put("streamType", item.streamType?.name)
                    put("cookie", item.cookie)
                    put("referer", item.referer)
                    put("userAgent", item.userAgent)
                    put("localSourcePath", item.localSourcePath)
                    put("quality", item.quality.name)
                    put("state", item.state.name)
                    put("progress", item.progress)
                    put("detail", item.detail)
                    put("localUri", item.localUri)
                    put("createdAt", item.createdAt)
                }
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ITEMS, array.toString())
            .apply()
    }

    private fun load(): List<DownloadRecord> {
        val context = appContext ?: return emptyList()
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ITEMS, null)
            ?: return emptyList()

        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        DownloadRecord(
                            id = item.getString("id"),
                            url = item.getString("url"),
                            displayName = item.optString("displayName", "Meerkat 下載"),
                            mimeType = nullableString(item, "mimeType"),
                            streamType = nullableString(item, "streamType")
                                ?.let { runCatching { StreamType.valueOf(it) }.getOrNull() },
                            cookie = nullableString(item, "cookie"),
                            referer = nullableString(item, "referer"),
                            userAgent = nullableString(item, "userAgent"),
                            localSourcePath = nullableString(item, "localSourcePath"),
                            quality = nullableString(item, "quality")
                                ?.let { runCatching { DownloadQuality.valueOf(it) }.getOrNull() }
                                ?: DownloadQuality.HIGH,
                            state = runCatching {
                                DownloadState.valueOf(item.getString("state"))
                            }.getOrDefault(DownloadState.FAILED),
                            progress = if (item.isNull("progress")) null else item.optInt("progress"),
                            detail = nullableString(item, "detail"),
                            localUri = nullableString(item, "localUri"),
                            createdAt = item.optLong("createdAt", System.currentTimeMillis()),
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun nullableString(item: JSONObject, key: String): String? =
        item.optString(key)
            .takeIf { it.isNotBlank() && it != "null" }
}

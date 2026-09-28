package com.resourcesniffer.app.download

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.concurrent.ConcurrentHashMap

object DirectDownloadTracker {
    private const val PREFS = "meerkat_direct_downloads"
    private const val KEY_MAP = "id_map"

    private val ids = ConcurrentHashMap<Long, String>()
    private val reverse = ConcurrentHashMap<String, Long>()
    @Volatile private var appContext: Context? = null

    @Synchronized
    fun initialize(context: Context) {
        appContext = context.applicationContext
        ids.clear()
        reverse.clear()
        val saved = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.getStringSet(KEY_MAP, emptySet()).orEmpty()
        saved.forEach { row ->
            val sep = row.indexOf('|')
            if (sep <= 0) return@forEach
            val systemId = row.substring(0, sep).toLongOrNull() ?: return@forEach
            val recordId = row.substring(sep + 1)
            if (recordId.isNotBlank()) {
                ids[systemId] = recordId
                reverse[recordId] = systemId
            }
        }
    }

    @Synchronized
    fun track(systemId: Long, recordId: String) {
        ids[systemId] = recordId
        reverse[recordId] = systemId
        persist()
    }

    @Synchronized
    fun cancel(context: Context, recordId: String): Boolean {
        val systemId = reverse.remove(recordId) ?: return false
        ids.remove(systemId)
        persist()
        context.getSystemService(DownloadManager::class.java).remove(systemId)
        DownloadRegistry.cancel(recordId)
        return true
    }

    @Synchronized
    private fun forget(systemId: Long): String? {
        val recordId = ids.remove(systemId) ?: return null
        reverse.remove(recordId)
        persist()
        return recordId
    }

    private fun persist() {
        val context = appContext ?: return
        val set = ids.entries.map { (systemId, recordId) -> systemId.toString() + "|" + recordId }.toSet()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_MAP, set).apply()
    }

    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            if (appContext == null) initialize(context)
            val systemId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            val recordId = forget(systemId) ?: return
            val manager = context.getSystemService(DownloadManager::class.java)
            val cursor = manager.query(DownloadManager.Query().setFilterById(systemId))
            cursor.use {
                if (!it.moveToFirst()) {
                    if (DownloadRegistry.find(recordId)?.state != DownloadState.CANCELLED) {
                        DownloadRegistry.update(recordId) { old ->
                            old.copy(state = DownloadState.FAILED, detail = "找不到下載結果")
                        }
                    }
                    return
                }
                val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val reason = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                DownloadRegistry.update(recordId) { old ->
                    if (old.state == DownloadState.CANCELLED) old
                    else if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        val localUri = runCatching {
                            it.getString(it.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                        }.getOrNull()
                        old.copy(
                            state = DownloadState.COMPLETED,
                            progress = 100,
                            detail = "下載完成",
                            localUri = localUri,
                        )
                    } else {
                        old.copy(state = DownloadState.FAILED, detail = "下載失敗（代碼 " + reason + "）")
                    }
                }
            }
        }
    }
}

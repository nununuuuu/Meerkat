package com.resourcesniffer.app.download

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.concurrent.ConcurrentHashMap

object DirectDownloadTracker {
    private val ids = ConcurrentHashMap<Long, String>()
    private val reverse = ConcurrentHashMap<String, Long>()

    fun track(systemId: Long, recordId: String) {
        ids[systemId] = recordId
        reverse[recordId] = systemId
    }

    fun cancel(context: Context, recordId: String): Boolean {
        val systemId = reverse.remove(recordId) ?: return false
        ids.remove(systemId)
        val manager = context.getSystemService(DownloadManager::class.java)
        manager.remove(systemId)
        DownloadRegistry.cancel(recordId)
        return true
    }

    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val systemId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            val recordId = ids.remove(systemId) ?: return
            reverse.remove(recordId)
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
                        old.copy(state = DownloadState.FAILED, detail = "下載失敗（代碼 $reason）")
                    }
                }
            }
        }
    }
}

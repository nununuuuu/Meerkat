package com.resourcesniffer.app.download

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.concurrent.ConcurrentHashMap

object DirectDownloadTracker {
    private val ids = ConcurrentHashMap<Long, String>()

    fun track(systemId: Long, recordId: String) {
        ids[systemId] = recordId
    }

    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val systemId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            val recordId = ids.remove(systemId) ?: return
            val manager = context.getSystemService(DownloadManager::class.java)
            val cursor = manager.query(DownloadManager.Query().setFilterById(systemId))
            cursor.use {
                if (!it.moveToFirst()) {
                    DownloadRegistry.update(recordId) { old ->
                        old.copy(state = DownloadState.FAILED, detail = "找不到下載結果")
                    }
                    return
                }
                val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val reason = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                DownloadRegistry.update(recordId) { old ->
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        old.copy(state = DownloadState.COMPLETED, progress = 100, detail = "下載完成")
                    } else {
                        old.copy(state = DownloadState.FAILED, detail = "下載失敗（代碼 $reason）")
                    }
                }
            }
        }
    }
}

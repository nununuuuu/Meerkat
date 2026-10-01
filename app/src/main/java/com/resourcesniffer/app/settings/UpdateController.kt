package com.resourcesniffer.app.settings

import android.content.Context
import com.resourcesniffer.app.BuildConfig
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal data class UpdateState(
    val automatic: Boolean = true,
    val busy: Boolean = false,
    val downloading: Boolean = false,
    val received: Long = 0,
    val total: Long = 0,
    val message: String = "",
    val release: AppRelease? = null,
    val apk: File? = null,
    val showPrompt: Boolean = false,
)

/** Lives across tab changes and activity recreation; only keeps the application Context. */
internal object UpdateController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(UpdateState())
    val state = mutable.asStateFlow()
    private var initialized = false
    private var lastCheck = 0L
    private var job: Job? = null
    private fun prefs(context: Context) = context.getSharedPreferences("app-updates", Context.MODE_PRIVATE)

    fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        lastCheck = prefs(context).getLong("last-check", 0L)
        mutable.update { it.copy(automatic = prefs(context).getBoolean("automatic", true)) }
    }

    fun onForeground(context: Context) {
        initialize(context)
        if (mutable.value.automatic && !mutable.value.busy && mutable.value.apk == null &&
            System.currentTimeMillis() - lastCheck >= 6 * 60 * 60 * 1000L) check(context, true)
    }

    fun setAutomatic(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("automatic", enabled).apply()
        mutable.update { it.copy(automatic = enabled) }
        if (enabled) { lastCheck = 0; onForeground(context) }
    }

    fun check(context: Context, autoDownload: Boolean = false) {
        if (mutable.value.busy) return
        val app = context.applicationContext
        lastCheck = System.currentTimeMillis()
        prefs(app).edit().putLong("last-check", lastCheck).apply()
        mutable.update { it.copy(busy = true, message = "正在檢查更新…") }
        job = scope.launch {
            try {
                val latest = withContext(Dispatchers.IO) { AppUpdates.latest() }
                if (latest.build <= BuildConfig.BUILD_NUMBER) {
                    mutable.update { it.copy(release = null, apk = null, message = "目前已是最新版本") }
                } else {
                    mutable.update { it.copy(release = latest, apk = if (it.release?.build == latest.build) it.apk else null,
                        message = "可更新至 ${latest.name}") }
                    if (autoDownload && mutable.value.automatic && mutable.value.apk == null) downloadNow(app, latest)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(message = e.message ?: "檢查更新失敗") } }
            finally { mutable.update { it.copy(busy = false, downloading = false) } }
        }
    }

    fun download(context: Context) {
        val release = mutable.value.release ?: return
        if (mutable.value.busy) return
        val app = context.applicationContext
        mutable.update { it.copy(busy = true) }
        job = scope.launch {
            try { downloadNow(app, release) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(message = e.message ?: "更新失敗") } }
            finally { mutable.update { it.copy(busy = false, downloading = false) } }
        }
    }

    private suspend fun downloadNow(context: Context, release: AppRelease) {
        mutable.update { it.copy(downloading = true, received = 0, total = release.size, showPrompt = true, message = "正在 App 內下載更新…") }
        val apk = withContext(Dispatchers.IO) {
            val task = currentCoroutineContext()
            AppUpdates.download(context, release) { received, total ->
                task.ensureActive()
                mutable.update { it.copy(received = received, total = total,
                    message = if (total > 0 && received >= total) "下載完成，正在驗證檔案與簽章…" else "正在 App 內下載更新…") }
            }
        }
        mutable.update { it.copy(apk = apk, downloading = false, message = "更新已下載並驗證完成，請按「安裝更新」") }
    }

    fun cancel() {
        job?.cancel()
        mutable.update { it.copy(message = "已取消更新下載", showPrompt = false) }
    }
    fun dismissPrompt() { mutable.update { it.copy(showPrompt = false) } }
    fun install(context: Context) {
        val apk = mutable.value.apk ?: return
        runCatching { AppUpdates.install(context, apk) }.onSuccess { launched ->
            mutable.update { it.copy(message = if (launched) "已開啟 Android 安裝確認" else "請允許安裝此來源的 App，返回後再按「安裝更新」") }
        }.onFailure { error -> mutable.update { it.copy(message = error.message ?: "無法開啟安裝畫面") } }
    }
}

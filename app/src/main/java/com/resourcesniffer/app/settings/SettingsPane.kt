package com.resourcesniffer.app.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.resourcesniffer.app.BuildConfig
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsPane() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var release by remember { mutableStateOf<AppRelease?>(null) }
    var apk by remember { mutableStateOf<File?>(null) }
    var accounts by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("設定", style = MaterialTheme.typography.headlineSmall)
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("關於", style = MaterialTheme.typography.titleLarge)
                Text("Meerkat")
                Text("版本 ${BuildConfig.VERSION_NAME} · build ${BuildConfig.BUILD_NUMBER}")
                Text("檢查 GitHub 發布的測試版本，下載後由 Android 確認安裝。")
                Button(enabled = !busy, onClick = {
                    scope.launch {
                        busy = true
                        message = "正在檢查更新…"
                        apk = null
                        try {
                            val latest = withContext(Dispatchers.IO) { AppUpdates.latest() }
                            release = latest.takeIf { it.build > BuildConfig.BUILD_NUMBER }
                            message = if (release == null) "目前已是最新版本" else "可更新至 ${latest.name}"
                        } catch (e: Exception) { message = e.message ?: "檢查更新失敗" }
                        finally { busy = false }
                    }
                }) { Text("檢查更新") }
                release?.let { latest ->
                    Button(enabled = !busy, onClick = {
                        scope.launch {
                            busy = true
                            try {
                                if (apk == null) {
                                    message = "正在下載並驗證更新…"
                                    apk = withContext(Dispatchers.IO) { AppUpdates.download(context.applicationContext, latest) }
                                }
                                message = if (AppUpdates.install(context, apk!!)) "已開啟安裝畫面" else "請允許安裝此來源的 App，返回後再按「安裝更新」"
                            } catch (e: Exception) { message = e.message ?: "更新失敗" }
                            finally { busy = false }
                        }
                    }) { Text(if (apk == null) "下載更新" else "安裝更新") }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (message.isNotBlank()) Text(message)
            }
        }
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("帳號設定", style = MaterialTheme.typography.titleLarge)
                Text("預留常用帳號帶入功能。")
                OutlinedButton(onClick = { accounts = true }) { Text("帳號設定") }
            }
        }
    }
    if (accounts) AlertDialog(onDismissRequest = { accounts = false }, title = { Text("帳號設定") },
        text = { Text("常用帳號帶入功能規劃中。目前尚未儲存帳號或密碼。") },
        confirmButton = { TextButton(onClick = { accounts = false }) { Text("知道了") } })
}

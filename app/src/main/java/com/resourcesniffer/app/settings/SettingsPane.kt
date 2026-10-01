package com.resourcesniffer.app.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.resourcesniffer.app.BuildConfig
import java.util.Locale

@Composable
internal fun UpdateProgress(state: UpdateState) {
    if (state.downloading) {
        if (state.total > 0) {
            val fraction = (state.received.toFloat() / state.total).coerceIn(0f, 1f)
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            Text("${(fraction * 100).toInt()}% · " + String.format(Locale.getDefault(), "%.1f / %.1f MB", state.received / 1048576.0, state.total / 1048576.0))
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(String.format(Locale.getDefault(), "已下載 %.1f MB", state.received / 1048576.0))
        }
    } else if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (state.message.isNotBlank()) Text(state.message)
}

@Composable
fun UpdatePrompt() {
    val state by UpdateController.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    if (state.showPrompt) AlertDialog(
        onDismissRequest = UpdateController::dismissPrompt,
        title = { Text("Meerkat 更新") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.release?.let { Text(it.name) }
            UpdateProgress(state)
            Text("下載在 App 內完成，安裝前會顯示 Android 系統確認。")
        } },
        confirmButton = {
            if (state.apk != null && !state.busy) TextButton(onClick = { UpdateController.install(context) }) { Text("安裝更新") }
            else TextButton(onClick = UpdateController::dismissPrompt) { Text("稍後查看") }
        },
        dismissButton = {
            if (state.downloading) TextButton(onClick = UpdateController::cancel) { Text("取消下載") }
            else TextButton(onClick = UpdateController::dismissPrompt) { Text("關閉") }
        },
    )
}

@Composable
fun SettingsPane(onOpenWebsite: (String) -> Unit) {
    val context = LocalContext.current
    val state by UpdateController.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { UpdateController.initialize(context) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("設定", style = MaterialTheme.typography.headlineSmall)
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("關於", style = MaterialTheme.typography.titleLarge)
                Text("Meerkat · ${BuildConfig.VERSION_NAME} · build ${BuildConfig.BUILD_NUMBER}")
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("自動更新", style = MaterialTheme.typography.titleMedium)
                        Text("開啟或返回 App 時自動檢查（每 6 小時一次），有新版便在 App 內下載。下載完成後確認安裝。", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = state.automatic, onCheckedChange = { UpdateController.setAutomatic(context, it) })
                }
                Button(enabled = !state.busy, onClick = { UpdateController.check(context, state.automatic) }) { Text("立即檢查更新") }
                UpdateProgress(state)
                if (state.release != null && !state.busy) {
                    Button(onClick = { if (state.apk != null) UpdateController.install(context) else UpdateController.download(context) }) {
                        Text(if (state.apk != null) "安裝更新" else "下載更新")
                    }
                }
                if (state.downloading) TextButton(onClick = UpdateController::cancel) { Text("取消下載") }
                Text("下載及驗證皆在 App 內完成；Android 最後會要求確認安裝。", style = MaterialTheme.typography.bodySmall)
            }
        }
        WebsiteAccounts(onOpenWebsite)
    }
}

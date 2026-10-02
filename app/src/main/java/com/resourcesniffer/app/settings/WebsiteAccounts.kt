package com.resourcesniffer.app.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID

internal data class WebsiteAccount(val id: String, val name: String, val url: String, val username: String, val password: String = "")

internal fun websiteUrl(input: String): String? = runCatching {
    val text = input.trim().let { if ("://" in it) it else "https://$it" }
    val uri = URI(text)
    require(uri.scheme.lowercase() in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null)
    uri.toASCIIString()
}.getOrNull()

@Composable
internal fun WebsiteAccounts(onOpenWebsite: (String) -> Unit) {
    val context = LocalContext.current
    val vault = remember { AccountVault(context) }
    val loaded = remember { runCatching { vault.load() } }
    var storageError by remember { mutableStateOf(if (loaded.isFailure) "無法讀取已保存帳號，請勿清除 App 資料。" else null) }
    var entries by remember { mutableStateOf(loaded.getOrDefault(emptyList())) }
    fun save(next: List<WebsiteAccount>): Boolean = runCatching {
        vault.save(next)
        entries = next
        storageError = null
        true
    }.getOrElse { storageError = "帳號儲存失敗，請稍後再試。"; false }
    var editing by remember { mutableStateOf<WebsiteAccount?>(null) }
    var deleting by remember { mutableStateOf<WebsiteAccount?>(null) }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("常用網站與帳號", style = MaterialTheme.typography.titleLarge)
            Text("帳號與密碼加密保存在本機。請在內建瀏覽器手動登入網站，登入狀態會保留。", style = MaterialTheme.typography.bodySmall)
            storageError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedButton(enabled = loaded.isSuccess, onClick = { editing = WebsiteAccount(UUID.randomUUID().toString(), "", "", "") }) { Text("新增網站／帳號") }
            entries.forEach { account ->
                HorizontalDivider()
                Text(account.name, style = MaterialTheme.typography.titleMedium)
                Text(account.url, style = MaterialTheme.typography.bodySmall)
                if (account.username.isNotBlank()) Text("帳號：${account.username}")
                Row {
                    TextButton(onClick = { onOpenWebsite(account.url) }) { Text("開啟網站") }
                    TextButton(enabled = account.username.isNotBlank(), onClick = {
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("帳號", account.username))
                    }) { Text("複製帳號") }
                }
                Row {
                    TextButton(onClick = { editing = account }) { Text("編輯") }
                    TextButton(onClick = { deleting = account }) { Text("刪除") }
                }
            }
        }
    }
    editing?.let { account ->
        var name by remember(account.id) { mutableStateOf(account.name) }
        var url by remember(account.id) { mutableStateOf(account.url) }
        var username by remember(account.id) { mutableStateOf(account.username) }
        var password by remember(account.id) { mutableStateOf(account.password) }
        var reveal by remember(account.id) { mutableStateOf(false) }
        var error by remember(account.id) { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { editing = null }, title = { Text("網站與帳號設定") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("名稱") }, singleLine = true)
                OutlinedTextField(url, { url = it; error = false }, label = { Text("網站或登入頁網址") }, singleLine = true, isError = error)
                OutlinedTextField(username, { username = it }, label = { Text("帳號（選填）") }, singleLine = true)
                OutlinedTextField(password, { password = it }, label = { Text("密碼") }, singleLine = true,
                    visualTransformation = if (reveal) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
                    trailingIcon = { TextButton(onClick = { reveal = !reveal }) { Text(if (reveal) "隱藏" else "顯示") } })
                storageError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (error) Text("請填寫名稱與有效的 HTTP／HTTPS 網址。")
            }
        }, confirmButton = { TextButton(onClick = {
            val valid = websiteUrl(url)
            if (name.isBlank() || valid == null) error = true else {
                val updated = account.copy(name = name.trim(), url = valid, username = username.trim(), password = password)
                if (save(if (entries.any { it.id == account.id }) entries.map { if (it.id == account.id) updated else it } else entries + updated)) editing = null
            }
        }) { Text("儲存") } }, dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } })
    }
    deleting?.let { account -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("刪除 ${account.name}？") },
        text = { Text("只刪除此設定，不會登出網站或刪除網站帳號。") },
        confirmButton = { TextButton(onClick = { if (save(entries.filterNot { it.id == account.id })) deleting = null }) { Text("刪除") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
}

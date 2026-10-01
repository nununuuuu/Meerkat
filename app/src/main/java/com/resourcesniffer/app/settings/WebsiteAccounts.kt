package com.resourcesniffer.app.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID

internal data class WebsiteAccount(val id: String, val name: String, val url: String, val username: String)

internal fun websiteUrl(input: String): String? = runCatching {
    val text = input.trim().let { if ("://" in it) it else "https://$it" }
    val uri = URI(text)
    require(uri.scheme.lowercase() in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null)
    uri.toASCIIString()
}.getOrNull()

@Composable
internal fun WebsiteAccounts(onOpenWebsite: (String) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("website_accounts", Context.MODE_PRIVATE) }
    var entries by remember { mutableStateOf(runCatching {
        val array = JSONArray(prefs.getString("entries", "[]"))
        List(array.length()) { index -> array.getJSONObject(index).let {
            WebsiteAccount(it.getString("id"), it.getString("name"), it.getString("url"), it.optString("username"))
        } }
    }.getOrDefault(emptyList())) }
    fun save(next: List<WebsiteAccount>) {
        prefs.edit().putString("entries", JSONArray().apply { next.forEach { account ->
            put(JSONObject().put("id", account.id).put("name", account.name).put("url", account.url).put("username", account.username))
        } }.toString()).apply()
        entries = next
    }
    var editing by remember { mutableStateOf<WebsiteAccount?>(null) }
    var deleting by remember { mutableStateOf<WebsiteAccount?>(null) }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("常用網站與帳號", style = MaterialTheme.typography.titleLarge)
            Text("自行設定網站或登入頁面。帳號可複製後貼上；登入狀態由內建瀏覽器保存，目前不儲存密碼。", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { editing = WebsiteAccount(UUID.randomUUID().toString(), "", "", "") }) { Text("新增網站／帳號") }
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
        var error by remember(account.id) { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { editing = null }, title = { Text("網站與帳號設定") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("名稱") }, singleLine = true)
                OutlinedTextField(url, { url = it; error = false }, label = { Text("網站或登入頁網址") }, singleLine = true, isError = error)
                OutlinedTextField(username, { username = it }, label = { Text("帳號（選填）") }, singleLine = true)
                if (error) Text("請填寫名稱與有效的 HTTP／HTTPS 網址。")
            }
        }, confirmButton = { TextButton(onClick = {
            val valid = websiteUrl(url)
            if (name.isBlank() || valid == null) error = true else {
                val updated = account.copy(name = name.trim(), url = valid, username = username.trim())
                save(if (entries.any { it.id == account.id }) entries.map { if (it.id == account.id) updated else it } else entries + updated)
                editing = null
            }
        }) { Text("儲存") } }, dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } })
    }
    deleting?.let { account -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("刪除 ${account.name}？") },
        text = { Text("只刪除此設定，不會登出網站或刪除網站帳號。") },
        confirmButton = { TextButton(onClick = { save(entries.filterNot { it.id == account.id }); deleting = null }) { Text("刪除") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
}

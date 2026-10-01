package com.resourcesniffer.app.settings

import android.webkit.WebView
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

@Composable
internal fun BrowserAccounts(webView: WebView?) {
    val context = LocalContext.current
    var accounts by remember { mutableStateOf<List<WebsiteAccount>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    TextButton(enabled = webView != null, onClick = {
        runCatching { AccountVault(context).load() }.onSuccess { saved ->
            val origin = webView?.url?.let(::credentialOrigin)
            val matches = saved.filter { origin != null && credentialOrigin(it.url) == origin }
            if (matches.isEmpty()) message = "此 HTTPS 網站尚無已保存帳號，請到設定新增網站的登入頁網址與帳密。"
            else accounts = matches
        }.onFailure { message = "無法解密帳號資料，請到設定檢查。" }
    }) { Text("帶入帳密") }
    accounts?.let { choices -> AlertDialog(onDismissRequest = { accounts = null }, title = { Text("選擇此網站的帳號") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { choices.forEach { account ->
            TextButton(onClick = {
                val view = webView
                if (view?.url?.let(::credentialOrigin) == credentialOrigin(account.url)) {
                    view?.evaluateJavascript(credentialFillScript(account)) { result ->
                        Toast.makeText(context, if (result == "\"filled\"") "已帶入帳密，請確認後登入" else "找不到適合的登入欄位，請確認目前在登入頁面", Toast.LENGTH_LONG).show()
                    }
                }
                accounts = null
            }) { Text("${account.name} · ${account.username}") }
        } } }, confirmButton = { TextButton(onClick = { accounts = null }) { Text("取消") } }) }
    message?.let { text -> AlertDialog(onDismissRequest = { message = null }, title = { Text("網站帳號") }, text = { Text(text) },
        confirmButton = { TextButton(onClick = { message = null }) { Text("知道了") } }) }
}

package com.resourcesniffer.app

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.resourcesniffer.app.core.Resource
import com.resourcesniffer.app.core.ResourceType
import com.resourcesniffer.app.download.DownloadHelper
import com.resourcesniffer.app.ui.MainViewModel

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<MainViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MeerkatApp(viewModel) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MeerkatApp(viewModel: MainViewModel) {
    val context = LocalContext.current
    val resources by viewModel.resources.collectAsStateWithLifecycle()
    var address by remember { mutableStateOf("https://") }
    var webView by remember { mutableStateOf<WebView?>(null) }

    BackHandler(enabled = webView?.canGoBack() == true) {
        webView?.goBack()
    }

    MaterialTheme {
        Scaffold(
            topBar = { TopAppBar(title = { Text("Meerkat 資源嗅探") }) }
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .padding(horizontal = 12.dp)
                    .fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AssistChip(
                    onClick = {},
                    label = { Text("目前模式：內建瀏覽器資源嗅探") }
                )

                Text(
                    "外部 App 的 VPN 嗅探暫時停用，避免未完成的轉送核心造成 App 斷網。你可以在下方直接登入網站並嗅探可下載資源。",
                    style = MaterialTheme.typography.bodySmall
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("網址") }
                    )
                    Button(onClick = {
                        val normalized = normalizeUrl(address)
                        address = normalized
                        webView?.loadUrl(normalized)
                    }) {
                        Text("開啟")
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    IconButton(onClick = { webView?.goBack() }, enabled = webView?.canGoBack() == true) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "上一頁")
                    }
                    IconButton(onClick = { webView?.goForward() }, enabled = webView?.canGoForward() == true) {
                        Icon(Icons.Default.ArrowForward, contentDescription = "下一頁")
                    }
                    IconButton(onClick = { webView?.reload() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "重新整理")
                    }
                    OutlinedButton(onClick = viewModel::clear) {
                        Icon(Icons.Default.Delete, null)
                        Spacer(Modifier.width(4.dp))
                        Text("清空資源")
                    }
                    OutlinedButton(onClick = {
                        if (!Settings.canDrawOverlays(context)) {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                            )
                        } else {
                            viewModel.startOverlay()
                        }
                    }) {
                        Text("懸浮球")
                    }
                }

                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            webView = this
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.databaseEnabled = true
                            settings.mediaPlaybackRequiresUserGesture = false
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                            webViewClient = object : WebViewClient() {
                                override fun shouldInterceptRequest(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): android.webkit.WebResourceResponse? {
                                    request?.url?.toString()?.let(viewModel::recordWebResource)
                                    return null
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    url?.let { address = it }
                                    super.onPageFinished(view, url)
                                }
                            }

                            setDownloadListener { url, _, _, mimeType, _ ->
                                if (!url.isNullOrBlank()) {
                                    viewModel.recordWebResource(url, mimeType)
                                }
                            }
                        }
                    }
                )

                HorizontalDivider()

                val downloadable = resources.filter { it.type != ResourceType.OTHER && !it.url.isNullOrBlank() }
                Text(
                    "已偵測可下載資源：${downloadable.size}",
                    fontWeight = FontWeight.SemiBold
                )

                if (downloadable.isEmpty()) {
                    Text(
                        "尚未偵測到圖片、影片、音訊、文件或串流。請在上方網頁中開啟內容、播放影片或瀏覽圖片。",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(downloadable, key = { it.id }) { resource ->
                            ResourceRow(resource)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResourceRow(resource: Resource) {
    val context = LocalContext.current
    val url = resource.url ?: return

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(resourceTypeLabel(resource.type), fontWeight = FontWeight.SemiBold)
            Text(resource.host, style = MaterialTheme.typography.bodyMedium)
            Text(url, style = MaterialTheme.typography.bodySmall, maxLines = 2)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    runCatching {
                        DownloadHelper.enqueue(
                            context,
                            url,
                            android.webkit.WebSettings.getDefaultUserAgent(context)
                        )
                    }.onSuccess {
                        Toast.makeText(context, "已加入下載佇列", Toast.LENGTH_SHORT).show()
                    }.onFailure {
                        Toast.makeText(context, "無法下載此資源", Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Icon(Icons.Default.Download, null)
                    Spacer(Modifier.width(4.dp))
                    Text("下載")
                }

                OutlinedButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("資源網址", url))
                    Toast.makeText(context, "已複製網址", Toast.LENGTH_SHORT).show()
                }) {
                    Text("複製網址")
                }
            }
        }
    }
}

private fun resourceTypeLabel(type: ResourceType): String = when (type) {
    ResourceType.IMAGE -> "圖片"
    ResourceType.VIDEO -> "影片"
    ResourceType.AUDIO -> "音訊"
    ResourceType.DOCUMENT -> "文件"
    ResourceType.STREAM -> "串流"
    ResourceType.ARCHIVE -> "壓縮檔"
    ResourceType.OTHER -> "其他"
}

private fun normalizeUrl(value: String): String {
    val trimmed = value.trim()
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
    return "https://$trimmed"
}

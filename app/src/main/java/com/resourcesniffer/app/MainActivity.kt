package com.resourcesniffer.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import com.resourcesniffer.app.core.InstalledApp
import com.resourcesniffer.app.core.Resource
import com.resourcesniffer.app.core.ResourceType
import com.resourcesniffer.app.download.DownloadHelper
import com.resourcesniffer.app.ui.MainViewModel
import org.json.JSONArray

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<MainViewModel>()
    private val incomingUrl = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        incomingUrl.value = extractUrl(intent)
        setContent { MeerkatApp(viewModel, incomingUrl.value) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingUrl.value = extractUrl(intent)
    }

    private fun extractUrl(intent: Intent?): String? {
        if (intent == null) return null
        if (intent.action == Intent.ACTION_VIEW) return intent.dataString
        if (intent.action == Intent.ACTION_SEND) {
            return intent.getStringExtra(Intent.EXTRA_TEXT)
                ?.trim()
                ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        }
        return null
    }
}

private enum class MainMode { BROWSER, EXTERNAL, RESOURCES }

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MeerkatApp(
    viewModel: MainViewModel,
    incomingUrl: String?,
) {
    val context = LocalContext.current
    val resources by viewModel.resources.collectAsStateWithLifecycle()
    var mode by remember { mutableStateOf(if (incomingUrl != null) MainMode.BROWSER else MainMode.RESOURCES) }
    var address by remember { mutableStateOf(incomingUrl ?: "https://") }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var selectedPackage by remember { mutableStateOf<String?>(null) }
    var externalCaptureActive by remember { mutableStateOf(false) }

    val apps = remember { viewModel.installedApps() }

    val vpnLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            selectedPackage?.let {
                viewModel.startExternalCapture(it)
                externalCaptureActive = true
                viewModel.startOverlay()
            }
        }
    }

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(incomingUrl) {
        if (!incomingUrl.isNullOrBlank()) {
            address = incomingUrl
            mode = MainMode.BROWSER
            webView?.loadUrl(incomingUrl)
        }
    }

    BackHandler(enabled = mode == MainMode.BROWSER && webView?.canGoBack() == true) {
        webView?.goBack()
    }

    MaterialTheme {
        Scaffold(
            topBar = { TopAppBar(title = { Text("Meerkat 資源嗅探") }) }
        ) { padding ->
            Column(
                Modifier
                    .padding(padding)
                    .padding(horizontal = 12.dp)
                    .fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = mode == MainMode.BROWSER,
                        onClick = { mode = MainMode.BROWSER },
                        shape = SegmentedButtonDefaults.itemShape(0, 3),
                    ) { Text("瀏覽器") }
                    SegmentedButton(
                        selected = mode == MainMode.EXTERNAL,
                        onClick = { mode = MainMode.EXTERNAL },
                        shape = SegmentedButtonDefaults.itemShape(1, 3),
                    ) { Text("外部 App") }
                    SegmentedButton(
                        selected = mode == MainMode.RESOURCES,
                        onClick = { mode = MainMode.RESOURCES },
                        shape = SegmentedButtonDefaults.itemShape(2, 3),
                    ) { Text("資源") }
                }

                when (mode) {
                    MainMode.BROWSER -> BrowserPane(
                        address = address,
                        onAddressChange = { address = it },
                        onWebViewReady = { webView = it },
                        viewModel = viewModel,
                    )
                    MainMode.EXTERNAL -> ExternalAppPane(
                        apps = apps,
                        selectedPackage = selectedPackage,
                        captureActive = externalCaptureActive,
                        onSelected = { selectedPackage = it },
                        onStart = {
                            val pkg = selectedPackage
                            if (pkg == null) {
                                Toast.makeText(context, "請先選擇 App", Toast.LENGTH_SHORT).show()
                            } else {
                                if (Build.VERSION.SDK_INT >= 33) {
                                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                                val prepare = VpnService.prepare(context)
                                if (prepare != null) {
                                    vpnLauncher.launch(prepare)
                                } else {
                                    viewModel.startExternalCapture(pkg)
                                    externalCaptureActive = true
                                    viewModel.startOverlay()
                                }
                            }
                        },
                        onStop = {
                            viewModel.stopExternalCapture()
                            externalCaptureActive = false
                        },
                    )
                    MainMode.RESOURCES -> ResourcePane(
                        resources = resources,
                        onClear = viewModel::clear,
                        onEnableOverlay = {
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
                        },
                    )
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun BrowserPane(
    address: String,
    onAddressChange: (String) -> Unit,
    onWebViewReady: (WebView) -> Unit,
    viewModel: MainViewModel,
) {
    var localAddress by remember(address) { mutableStateOf(address) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "此模式可保留網站登入狀態，適合抓取 HTTPS 圖片、影片、文件與串流網址。",
            style = MaterialTheme.typography.bodySmall,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = localAddress,
                onValueChange = {
                    localAddress = it
                    onAddressChange(it)
                },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("網址") },
            )
            Button(onClick = {
                val url = normalizeUrl(localAddress)
                localAddress = url
                onAddressChange(url)
                webView?.loadUrl(url)
            }) { Text("開啟") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(onClick = { webView?.goBack() }, enabled = webView?.canGoBack() == true) {
                Icon(Icons.Default.ArrowBack, "上一頁")
            }
            IconButton(onClick = { webView?.goForward() }, enabled = webView?.canGoForward() == true) {
                Icon(Icons.Default.ArrowForward, "下一頁")
            }
            IconButton(onClick = { webView?.reload() }) {
                Icon(Icons.Default.Refresh, "重新整理")
            }
            OutlinedButton(onClick = { webView?.let { scanDomResources(it, viewModel) } }) {
                Text("掃描目前頁面")
            }
        }

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    webView = this
                    onWebViewReady(this)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): android.webkit.WebResourceResponse? {
                            val req = request ?: return null
                            val url = req.url.toString()
                            val headers = req.requestHeaders.orEmpty()
                            viewModel.recordWebResource(
                                url = url,
                                mimeType = mimeHint(headers["Accept"]),
                                requestHeaders = headers,
                            )
                            return null
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            url?.let {
                                localAddress = it
                                onAddressChange(it)
                            }
                            view?.let { scanDomResources(it, viewModel) }
                            super.onPageFinished(view, url)
                        }
                    }

                    setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                        if (!url.isNullOrBlank()) {
                            viewModel.recordWebResource(
                                url = url,
                                mimeType = mimeType,
                                requestHeaders = mapOf(
                                    "User-Agent" to (userAgent ?: ""),
                                    "Referer" to (this.url ?: ""),
                                    "Cookie" to (CookieManager.getInstance().getCookie(url) ?: ""),
                                ),
                            )
                        }
                    }

                    if (localAddress != "https://") loadUrl(normalizeUrl(localAddress))
                }
            },
        )
    }
}

@Composable
private fun ExternalAppPane(
    apps: List<InstalledApp>,
    selectedPackage: String?,
    captureActive: Boolean,
    onSelected: (String) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, apps) {
        if (query.isBlank()) apps
        else apps.filter {
            it.label.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true)
        }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "選擇一個 App 後，只有該 App 會走 Meerkat 的本機 VPN。TCP/UDP 會正常轉送，不會像舊版一樣斷網。資源列表只顯示辨識出的檔案，不顯示一般連線。",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "HTTPS 若使用一般 TLS，加密層外無法取得完整 path；需要完整網址時請使用內建瀏覽器/分享網址模式。憑證釘選或 DRM 內容不會嘗試繞過。",
            style = MaterialTheme.typography.bodySmall,
        )

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("搜尋 App") },
            singleLine = true,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onStart, enabled = !captureActive && selectedPackage != null) {
                Text("開始嗅探")
            }
            OutlinedButton(onClick = onStop, enabled = captureActive) {
                Text("停止")
            }
            if (captureActive) {
                AssistChip(onClick = {}, label = { Text("嗅探中") })
            }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(filtered, key = { it.packageName }) { app ->
                val selected = selectedPackage == app.packageName
                ListItem(
                    headlineContent = { Text(app.label) },
                    supportingContent = { Text(app.packageName) },
                    leadingContent = {
                        RadioButton(
                            selected = selected,
                            onClick = { onSelected(app.packageName) },
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                HorizontalDivider()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResourcePane(
    resources: List<Resource>,
    onClear: () -> Unit,
    onEnableOverlay: () -> Unit,
) {
    var selectedType by remember { mutableStateOf<ResourceType?>(null) }
    var query by remember { mutableStateOf("") }

    val visible = remember(resources, selectedType, query) {
        resources.filter { resource ->
            (selectedType == null || resource.type == selectedType) &&
                (query.isBlank() ||
                    resource.url.orEmpty().contains(query, true) ||
                    resource.host.contains(query, true))
        }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onClear) {
                Icon(Icons.Default.Delete, null)
                Spacer(Modifier.width(4.dp))
                Text("清空歷史")
            }
            OutlinedButton(onClick = onEnableOverlay) { Text("啟用懸浮球") }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("搜尋網址或網域") },
            singleLine = true,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(
                null to "全部",
                ResourceType.IMAGE to "圖片",
                ResourceType.VIDEO to "影片",
                ResourceType.AUDIO to "音訊",
                ResourceType.STREAM to "串流",
                ResourceType.DOCUMENT to "文件",
            ).forEach { (type, label) ->
                FilterChip(
                    selected = selectedType == type,
                    onClick = { selectedType = type },
                    label = { Text(label) },
                )
            }
        }

        Text("已保存 ${visible.size} 項可下載資源", fontWeight = FontWeight.SemiBold)

        if (visible.isEmpty()) {
            Text("尚未偵測到符合條件的資源。", style = MaterialTheme.typography.bodySmall)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(visible, key = { it.id }) { ResourceRow(it) }
            }
        }
    }
}

@Composable
private fun ResourceRow(resource: Resource) {
    val context = LocalContext.current
    val url = resource.url ?: return

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(resourceTypeLabel(resource.type), fontWeight = FontWeight.SemiBold)
                resource.sourceAppName?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                resource.sourceAppPackage?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
            }
            Text(resource.host, style = MaterialTheme.typography.bodyMedium)
            Text(url, style = MaterialTheme.typography.bodySmall, maxLines = 3)
            resource.contentLength?.let { Text(formatBytes(it), style = MaterialTheme.typography.labelSmall) }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    runCatching { DownloadHelper.enqueue(context, resource) }
                        .onSuccess { Toast.makeText(context, "已加入下載", Toast.LENGTH_SHORT).show() }
                        .onFailure { Toast.makeText(context, "無法下載：${it.message ?: "未知錯誤"}", Toast.LENGTH_SHORT).show() }
                }) {
                    Icon(Icons.Default.Download, null)
                    Spacer(Modifier.width(4.dp))
                    Text("下載")
                }

                OutlinedButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("資源網址", url))
                    Toast.makeText(context, "已複製網址", Toast.LENGTH_SHORT).show()
                }) { Text("複製網址") }

                OutlinedButton(onClick = {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, url)
                    }
                    context.startActivity(Intent.createChooser(intent, "分享資源"))
                }) { Text("分享") }
            }
        }
    }
}

private fun scanDomResources(webView: WebView, viewModel: MainViewModel) {
    val script = """
        (function() {
          const set = new Set();
          performance.getEntriesByType('resource').forEach(e => set.add(e.name));
          document.querySelectorAll('img[src],video[src],audio[src],source[src],a[href]').forEach(e => {
            const v = e.src || e.href;
            if (v) set.add(v);
          });
          return JSON.stringify(Array.from(set));
        })();
    """.trimIndent()

    webView.evaluateJavascript(script) { raw ->
        runCatching {
            val jsonText = if (raw.startsWith(""")) {
                org.json.JSONTokener(raw).nextValue() as String
            } else raw
            val array = JSONArray(jsonText)
            for (i in 0 until array.length()) {
                val url = array.optString(i)
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    viewModel.recordWebResource(
                        url = url,
                        requestHeaders = mapOf(
                            "Referer" to (webView.url ?: ""),
                            "User-Agent" to webView.settings.userAgentString,
                            "Cookie" to (CookieManager.getInstance().getCookie(url) ?: ""),
                        ),
                    )
                }
            }
        }
    }
}

private fun mimeHint(accept: String?): String? {
    val value = accept?.lowercase() ?: return null
    return when {
        value.contains("image/") -> "image/*"
        value.contains("video/") -> "video/*"
        value.contains("audio/") -> "audio/*"
        value.contains("application/pdf") -> "application/pdf"
        value.contains("mpegurl") -> "application/vnd.apple.mpegurl"
        value.contains("dash+xml") -> "application/dash+xml"
        else -> null
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

private fun formatBytes(value: Long): String = when {
    value >= 1024L * 1024L * 1024L -> "%.2f GB".format(value / (1024.0 * 1024.0 * 1024.0))
    value >= 1024L * 1024L -> "%.2f MB".format(value / (1024.0 * 1024.0))
    value >= 1024L -> "%.1f KB".format(value / 1024.0)
    else -> "$value B"
}

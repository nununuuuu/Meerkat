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
import com.resourcesniffer.app.download.DownloadRecord
import com.resourcesniffer.app.download.DownloadQuality
import com.resourcesniffer.app.download.DownloadRegistry
import com.resourcesniffer.app.download.DownloadState
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

private enum class MainMode { BROWSER, EXTERNAL, RESOURCES, DOWNLOADS }

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MeerkatApp(
    viewModel: MainViewModel,
    incomingUrl: String?,
) {
    val context = LocalContext.current
    val resources by viewModel.resources.collectAsStateWithLifecycle()
    val downloads by DownloadRegistry.items.collectAsStateWithLifecycle()
    val currentSession by viewModel.currentSession.collectAsStateWithLifecycle()
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
                        shape = SegmentedButtonDefaults.itemShape(0, 4),
                    ) { Text("瀏覽器") }
                    SegmentedButton(
                        selected = mode == MainMode.EXTERNAL,
                        onClick = { mode = MainMode.EXTERNAL },
                        shape = SegmentedButtonDefaults.itemShape(1, 4),
                    ) { Text("外部 App") }
                    SegmentedButton(
                        selected = mode == MainMode.RESOURCES,
                        onClick = { mode = MainMode.RESOURCES },
                        shape = SegmentedButtonDefaults.itemShape(2, 4),
                    ) { Text("資源") }
                    SegmentedButton(
                        selected = mode == MainMode.DOWNLOADS,
                        onClick = { mode = MainMode.DOWNLOADS },
                        shape = SegmentedButtonDefaults.itemShape(3, 4),
                    ) { Text("下載") }
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
                        currentSessionId = currentSession?.id,
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
                    MainMode.DOWNLOADS -> DownloadsPane(downloads)
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
    currentSessionId: Long?,
    onClear: () -> Unit,
    onEnableOverlay: () -> Unit,
) {
    var selectedType by remember { mutableStateOf<ResourceType?>(null) }
    var query by remember { mutableStateOf("") }
    var currentOnly by remember { mutableStateOf(true) }

    val visible = remember(resources, selectedType, query, currentOnly, currentSessionId) {
        resources.filter { resource ->
            (!currentOnly || currentSessionId == null || resource.sessionId == currentSessionId) &&
                (selectedType == null || resource.type == selectedType) &&
                (query.isBlank() ||
                    resource.url.orEmpty().contains(query, true) ||
                    resource.host.contains(query, true))
        }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = currentOnly,
                onClick = { currentOnly = !currentOnly },
                label = { Text(if (currentOnly) "本次嗅探" else "全部歷史") },
            )
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
    var showPreview by remember { mutableStateOf(false) }
    var showQualityDialog by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(resourceTypeLabel(resource.type), fontWeight = FontWeight.SemiBold)
                resource.sourceAppName?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                resource.sourceAppPackage?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
            }
            Text(resource.host, style = MaterialTheme.typography.bodyMedium)
            Text(url, style = MaterialTheme.typography.bodySmall, maxLines = 3)
            resource.mimeType?.let { Text("MIME：$it", style = MaterialTheme.typography.labelSmall) }
            resource.extension?.let { Text("格式：$it", style = MaterialTheme.typography.labelSmall) }
            resource.contentLength?.let { Text("大小：${formatBytes(it)}", style = MaterialTheme.typography.labelSmall) }
            if (resource.width != null && resource.height != null) {
                Text("解析度：${resource.width} × ${resource.height}", style = MaterialTheme.typography.labelSmall)
            }
            resource.durationMs?.takeIf { it > 0 }?.let {
                Text("時長：${formatDuration(it)}", style = MaterialTheme.typography.labelSmall)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    if (resource.type == ResourceType.STREAM) {
                        showQualityDialog = true
                    } else {
                        runCatching { DownloadHelper.enqueue(context, resource) }
                            .onSuccess { Toast.makeText(context, "已加入下載", Toast.LENGTH_SHORT).show() }
                            .onFailure { Toast.makeText(context, "無法下載：${it.message ?: "未知錯誤"}", Toast.LENGTH_SHORT).show() }
                    }
                }) {
                    Icon(Icons.Default.Download, null)
                    Spacer(Modifier.width(4.dp))
                    Text("下載")
                }

                if (resource.type == ResourceType.IMAGE ||
                    resource.type == ResourceType.VIDEO ||
                    resource.type == ResourceType.AUDIO
                ) {
                    OutlinedButton(onClick = { showPreview = true }) {
                        Text("預覽")
                    }
                }

                OutlinedButton(onClick = { showDetails = true }) {
                    Text("詳情")
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

    if (showPreview) {
        ResourcePreviewDialog(
            resource = resource,
            onDismiss = { showPreview = false },
        )
    }

    if (showDetails) {
        AlertDialog(
            onDismissRequest = { showDetails = false },
            confirmButton = {
                TextButton(onClick = { showDetails = false }) { Text("關閉") }
            },
            title = { Text("資源詳情") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("類型：${resourceTypeLabel(resource.type)}")
                    Text("來源：${resource.sourceAppName ?: resource.sourceAppPackage ?: "內建瀏覽器"}")
                    Text("Session：${resource.sessionId}")
                    Text("網域：${resource.host}")
                    resource.mimeType?.let { Text("MIME：$it") }
                    resource.extension?.let { Text("格式：$it") }
                    resource.contentLength?.let { Text("大小：${formatBytes(it)}") }
                    if (resource.width != null && resource.height != null) {
                        Text("解析度：${resource.width} × ${resource.height}")
                    }
                    resource.durationMs?.takeIf { it > 0 }?.let {
                        Text("時長：${formatDuration(it)}")
                    }
                    resource.streamType?.let { Text("串流：${it.name}") }
                    Text("Cookie：${if (resource.cookie.isNullOrBlank()) "無" else "有"}")
                    Text("Referer：${if (resource.referer.isNullOrBlank()) "無" else "有"}")
                    Text("User-Agent：${if (resource.userAgent.isNullOrBlank()) "無" else "有"}")
                    Text(resource.url.orEmpty(), style = MaterialTheme.typography.bodySmall)
                }
            },
        )
    }

    if (showQualityDialog) {
        AlertDialog(
            onDismissRequest = { showQualityDialog = false },
            title = { Text("選擇串流畫質") },
            text = { Text("最高畫質會優先選擇較高解析度/bitrate；省流量會選擇較低畫質。") },
            confirmButton = {
                Button(onClick = {
                    showQualityDialog = false
                    runCatching {
                        DownloadHelper.enqueue(context, resource, DownloadQuality.HIGH)
                    }.onSuccess {
                        Toast.makeText(context, "已加入最高畫質下載", Toast.LENGTH_SHORT).show()
                    }.onFailure {
                        Toast.makeText(context, "無法下載：${it.message ?: "未知錯誤"}", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("最高畫質") }
            },
            dismissButton = {
                OutlinedButton(onClick = {
                    showQualityDialog = false
                    runCatching {
                        DownloadHelper.enqueue(context, resource, DownloadQuality.LOW)
                    }.onSuccess {
                        Toast.makeText(context, "已加入省流量下載", Toast.LENGTH_SHORT).show()
                    }.onFailure {
                        Toast.makeText(context, "無法下載：${it.message ?: "未知錯誤"}", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("省流量") }
            },
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ResourcePreviewDialog(
    resource: Resource,
    onDismiss: () -> Unit,
) {
    val url = resource.url ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("關閉") }
        },
        title = { Text("資源預覽") },
        text = {
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        CookieManager.getInstance().setAcceptCookie(true)
                        val safe = org.json.JSONObject.quote(url)
                        val html = when (resource.type) {
                            ResourceType.IMAGE ->
                                "<html><body style='margin:0;background:#111;display:flex;align-items:center;justify-content:center'><img src=" +
                                    safe +
                                    " style='max-width:100%;max-height:100%;object-fit:contain'/></body></html>"
                            ResourceType.VIDEO ->
                                "<html><body style='margin:0;background:#111'><video src=" +
                                    safe +
                                    " controls autoplay style='width:100%;height:100%'></video></body></html>"
                            ResourceType.AUDIO ->
                                "<html><body><audio src=" + safe + " controls autoplay style='width:100%'></audio></body></html>"
                            else -> "<html><body>此類型不支援內建預覽</body></html>"
                        }
                        loadDataWithBaseURL(url, html, "text/html", "UTF-8", null)
                    }
                },
            )
        },
    )
}

@Composable
private fun DownloadsPane(downloads: List<DownloadRecord>) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("下載管理", fontWeight = FontWeight.SemiBold)
            OutlinedButton(onClick = { DownloadRegistry.clearCompleted() }) {
                Text("清除已結束")
            }
        }

        if (downloads.isEmpty()) {
            Text("目前沒有下載任務。", style = MaterialTheme.typography.bodySmall)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(downloads, key = { it.id }) { item ->
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(item.displayName, fontWeight = FontWeight.SemiBold)
                            Text(downloadStateLabel(item.state), style = MaterialTheme.typography.bodySmall)
                            item.detail?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                            item.progress?.let { progress ->
                                LinearProgressIndicator(
                                    progress = { progress / 100f },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text("$progress%", style = MaterialTheme.typography.labelSmall)
                            }
                            Text(item.url, style = MaterialTheme.typography.labelSmall, maxLines = 2)

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (item.state == DownloadState.QUEUED || item.state == DownloadState.DOWNLOADING) {
                                    OutlinedButton(onClick = {
                                        DownloadHelper.cancel(context, item)
                                    }) {
                                        Text("取消")
                                    }
                                }
                                if (item.state == DownloadState.FAILED || item.state == DownloadState.CANCELLED) {
                                    Button(onClick = {
                                        DownloadHelper.retry(context, item)
                                    }) {
                                        Text("重試")
                                    }
                                }
                                if (item.state == DownloadState.COMPLETED && !item.localUri.isNullOrBlank()) {
                                    Button(onClick = {
                                        openDownloadedFile(context, item)
                                    }) {
                                        Text("開啟")
                                    }
                                    OutlinedButton(onClick = {
                                        shareDownloadedFile(context, item)
                                    }) {
                                        Text("分享檔案")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun openDownloadedFile(context: Context, item: DownloadRecord) {
    val uri = item.localUri?.let(Uri::parse) ?: return
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, item.mimeType ?: "*/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(intent)
    }.onFailure {
        Toast.makeText(context, "沒有可開啟此檔案的 App", Toast.LENGTH_SHORT).show()
    }
}

private fun shareDownloadedFile(context: Context, item: DownloadRecord) {
    val uri = item.localUri?.let(Uri::parse) ?: return
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = item.mimeType ?: "*/*"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, "分享下載檔案"))
    }.onFailure {
        Toast.makeText(context, "無法分享此檔案", Toast.LENGTH_SHORT).show()
    }
}

private fun downloadStateLabel(state: DownloadState): String = when (state) {
    DownloadState.QUEUED -> "等待中"
    DownloadState.DOWNLOADING -> "下載中"
    DownloadState.COMPLETED -> "已完成"
    DownloadState.FAILED -> "失敗"
    DownloadState.CANCELLED -> "已取消"
}

private fun scanDomResources(webView: WebView, viewModel: MainViewModel) {
    val script = """
        (function() {
          const map = new Map();
          function put(url, kind, w, h, duration) {
            if (!url) return;
            const old = map.get(url) || {url:url, kind:null, width:null, height:null, duration:null};
            if (kind) old.kind = kind;
            if (w > 0) old.width = w;
            if (h > 0) old.height = h;
            if (isFinite(duration) && duration > 0) old.duration = duration;
            map.set(url, old);
          }

          performance.getEntriesByType('resource').forEach(e => put(e.name, null, null, null, null));
          document.querySelectorAll('img[src]').forEach(e => put(e.currentSrc || e.src, 'image', e.naturalWidth, e.naturalHeight, null));
          document.querySelectorAll('video[src],video source[src]').forEach(e => {
            const video = e.tagName === 'VIDEO' ? e : e.closest('video');
            put(e.currentSrc || e.src, 'video', video ? video.videoWidth : null, video ? video.videoHeight : null, video ? video.duration : null);
          });
          document.querySelectorAll('audio[src],audio source[src]').forEach(e => {
            const audio = e.tagName === 'AUDIO' ? e : e.closest('audio');
            put(e.currentSrc || e.src, 'audio', null, null, audio ? audio.duration : null);
          });
          document.querySelectorAll('a[href]').forEach(e => put(e.href, null, null, null, null));
          return JSON.stringify(Array.from(map.values()));
        })();
    """.trimIndent()

    webView.evaluateJavascript(script) { raw ->
        runCatching {
            val jsonText = if (raw.startsWith("\"")) {
                org.json.JSONTokener(raw).nextValue() as String
            } else raw
            val array = JSONArray(jsonText)
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val url = item.optString("url")
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    val kind = item.optString("kind")
                    val mime = when (kind) {
                        "image" -> "image/*"
                        "video" -> "video/*"
                        "audio" -> "audio/*"
                        else -> null
                    }
                    val width = item.optInt("width").takeIf { it > 0 }
                    val height = item.optInt("height").takeIf { it > 0 }
                    val durationMs = item.optDouble("duration")
                        .takeIf { it.isFinite() && it > 0 }
                        ?.let { (it * 1000).toLong() }

                    viewModel.recordWebResource(
                        url = url,
                        mimeType = mime,
                        requestHeaders = mapOf(
                            "Referer" to (webView.url ?: ""),
                            "User-Agent" to webView.settings.userAgentString,
                            "Cookie" to (CookieManager.getInstance().getCookie(url) ?: ""),
                        ),
                        width = width,
                        height = height,
                        durationMs = durationMs,
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

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

private fun formatBytes(value: Long): String = when {
    value >= 1024L * 1024L * 1024L -> "%.2f GB".format(value / (1024.0 * 1024.0 * 1024.0))
    value >= 1024L * 1024L -> "%.2f MB".format(value / (1024.0 * 1024.0))
    value >= 1024L -> "%.1f KB".format(value / 1024.0)
    else -> "$value B"
}

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
import android.webkit.WebResourceError
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import android.widget.ImageView
import android.widget.VideoView
import android.widget.MediaController
import android.graphics.BitmapFactory
import java.net.HttpURLConnection
import java.net.URL
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Stream
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
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
import com.resourcesniffer.app.ui.theme.MeerkatTheme
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
            val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            return Regex("""https?://[^\s]+""", RegexOption.IGNORE_CASE)
                .find(text)
                ?.value
                ?.trimEnd('.', ',', ')', ']', '}', '>', '，', '。')
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
    var address by remember { mutableStateOf(incomingUrl ?: "https://www.google.com/") }
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

    MeerkatTheme {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Meerkat", fontWeight = FontWeight.SemiBold)
                            Text(
                                when {
                                    externalCaptureActive -> "正在嗅探外部 App"
                                    currentSession != null -> "已建立嗅探工作階段"
                                    else -> "資源嗅探與下載"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                )
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = mode == MainMode.BROWSER,
                        onClick = { mode = MainMode.BROWSER },
                        icon = { Icon(Icons.Default.Public, null) },
                        label = { Text("瀏覽器") },
                    )
                    NavigationBarItem(
                        selected = mode == MainMode.RESOURCES,
                        onClick = { mode = MainMode.RESOURCES },
                        icon = { Icon(Icons.Default.Collections, null) },
                        label = { Text("資源") },
                    )
                    NavigationBarItem(
                        selected = mode == MainMode.DOWNLOADS,
                        onClick = { mode = MainMode.DOWNLOADS },
                        icon = { Icon(Icons.Default.DownloadForOffline, null) },
                        label = { Text("下載") },
                    )
                }
            },
        ) { padding ->
            Column(
                Modifier
                    .padding(padding)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (mode) {
                    MainMode.BROWSER -> BrowserPane(
                        address = address,
                        onAddressChange = { address = it },
                        onWebViewReady = { webView = it },
                        viewModel = viewModel,
                        resources = resources,
                        currentSessionId = currentSession?.id,
                        onOpenResources = { mode = MainMode.RESOURCES },
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
    resources: List<Resource>,
    currentSessionId: Long?,
    onOpenResources: () -> Unit,
) {
    var localAddress by remember(address) { mutableStateOf(address) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var loading by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var pageError by remember { mutableStateOf<String?>(null) }

    val liveResources = remember(resources, currentSessionId) {
        if (currentSessionId == null) emptyList()
        else resources.filter { it.sessionId == currentSessionId }
    }
    val imageCount = liveResources.count { it.type == ResourceType.IMAGE }
    val videoCount = liveResources.count {
        it.type == ResourceType.VIDEO || it.type == ResourceType.STREAM
    }

    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "自動嗅探",
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                AssistChip(onClick = {}, label = { Text("圖片 $imageCount") })
                AssistChip(onClick = {}, label = { Text("影片 $videoCount") })
                TextButton(onClick = onOpenResources) { Text("查看") }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
                pageError = null
                webView?.loadUrl(url)
            }) { Text("開啟") }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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

        if (loading) {
            LinearProgressIndicator(
                progress = { (progress.coerceIn(0, 100)) / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        pageError?.let { error ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(
                    text = error,
                    modifier = Modifier.padding(10.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        AndroidView(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            factory = { ctx ->
                WebView(ctx).apply {
                    webView = this
                    onWebViewReady(this)
                    setBackgroundColor(android.graphics.Color.WHITE)

                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.loadsImagesAutomatically = true
                    settings.blockNetworkImage = false
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = false
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    settings.javaScriptCanOpenWindowsAutomatically = true
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            progress = newProgress
                            loading = newProgress < 100
                            super.onProgressChanged(view, newProgress)
                        }
                    }

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): Boolean {
                            return false
                        }

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): android.webkit.WebResourceResponse? {
                            val req = request ?: return null
                            val url = req.url.toString()
                            val headers = req.requestHeaders.orEmpty()
                            viewModel.recordWebResource(
                                url = url,
                                mimeType = null,
                                requestHeaders = headers,
                            )
                            return null
                        }

                        override fun onPageStarted(
                            view: WebView?,
                            url: String?,
                            favicon: android.graphics.Bitmap?,
                        ) {
                            loading = true
                            pageError = null
                            super.onPageStarted(view, url, favicon)
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?,
                        ) {
                            if (request?.isForMainFrame == true) {
                                pageError = "網頁載入失敗：${error?.description ?: "未知錯誤"}"
                                loading = false
                            }
                            super.onReceivedError(view, request, error)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            loading = false
                            url?.let {
                                localAddress = it
                                onAddressChange(it)
                            }
                            view?.let { scanDomResources(it, viewModel) }
                            super.onPageFinished(view, url)
                        }
                    }

                    setDownloadListener { url, userAgent, _, mimeType, _ ->
                        if (!url.isNullOrBlank()) {
                            viewModel.recordWebResource(
                                url = url,
                                mimeType = mimeType,
                                requestHeaders = mapOf(
                                    "User-Agent" to (userAgent ?: settings.userAgentString.orEmpty()),
                                    "Referer" to (this.url ?: ""),
                                    "Cookie" to (CookieManager.getInstance().getCookie(url) ?: ""),
                                ),
                            )
                        }
                    }

                    loadUrl(normalizeUrl(localAddress))
                }
            },
            update = { view ->
                webView = view
                onWebViewReady(view)
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
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Column(
                Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("外部 App 相容嗅探", fontWeight = FontWeight.SemiBold)
                Text(
                    "這個模式會把你選定 App 的流量正常轉送；只有能看見完整 HTTP URL 的請求才能直接辨識成可下載資源。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "多數 App 使用 HTTPS，完整資源路徑位於 TLS 加密內，因此可能顯示 0。若 App 可分享文章/頁面網址，請分享至 Meerkat 後用內建瀏覽器抓取 HTTPS 資源。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("搜尋 App") },
            singleLine = true,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onStart, enabled = !captureActive && selectedPackage != null) {
                Text("啟動相容嗅探")
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
        }.sortedWith(
            compareBy<Resource> {
                when (it.type) {
                    ResourceType.VIDEO, ResourceType.STREAM -> 0
                    ResourceType.IMAGE -> 1
                    ResourceType.AUDIO -> 2
                    ResourceType.DOCUMENT -> 3
                    ResourceType.ARCHIVE -> 4
                    ResourceType.OTHER -> 5
                }
            }.thenByDescending {
                (it.width?.toLong() ?: 0L) * (it.height?.toLong() ?: 0L)
            }.thenByDescending { it.contentLength ?: 0L }
        )
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
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

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Box(
                        modifier = Modifier.size(42.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = resourceTypeIcon(resource.type),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }

                Column(Modifier.weight(1f)) {
                    Text(
                        resourceSummaryTitle(resource),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        resource.sourceAppName ?: resource.host,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                AssistChip(
                    onClick = { showDetails = true },
                    label = { Text(resourceTypeLabel(resource.type)) },
                )
            }

            Text(
                resourceSummaryLine(resource),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                url,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 2,
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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

                TextButton(onClick = { showDetails = true }) {
                    Text("詳情")
                }

                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("資源網址", url))
                    Toast.makeText(context, "已複製網址", Toast.LENGTH_SHORT).show()
                }) {
                    Text("複製")
                }
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

private fun resourceTypeIcon(type: ResourceType) = when (type) {
    ResourceType.IMAGE -> Icons.Default.Image
    ResourceType.VIDEO -> Icons.Default.Movie
    ResourceType.AUDIO -> Icons.Default.Audiotrack
    ResourceType.DOCUMENT -> Icons.Default.Description
    ResourceType.STREAM -> Icons.Default.Stream
    ResourceType.ARCHIVE -> Icons.Default.FolderZip
    ResourceType.OTHER -> Icons.Default.Collections
}

private fun resourceSummaryTitle(resource: Resource): String {
    val resolution = if (resource.width != null && resource.height != null) {
        "${resource.width}×${resource.height}"
    } else null
    return listOfNotNull(
        resolution,
        resource.extension?.uppercase(),
        resource.mimeType?.substringAfter('/'),
    ).firstOrNull() ?: resourceTypeLabel(resource.type)
}

private fun resourceSummaryLine(resource: Resource): String {
    val parts = buildList {
        resource.contentLength?.let { add(formatBytes(it)) }
        resource.durationMs?.takeIf { it > 0 }?.let { add(formatDuration(it)) }
        resource.streamType?.let { add(it.name) }
        if (isEmpty()) add(resource.host)
    }
    return parts.joinToString(" · ")
}

@Composable
private fun ResourcePreviewDialog(
    resource: Resource,
    onDismiss: () -> Unit,
) {
    val url = resource.url ?: return
    var previewError by remember(resource.id) { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("關閉") }
        },
        title = { Text("資源預覽") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (resource.type) {
                    ResourceType.IMAGE -> {
                        AndroidView(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(360.dp),
                            factory = { context ->
                                ImageView(context).apply {
                                    setBackgroundColor(android.graphics.Color.DKGRAY)
                                    scaleType = ImageView.ScaleType.FIT_CENTER

                                    Thread {
                                        runCatching {
                                            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                                                instanceFollowRedirects = true
                                                connectTimeout = 15_000
                                                readTimeout = 20_000
                                                setRequestProperty("Accept", "image/*")
                                                resource.userAgent?.takeIf { it.isNotBlank() }?.let {
                                                    setRequestProperty("User-Agent", it)
                                                }
                                                resource.referer?.takeIf { it.isNotBlank() }?.let {
                                                    setRequestProperty("Referer", it)
                                                }
                                                resource.cookie?.takeIf { it.isNotBlank() }?.let {
                                                    setRequestProperty("Cookie", it)
                                                }
                                            }
                                            connection.connect()
                                            if (connection.responseCode !in 200..299) {
                                                throw IllegalStateException("HTTP ${connection.responseCode}")
                                            }
                                            connection.inputStream.use { BitmapFactory.decodeStream(it) }
                                                ?: throw IllegalStateException("不是可解碼的圖片")
                                        }.onSuccess { bitmap ->
                                            post {
                                                setImageBitmap(bitmap)
                                                previewError = null
                                            }
                                        }.onFailure { error ->
                                            post {
                                                previewError = "圖片預覽失敗：${error.message ?: "未知錯誤"}"
                                            }
                                        }
                                    }.start()
                                }
                            },
                        )
                    }

                    ResourceType.VIDEO, ResourceType.AUDIO -> {
                        AndroidView(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(360.dp),
                            factory = { context ->
                                VideoView(context).apply {
                                    setBackgroundColor(android.graphics.Color.DKGRAY)
                                    val controller = MediaController(context)
                                    controller.setAnchorView(this)
                                    setMediaController(controller)

                                    val headers = buildMap {
                                        resource.userAgent?.takeIf { it.isNotBlank() }?.let {
                                            put("User-Agent", it)
                                        }
                                        resource.referer?.takeIf { it.isNotBlank() }?.let {
                                            put("Referer", it)
                                        }
                                        resource.cookie?.takeIf { it.isNotBlank() }?.let {
                                            put("Cookie", it)
                                        }
                                    }

                                    setOnPreparedListener {
                                        previewError = null
                                        start()
                                    }
                                    setOnErrorListener { _, what, extra ->
                                        previewError = "媒體預覽失敗：what=$what extra=$extra"
                                        true
                                    }
                                    setVideoURI(Uri.parse(url), headers)
                                    requestFocus()
                                }
                            },
                        )
                    }

                    else -> {
                        Text("此類型目前不支援內建預覽。")
                    }
                }

                previewError?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Text(
                    url,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                )
            }
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

          function abs(url) {
            if (!url) return null;
            try { return new URL(url, document.baseURI).href; } catch (_) { return null; }
          }

          function put(url, kind, w, h, duration) {
            url = abs(url);
            if (!url || (!url.startsWith('http://') && !url.startsWith('https://'))) return;
            const old = map.get(url) || {url:url, kind:null, width:null, height:null, duration:null};
            if (kind) old.kind = kind;
            if (Number(w) > Number(old.width || 0)) old.width = Number(w);
            if (Number(h) > Number(old.height || 0)) old.height = Number(h);
            if (isFinite(duration) && Number(duration) > Number(old.duration || 0)) old.duration = Number(duration);
            map.set(url, old);
          }

          function srcsetEntries(value) {
            if (!value) return [];
            return value.split(',').map(part => {
              const bits = part.trim().split(/\s+/);
              const descriptor = bits[1] || '';
              const width = descriptor.endsWith('w') ? parseInt(descriptor, 10) : null;
              return { url: bits[0], width: width };
            });
          }

          performance.getEntriesByType('resource').forEach(e => put(e.name, null, null, null, null));

          document.querySelectorAll('img').forEach(e => {
            put(e.currentSrc || e.src, 'image', e.naturalWidth, e.naturalHeight, null);
            srcsetEntries(e.getAttribute('srcset')).forEach(x =>
              put(x.url, 'image', x.width, e.naturalHeight, null)
            );
          });

          document.querySelectorAll('picture source[srcset]').forEach(e => {
            srcsetEntries(e.getAttribute('srcset')).forEach(x =>
              put(x.url, 'image', x.width, null, null)
            );
          });

          document.querySelectorAll('video').forEach(video => {
            put(video.currentSrc || video.src, 'video', video.videoWidth, video.videoHeight, video.duration);
            if (video.poster) put(video.poster, 'image', video.videoWidth, video.videoHeight, null);
            video.querySelectorAll('source[src]').forEach(e =>
              put(e.src, 'video', video.videoWidth, video.videoHeight, video.duration)
            );
          });

          document.querySelectorAll('audio').forEach(audio => {
            put(audio.currentSrc || audio.src, 'audio', null, null, audio.duration);
            audio.querySelectorAll('source[src]').forEach(e =>
              put(e.src, 'audio', null, null, audio.duration)
            );
          });

          [
            ['meta[property="og:image"]', 'image'],
            ['meta[property="og:image:url"]', 'image'],
            ['meta[name="twitter:image"]', 'image'],
            ['meta[property="og:video"]', 'video'],
            ['meta[property="og:video:url"]', 'video'],
            ['meta[property="og:video:secure_url"]', 'video']
          ].forEach(pair => {
            document.querySelectorAll(pair[0]).forEach(e => put(e.content, pair[1], null, null, null));
          });

          document.querySelectorAll('*').forEach(e => {
            const bg = getComputedStyle(e).backgroundImage;
            if (!bg || bg === 'none') return;
            const matches = bg.match(/url\((['"]?)(.*?)\1\)/g) || [];
            matches.forEach(m => {
              const hit = m.match(/url\((['"]?)(.*?)\1\)/);
              if (hit && hit[2]) put(hit[2], 'image', null, null, null);
            });
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

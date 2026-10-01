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
import android.os.Message
import android.view.View
import android.provider.Settings
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.RenderProcessGoneDetail
import android.widget.Toast
import android.widget.ImageView
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import com.resourcesniffer.app.capture.CaptureStatus
import com.resourcesniffer.app.capture.CaptureSnapshot
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Settings
import com.resourcesniffer.app.settings.SettingsPane
import androidx.compose.material.icons.filled.Stream
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.resourcesniffer.app.core.Resource
import com.resourcesniffer.app.core.ResourceType
import com.resourcesniffer.app.core.StreamType
import com.resourcesniffer.app.core.MediaPreviewSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.resourcesniffer.app.core.ValidationState
import com.resourcesniffer.app.download.DownloadHelper
import com.resourcesniffer.app.download.DownloadRecord
import com.resourcesniffer.app.download.DownloadQuality
import com.resourcesniffer.app.download.DownloadRegistry
import com.resourcesniffer.app.download.DownloadState
import com.resourcesniffer.app.overlay.OverlayService
import com.resourcesniffer.app.ui.MainViewModel
import com.resourcesniffer.app.ui.theme.MeerkatTheme
import org.json.JSONArray

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<MainViewModel>()
    private val incomingUrl = mutableStateOf<String?>(null)
    private val resourceRequest = mutableStateOf(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        incomingUrl.value = extractUrl(intent)
        if (intent.getBooleanExtra("open_resources", false)) resourceRequest.value = System.nanoTime()
        setContent { MeerkatApp(viewModel, incomingUrl.value, resourceRequest.value) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("open_resources", false)) resourceRequest.value = System.nanoTime()
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

private enum class MainMode { BROWSER, EXTERNAL, RESOURCES, DOWNLOADS, SETTINGS }

private enum class UiResourceCategory(val label: String) {
    IMAGE("圖片"),
    VIDEO("影片"),
    DOCUMENT("文件"),
    OTHER("其他"),
}

private fun Resource.uiCategory(): UiResourceCategory = when (type) {
    ResourceType.IMAGE -> UiResourceCategory.IMAGE
    ResourceType.VIDEO, ResourceType.STREAM -> UiResourceCategory.VIDEO
    ResourceType.DOCUMENT -> UiResourceCategory.DOCUMENT
    ResourceType.AUDIO, ResourceType.ARCHIVE, ResourceType.OTHER -> UiResourceCategory.OTHER
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MeerkatApp(
    viewModel: MainViewModel,
    incomingUrl: String?,
    resourceRequest: Long,
) {
    val context = LocalContext.current
    val resources by viewModel.resources.collectAsStateWithLifecycle()
    val downloads by DownloadRegistry.items.collectAsStateWithLifecycle()
    val currentSession by viewModel.currentSession.collectAsStateWithLifecycle()
    val browserSession by viewModel.browserSession.collectAsStateWithLifecycle()
    val externalSession by viewModel.externalSession.collectAsStateWithLifecycle()
    val captureStatus by CaptureStatus.state.collectAsStateWithLifecycle()
    val externalCaptureActive = captureStatus.running
    var mode by remember { mutableStateOf(if (incomingUrl != null) MainMode.BROWSER else MainMode.RESOURCES) }
    var address by remember { mutableStateOf(incomingUrl.orEmpty()) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var blockQuic by remember { mutableStateOf(captureStatus.blockQuic) }
    var inspectHttps by remember { mutableStateOf(captureStatus.httpsEnabled) }
    LaunchedEffect(captureStatus.running, captureStatus.httpsEnabled, captureStatus.blockQuic) {
        if (captureStatus.running) {
            blockQuic = captureStatus.blockQuic
            inspectHttps = captureStatus.httpsEnabled
        }
    }
    val overlayRunning by OverlayService.running.collectAsStateWithLifecycle()
    var overlayWanted by remember { mutableStateOf(false) }
    var manualCaConfirmed by remember { mutableStateOf(viewModel.isMitmCaManuallyConfirmed()) }
    var showCaConfirmation by remember { mutableStateOf(false) }
    var mitmCaInstalled by remember { mutableStateOf(viewModel.isMitmCaInstalled()) }

    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        mitmCaInstalled = viewModel.isMitmCaInstalled()
        manualCaConfirmed = viewModel.isMitmCaManuallyConfirmed()
    }

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val caSettingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        mitmCaInstalled = viewModel.isMitmCaInstalled()
        manualCaConfirmed = viewModel.isMitmCaManuallyConfirmed()
    }
    val overlayLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (overlayWanted && Settings.canDrawOverlays(context)) {
            viewModel.startOverlay()
        }
    }

    val setOverlay: (Boolean) -> Unit = { enabled ->
        overlayWanted = enabled
        if (!enabled) {
            viewModel.stopOverlay()
        } else if (Settings.canDrawOverlays(context)) {
            viewModel.startOverlay()
        } else {
            overlayLauncher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")))
        }
    }

    val beginGlobalCapture: () -> Unit = {
        viewModel.startExternalCapture(blockQuic, inspectHttps)
    }

    val vpnLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            beginGlobalCapture()
        }
    }

    LaunchedEffect(incomingUrl) {
        if (!incomingUrl.isNullOrBlank()) {
            address = incomingUrl
            mode = MainMode.BROWSER
        }
    }

    LaunchedEffect(resourceRequest) {
        if (resourceRequest != 0L) mode = MainMode.RESOURCES
    }

    BackHandler(enabled = mode == MainMode.BROWSER && webView?.canGoBack() == true) {
        webView?.goBack()
    }

    MeerkatTheme {
        if (showCaConfirmation) {
            AlertDialog(
                onDismissRequest = { showCaConfirmation = false },
                title = { Text("確認已安裝目前的 CA") },
                text = { Text("請確認安裝的是本版本匯出的 Meerkat-Local-CA.crt。重新安裝 App 後，舊憑證可能已不相符。確認後請重新開始全域嗅探。手動確認不代表其他 App 一定信任憑證。") },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.confirmMitmCaInstalled()
                        manualCaConfirmed = true
                        showCaConfirmation = false
                    }) { Text("已安裝，啟用嘗試") }
                },
                dismissButton = { TextButton(onClick = { showCaConfirmation = false }) { Text("取消") } },
            )
        }
        Scaffold(
            topBar = {
                if (mode != MainMode.BROWSER) CenterAlignedTopAppBar(
                    title = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Meerkat", fontWeight = FontWeight.SemiBold)
                            Text(
                                when {
                                    externalCaptureActive -> "正在全域嗅探"
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
                        selected = mode == MainMode.EXTERNAL,
                        onClick = { mode = MainMode.EXTERNAL },
                        icon = { Icon(Icons.Default.Public, null) },
                        label = { Text("App") },
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
                    NavigationBarItem(
                        selected = mode == MainMode.SETTINGS,
                        onClick = { mode = MainMode.SETTINGS },
                        icon = { Icon(Icons.Default.Settings, null) },
                        label = { Text("設定") },
                    )
                }
            },
        ) { padding ->
            Box(
                Modifier
                    .padding(padding)
                    .padding(horizontal = if (mode == MainMode.BROWSER) 0.dp else 14.dp, vertical = if (mode == MainMode.BROWSER) 0.dp else 8.dp)
                    .fillMaxSize(),
            ) {
                // Keep the browser in composition: tab changes must not recreate its
                // WebView, history, scroll position, or JavaScript capture hooks.
                BrowserPane(
                    visible = mode == MainMode.BROWSER,
                    incomingUrl = incomingUrl,
                    address = address,
                    onAddressChange = { address = it },
                    onWebViewReady = { webView = it },
                    viewModel = viewModel,
                    resources = resources,
                    currentSessionId = browserSession?.id,
                    onOpenResources = { mode = MainMode.RESOURCES },
                    onClear = viewModel::clear,
                )
                when (mode) {
                    MainMode.BROWSER -> Unit
                    MainMode.EXTERNAL -> ExternalAppPane(
                        captureActive = externalCaptureActive,
                        captureStatus = captureStatus,
                        resourceCount = resources.count { it.sessionId == externalSession?.id },
                        blockQuic = blockQuic,
                        inspectHttps = inspectHttps,
                        onInspectHttpsChange = { inspectHttps = it },
                        onBlockQuicChange = {
                            blockQuic = it
                            if (captureStatus.running) viewModel.updateBlockQuic(it)
                        },
                        overlayRunning = overlayRunning,
                        onOverlayChange = setOverlay,
                        caManuallyConfirmed = manualCaConfirmed,
                        onConfirmCa = { showCaConfirmation = true },
                        onStart = {
                            if (Build.VERSION.SDK_INT >= 33) {
                                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            val prepare = VpnService.prepare(context)
                            if (prepare != null) {
                                vpnLauncher.launch(prepare)
                            } else {
                                beginGlobalCapture()
                            }
                        },
                        onStop = {
                            viewModel.stopExternalCapture()
                        },
                        caInstalled = mitmCaInstalled,
                        caFingerprint = viewModel.mitmCaFingerprint(),
                        onInstallCa = {
                            runCatching {
                                viewModel.exportMitmCaCertificate()
                            }.onSuccess {
                                Toast.makeText(
                                    context,
                                    "已匯出到 Downloads/Meerkat/Meerkat-Local-CA.crt",
                                    Toast.LENGTH_LONG,
                                ).show()
                                caSettingsLauncher.launch(viewModel.caSettingsIntent())
                            }.onFailure { error ->
                                Toast.makeText(
                                    context,
                                    "CA 匯出失敗：${error.message ?: "未知錯誤"}",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        },
                    )
                    MainMode.RESOURCES -> ResourcePane(
                        resources = resources,
                        currentSessionId = currentSession?.id,
                        onClear = viewModel::clear,
                    )
                    MainMode.DOWNLOADS -> DownloadsPane(downloads)
                    MainMode.SETTINGS -> SettingsPane()
                }
            }
        }
    }
}

private class BrowserCaptureBridge(
    private val context: Context,
    private val viewModel: MainViewModel,
) {
    private data class BlobState(
        val file: File,
        val output: FileOutputStream,
        val sourceUrl: String,
        val mimeType: String?,
        val expectedSize: Long,
        val referer: String?,
        var fileName: String?,
        var received: Long = 0L,
    )

    private val blobs = ConcurrentHashMap<String, BlobState>()
    private val blobNames = ConcurrentHashMap<String, String>()
    private val blobDir = File(context.filesDir, "captured-blobs").apply { mkdirs() }

    @JavascriptInterface
    fun resource(url: String?, mimeType: String?, referer: String?) {
        val target = url?.trim().orEmpty()
        if (!target.startsWith("http://", true) && !target.startsWith("https://", true)) return
        viewModel.recordWebResource(
            url = target,
            mimeType = mimeType?.takeIf { it.isNotBlank() && it != "null" },
            requestHeaders = mapOf(
                "Referer" to referer.orEmpty(),
                "User-Agent" to browserUserAgent(context, referer.orEmpty()),
                "Cookie" to (CookieManager.getInstance().getCookie(target) ?: ""),
            ),
        )
    }

    @JavascriptInterface
    @Synchronized
    fun blobBegin(
        id: String?,
        blobUrl: String?,
        mimeType: String?,
        size: Long,
        referer: String?,
        fileName: String?,
    ) {
        val safeId = id?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,80}")) } ?: return
        val source = blobUrl?.takeIf { it.startsWith("blob:", true) } ?: return
        if (size <= 0L || size > MAX_BLOB_BYTES || blobs.size >= MAX_ACTIVE_BLOBS) return
        blobs.remove(safeId)?.let { old ->
            runCatching { old.output.close() }
            old.file.delete()
        }
        val file = File(blobDir, safeId + ".blob")
        val output = runCatching { FileOutputStream(file, false) }.getOrNull() ?: return
        blobs[safeId] = BlobState(
            file = file,
            output = output,
            sourceUrl = source,
            mimeType = mimeType?.takeIf { it.isNotBlank() },
            expectedSize = size,
            referer = referer,
            fileName = sanitizeName(fileName) ?: blobNames[source],
        )
    }

    @JavascriptInterface
    @Synchronized
    fun blobName(blobUrl: String?, fileName: String?) {
        val source = blobUrl?.takeIf { it.startsWith("blob:", true) } ?: return
        val name = sanitizeName(fileName) ?: return
        blobNames[source] = name
        blobs.values.filter { it.sourceUrl == source }.forEach { it.fileName = name }
    }

    @JavascriptInterface
    @Synchronized
    fun blobChunk(id: String?, base64: String?) {
        val state = blobs[id] ?: return
        val encoded = base64 ?: return
        val bytes = runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull() ?: run {
            abortBlob(id)
            return
        }
        if (bytes.isEmpty() || state.received + bytes.size > state.expectedSize || state.received + bytes.size > MAX_BLOB_BYTES) {
            abortBlob(id)
            return
        }
        runCatching {
            state.output.write(bytes)
            state.received += bytes.size
        }.onFailure { abortBlob(id) }
    }

    @JavascriptInterface
    @Synchronized
    fun blobEnd(id: String?) {
        val key = id ?: return
        val state = blobs.remove(key) ?: return
        runCatching {
            state.output.flush()
            state.output.fd.sync()
            state.output.close()
            require(state.received == state.expectedSize) { "Blob size mismatch" }
            viewModel.recordLocalResource(
                sourceUrl = state.sourceUrl,
                localCachePath = state.file.absolutePath,
                mimeType = state.mimeType,
                contentLength = state.received,
                fileName = state.fileName ?: defaultBlobName(state.mimeType),
                referer = state.referer,
            )
        }.onFailure {
            runCatching { state.output.close() }
            state.file.delete()
        }
    }

    @JavascriptInterface
    @Synchronized
    fun blobAbort(id: String?) { abortBlob(id) }

    private fun abortBlob(id: String?) {
        val state = blobs.remove(id) ?: return
        runCatching { state.output.close() }
        state.file.delete()
    }

    private fun sanitizeName(value: String?): String? {
        val clean = value
            ?.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_")
            ?.trim()
            ?.trim('.')
            ?.take(180)
            .orEmpty()
        return clean.takeIf { it.isNotBlank() }
    }

    private fun defaultBlobName(mime: String?): String {
        val extension = when (mime?.substringBefore(';')?.lowercase()) {
            "application/pdf" -> "pdf"
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
            "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> "pptx"
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx"
            "image/png" -> "png"
            "image/jpeg" -> "jpg"
            "image/webp" -> "webp"
            "video/mp4" -> "mp4"
            "audio/mpeg" -> "mp3"
            else -> "bin"
        }
        return "Meerkat-blob-" + System.currentTimeMillis() + "." + extension
    }

    companion object {
        private const val MAX_ACTIVE_BLOBS = 4
        private const val MAX_BLOB_BYTES = 512L * 1024 * 1024
    }
}
private fun browserCaptureScript(): String = """
        (function() {
          if (window.__meerkatCaptureInstalled) return;
          window.__meerkatCaptureInstalled = true;

          const MAX_TEXT = 1024 * 1024;
          // Kotlin raw strings preserve backslashes: these are JavaScript escapes.
          const RESOURCE_RE = /https?:\/\/[^\s"'<>\\]+|(?:\/{1,2}|\.\.?\/)?[^\s"'<>\\{}]+?\.(?:m3u8|mpd|mp4|m4v|webm|mkv|mov|avi|m4a|mp3|aac|flac|ogg|opus|wav|jpg|jpeg|png|webp|gif|avif|bmp|svg|heic|heif|pdf|epub|doc|docx|docm|dot|dotx|xls|xlsx|xlsm|xlsb|ppt|pptx|pptm|pps|ppsx|odt|ods|odp|pages|numbers|key|txt|csv|tsv|rtf|md|zip|rar|7z|tar|gz)(?:\?[^\s"'<>\\]*)?/ig;
          const reported = new Set();

          function absolute(value) {
            if (!value) return null;
            try { return new URL(String(value), document.baseURI).href; } catch (_) { return null; }
          }

          function report(value, mime) {
            const url = absolute(value);
            if (!url || (!url.startsWith("http://") && !url.startsWith("https://"))) return;
            const key = url + "|" + (mime || "");
            if (reported.has(key)) return;
            // Bound memory while allowing response MIME to enrich a URL report.
            if (reported.size >= 4096) reported.clear();
            try {
              MeerkatCapture.resource(url, mime || "", location.href);
              reported.add(key);
            } catch (_) {}
          }

          function scanText(value) {
            if (typeof value !== "string" || !value) return;
            let text = value.length > MAX_TEXT ? value.slice(0, MAX_TEXT) : value;
            text = text.replace(/\\\//g, "/").replace(/\\u002[fF]/g, "/").replace(/&amp;/g, "&");
            RESOURCE_RE.lastIndex = 0;
            let match, count = 0;
            while ((match = RESOURCE_RE.exec(text)) && count < 256) {
              const raw = match[0].replace(/^['"]|['",;)}\]]+$/g, "");
              report(raw, "");
              count++;
            }
          }

          // Player configuration often contains extensionless signed media URLs.
          function scanJson(value) {
            let remaining = 1024;
            const seen = new WeakSet();
            function visit(item, depth) {
              if (--remaining < 0 || depth > 12) return;
              if (typeof item === "string") {
                scanText(item);
              } else if (item && typeof item === "object" && !seen.has(item)) {
                seen.add(item);
                Object.keys(item).slice(0, 256).forEach(function(key) {
                  visit(item[key], depth + 1);
                });
              }
            }
            visit(value, 0);
          }
          const originalJsonParse = JSON.parse;
          JSON.parse = function() {
            const result = originalJsonParse.apply(this, arguments);
            try { scanJson(result); } catch (_) {}
            return result;
          };
          try {
            const originalResponseJson = Response.prototype.json;
            Response.prototype.json = function() {
              return originalResponseJson.apply(this, arguments).then(function(value) {
                try { scanJson(value); } catch (_) {}
                return value;
              });
            };
          } catch (_) {}

          const MAX_BLOB_CAPTURE = 512 * 1024 * 1024;
          const BLOB_CHUNK = 192 * 1024;
          const bufferOrigins = new WeakMap();

          function base64Bytes(bytes) {
            let binary = "";
            const step = 0x8000;
            for (let i = 0; i < bytes.length; i += step) {
              binary += String.fromCharCode.apply(null, bytes.subarray(i, Math.min(i + step, bytes.length)));
            }
            return btoa(binary);
          }

          async function captureBlob(blob, blobUrl) {
            if (!blob || !blobUrl || !blob.size || blob.size > MAX_BLOB_CAPTURE) return;
            const id = "b" + Date.now().toString(36) + Math.random().toString(36).slice(2);
            try {
              MeerkatCapture.blobBegin(id, blobUrl, blob.type || "", Number(blob.size), location.href, blob.name || "");
              for (let offset = 0; offset < blob.size; offset += BLOB_CHUNK) {
                const part = blob.slice(offset, Math.min(blob.size, offset + BLOB_CHUNK));
                const buffer = await part.arrayBuffer();
                MeerkatCapture.blobChunk(id, base64Bytes(new Uint8Array(buffer)));
              }
              MeerkatCapture.blobEnd(id);
            } catch (_) {
              try { MeerkatCapture.blobAbort(id); } catch (_) {}
            }
          }

          try {
            const originalCreateObjectURL = URL.createObjectURL.bind(URL);
            URL.createObjectURL = function(object) {
              const result = originalCreateObjectURL(object);
              try {
                if (object instanceof Blob) captureBlob(object, result);
              } catch (_) {}
              return result;
            };
            document.addEventListener("click", function(event) {
              try {
                const anchor = event.target && event.target.closest ? event.target.closest("a") : null;
                if (anchor && anchor.href && anchor.href.startsWith("blob:") && anchor.download) {
                  MeerkatCapture.blobName(anchor.href, anchor.download);
                }
              } catch (_) {}
            }, true);
          } catch (_) {}

          try {
            const originalResponseArrayBuffer = Response.prototype.arrayBuffer;
            Response.prototype.arrayBuffer = function() {
              const response = this;
              return originalResponseArrayBuffer.apply(this, arguments).then(function(buffer) {
                try {
                  bufferOrigins.set(buffer, {
                    url: response.url || "",
                    mime: response.headers && response.headers.get("content-type") || ""
                  });
                } catch (_) {}
                return buffer;
              });
            };
          } catch (_) {}

          try {
            if (window.MediaSource && MediaSource.prototype.addSourceBuffer && window.SourceBuffer) {
              const originalAddSourceBuffer = MediaSource.prototype.addSourceBuffer;
              MediaSource.prototype.addSourceBuffer = function(type) {
                const sourceBuffer = originalAddSourceBuffer.apply(this, arguments);
                try { sourceBuffer.__meerkatMime = type || ""; } catch (_) {}
                return sourceBuffer;
              };
              const originalAppendBuffer = SourceBuffer.prototype.appendBuffer;
              SourceBuffer.prototype.appendBuffer = function(data) {
                try {
                  const origin = bufferOrigins.get(data);
                  if (origin && origin.url) report(origin.url, this.__meerkatMime || origin.mime || "");
                } catch (_) {}
                return originalAppendBuffer.apply(this, arguments);
              };
            }
          } catch (_) {}

          try {
            const NativeWorker = window.Worker;
            if (NativeWorker) {
              window.Worker = function(url, options) {
                report(url, "application/javascript");
                return new NativeWorker(url, options);
              };
              window.Worker.prototype = NativeWorker.prototype;
            }
            const NativeSharedWorker = window.SharedWorker;
            if (NativeSharedWorker) {
              window.SharedWorker = function(url, options) {
                report(url, "application/javascript");
                return new NativeSharedWorker(url, options);
              };
              window.SharedWorker.prototype = NativeSharedWorker.prototype;
            }
            if (navigator.serviceWorker && navigator.serviceWorker.register) {
              const originalRegister = navigator.serviceWorker.register.bind(navigator.serviceWorker);
              navigator.serviceWorker.register = function(url, options) {
                report(url, "application/javascript");
                return originalRegister(url, options);
              };
            }
          } catch (_) {}
          const originalFetch = window.fetch;
          if (originalFetch) {
            window.fetch = function(input, init) {
              try { report(input instanceof URL ? input.href : typeof input === "string" ? input : input && input.url, ""); } catch (_) {}
              return originalFetch.apply(this, arguments).then(function(response) {
                try {
                  report(response.url, response.headers && response.headers.get("content-type"));
                  const ct = (response.headers && response.headers.get("content-type") || "").toLowerCase();
                  if (ct.includes("json") || ct.startsWith("text/") || ct.includes("javascript") || ct.includes("xml")) {
                    response.clone().text().then(scanText).catch(function(){});
                  }
                } catch (_) {}
                return response;
              });
            };
          }

          const originalOpen = XMLHttpRequest.prototype.open;
          const originalSend = XMLHttpRequest.prototype.send;
          XMLHttpRequest.prototype.open = function(method, url) {
            this.__meerkatUrl = absolute(url);
            if (this.__meerkatUrl) report(this.__meerkatUrl, "");
            return originalOpen.apply(this, arguments);
          };
          XMLHttpRequest.prototype.send = function() {
            this.addEventListener("load", function() {
              try {
                const ct = this.getResponseHeader("content-type") || "";
                report(this.responseURL || this.__meerkatUrl, ct);
                if (this.responseType === "arraybuffer" && this.response) {
                  try { bufferOrigins.set(this.response, {url:this.responseURL || this.__meerkatUrl || "", mime:ct}); } catch (_) {}
                }
                if (this.responseType === "json") scanJson(this.response);
                if ((ct.includes("json") || ct.startsWith("text/") || ct.includes("javascript") || ct.includes("xml")) && typeof this.responseText === "string") {
                  scanText(this.responseText);
                }
              } catch (_) {}
            });
            return originalSend.apply(this, arguments);
          };

          const NativeWebSocket = window.WebSocket;
          if (NativeWebSocket) {
            window.WebSocket = function(url, protocols) {
              const ws = protocols === undefined ? new NativeWebSocket(url) : new NativeWebSocket(url, protocols);
              ws.addEventListener("message", function(event) {
                if (typeof event.data === "string") scanText(event.data);
              });
              return ws;
            };
            window.WebSocket.prototype = NativeWebSocket.prototype;
            Object.defineProperties(window.WebSocket, { CONNECTING:{value:0}, OPEN:{value:1}, CLOSING:{value:2}, CLOSED:{value:3} });
          }

          if (window.PerformanceObserver) {
            try {
              const observer = new PerformanceObserver(function(list) {
                list.getEntries().forEach(function(entry) { report(entry.name, ""); });
              });
              observer.observe({type:"resource", buffered:true});
            } catch (_) {}
          }

          function scanNode(node) {
            if (!node || node.nodeType !== 1) return;
            ["src","href","poster","data-src","data-url"].forEach(function(attr) {
              try { if (node.hasAttribute && node.hasAttribute(attr)) report(node.getAttribute(attr), ""); } catch (_) {}
            });
            try {
              if (node.currentSrc) report(node.currentSrc, node.tagName === "IMG" ? "image/*" : node.tagName === "AUDIO" ? "audio/*" : node.tagName === "VIDEO" ? "video/*" : "");
              if (node.srcset) String(node.srcset).split(",").forEach(function(part) { report(part.trim().split(/\s+/)[0], "image/*"); });
            } catch (_) {}
          }

          try {
            new MutationObserver(function(records) {
              records.forEach(function(record) {
                scanNode(record.target);
                record.addedNodes && record.addedNodes.forEach(function(node) {
                  scanNode(node);
                  if (node.querySelectorAll) node.querySelectorAll("[src],[href],[srcset],[poster],[data-src],[data-url]").forEach(scanNode);
                });
              });
            }).observe(document.documentElement || document, {subtree:true, childList:true, attributes:true, attributeFilter:["src","href","poster","srcset","data-src","data-url"]});
          } catch (_) {}
          function scanDocument() {
            document.querySelectorAll("[src],[href],[srcset],[poster],[data-src],[data-url]").forEach(scanNode);
            document.querySelectorAll("script:not([src])").forEach(function(node) { scanText(node.textContent); });
            try { performance.getEntriesByType("resource").forEach(function(entry) { report(entry.name, ""); }); } catch (_) {}
          }
          if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", scanDocument, {once:true});
          else scanDocument();
          document.addEventListener("loadedmetadata", function(event) { scanNode(event.target); }, true);
        })();
    """.trimIndent()

private fun installBrowserCapture(webView: WebView) {
    webView.evaluateJavascript(browserCaptureScript(), null)
}
@SuppressLint("SetJavaScriptEnabled")
@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun BrowserPane(
    visible: Boolean,
    incomingUrl: String?,
    address: String,
    onAddressChange: (String) -> Unit,
    onWebViewReady: (WebView?) -> Unit,
    viewModel: MainViewModel,
    resources: List<Resource>,
    currentSessionId: Long?,
    onOpenResources: () -> Unit,
    onClear: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var showResources by remember { mutableStateOf(false) }
    var resourceCategory by remember { mutableStateOf<UiResourceCategory?>(null) }
    var localAddress by remember { mutableStateOf(address) }
    var activeUrl by remember { mutableStateOf(address.takeIf { it.isNotBlank() }) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var loading by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var pageError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(address) {
        if (address != localAddress && address.isNotBlank()) {
            localAddress = address
        }
    }

    LaunchedEffect(incomingUrl) {
        if (!incomingUrl.isNullOrBlank()) {
            val url = normalizeUrl(incomingUrl)
            localAddress = url
            activeUrl = url
            pageError = null
            // A first URL creates the WebView below; subsequent shares navigate it.
            if (webView?.url != url) webView?.loadBrowserUrl(url)
        }
    }

    val liveResources = remember(resources, currentSessionId) {
        if (currentSessionId == null) emptyList()
        else resources.filter { it.sessionId == currentSessionId }
    }
    val imageCount = liveResources.count { it.uiCategory() == UiResourceCategory.IMAGE }
    val videoCount = liveResources.count { it.uiCategory() == UiResourceCategory.VIDEO }
    val documentCount = liveResources.count { it.uiCategory() == UiResourceCategory.DOCUMENT }
    val otherCount = liveResources.count { it.uiCategory() == UiResourceCategory.OTHER }

    val navigate = {
        if (localAddress.isNotBlank()) {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
            webView?.clearFocus()
            val url = normalizeUrl(localAddress)
            localAddress = url
            activeUrl = url
            onAddressChange(url)
            pageError = null
            webView?.loadBrowserUrl(url)
            webView?.let { view ->
                (view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .hideSoftInputFromWindow(view.windowToken, 0)
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            layout(placeable.width, placeable.height) {
                if (visible) placeable.placeRelative(0, 0)
            }
        },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = localAddress,
                onValueChange = { localAddress = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("搜尋或輸入網址") },
                shape = MaterialTheme.shapes.extraLarge,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { navigate() }),
            )
            IconButton(onClick = { navigate() }, enabled = localAddress.isNotBlank()) {
                Icon(Icons.Default.ArrowForward, "前往")
            }
        }

        if (loading) {
            LinearProgressIndicator(
                progress = { progress.coerceIn(0, 100) / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        pageError?.let { error ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    text = error,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        if (activeUrl.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "輸入網址開始瀏覽與嗅探",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            AndroidView(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        webView = this
                        onWebViewReady(this)
                        setBackgroundColor(android.graphics.Color.WHITE)
                        isFocusable = true
                        isFocusableInTouchMode = true

                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.loadsImagesAutomatically = true
                        settings.blockNetworkImage = false
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = false
                        settings.setSupportZoom(true)
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        settings.javaScriptCanOpenWindowsAutomatically = true
                        settings.setSupportMultipleWindows(true)
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

                        addJavascriptInterface(
                            BrowserCaptureBridge(ctx, viewModel),
                            "MeerkatCapture",
                        )
                        val hasDocumentStart =
                            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
                        if (hasDocumentStart) {
                            WebViewCompat.addDocumentStartJavaScript(
                                this,
                                browserCaptureScript(),
                                setOf("*"),
                            )
                        }

                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                        webChromeClient = object : WebChromeClient() {
                            override fun onCreateWindow(
                                view: WebView?,
                                isDialog: Boolean,
                                isUserGesture: Boolean,
                                resultMsg: Message?,
                            ): Boolean {
                                // target="_blank" and window.open must use the same captured
                                // browser, rather than an external app or an uncaptured window.
                                val message = resultMsg ?: return false
                                val transport = message.obj as? WebView.WebViewTransport ?: return false
                                val popup = WebView(ctx)
                                popup.webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                    ): Boolean {
                                        val target = request?.url?.toString() ?: return true
                                        if (target == "about:blank") return false
                                        resolveBrowsableUrl(target)?.let { loadBrowserUrl(it) }
                                        popup.post { popup.destroy() }
                                        return true
                                    }
                                }
                                transport.webView = popup
                                message.sendToTarget()
                                return true
                            }

                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                progress = newProgress
                                loading = newProgress in 0..99
                                super.onProgressChanged(view, newProgress)
                            }
                        }

                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): Boolean {
                                val req = request ?: return false
                                val raw = req.url.toString()
                                val scheme = req.url.scheme?.lowercase()
                                val currentUrl = view?.url

                                if (scheme == "http" || scheme == "https") {
                                    if (isInstagramHost(currentUrl) && isInstagramStoreUrl(raw)) return true
                                    return false
                                }

                                // Convert main-frame app links automatically. Never launch
                                // another app; subframes must not replace the current page.
                                if (req.isForMainFrame) {
                                    val fallback = resolveBrowsableUrl(raw)
                                    if (
                                        !fallback.isNullOrBlank() &&
                                        !isInstagramStoreUrl(fallback) &&
                                        !sameBrowserTarget(currentUrl, fallback)
                                    ) {
                                        localAddress = fallback
                                        activeUrl = fallback
                                        onAddressChange(fallback)
                                        view?.loadBrowserUrl(fallback)
                                    }
                                }
                                return true
                            }

                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): android.webkit.WebResourceResponse? {
                                val req = request ?: return null
                                val url = req.url.toString()
                                if (url.startsWith("http://", true) || url.startsWith("https://", true)) {
                                    viewModel.recordWebResource(
                                        url = url,
                                        mimeType = null,
                                        requestHeaders = req.requestHeaders.orEmpty(),
                                    )
                                }
                                return null
                            }

                            override fun onPageStarted(
                                view: WebView?,
                                url: String?,
                                favicon: android.graphics.Bitmap?,
                            ) {
                                loading = true
                                pageError = null
                                focusManager.clearFocus(force = true)
                                keyboard?.hide()
                                view?.let {
                                    (it.context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                                        .hideSoftInputFromWindow(it.windowToken, 0)
                                }
                                super.onPageStarted(view, url, favicon)
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?,
                            ) {
                                val scheme = request?.url?.scheme?.lowercase()
                                if (
                                    request?.isForMainFrame == true &&
                                    (scheme == "http" || scheme == "https")
                                ) {
                                    pageError = "網頁載入失敗：${error?.description ?: "未知錯誤"}"
                                    loading = false
                                }
                                super.onReceivedError(view, request, error)
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                if (view !== webView) return
                                loading = false
                                url?.let {
                                    localAddress = it
                                    activeUrl = it
                                    onAddressChange(it)
                                }
                                // Document-start injection already covers supported WebView
                                // versions. Avoid reinjecting after every media/page lifecycle.
                                if (!hasDocumentStart) {
                                    view?.post {
                                        if (view.isAttachedToWindow) installBrowserCapture(view)
                                    }
                                }
                                view?.postDelayed({
                                    if (view.isAttachedToWindow) scanDomResources(view, viewModel)
                                }, 700)
                                super.onPageFinished(view, url)
                            }

                            override fun onRenderProcessGone(
                                view: WebView?,
                                detail: RenderProcessGoneDetail?,
                            ): Boolean {
                                pageError = if (detail?.didCrash() == true) {
                                    "網頁渲染程序崩潰，請重新開啟頁面。"
                                } else {
                                    "網頁渲染程序被系統終止，請重新開啟頁面。"
                                }
                                loading = false
                                webView = null
                                return true
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

                        loadBrowserUrl(activeUrl!!)
                    }
                },
                update = { view ->
                    webView = view
                    onWebViewReady(view)
                    view.visibility = if (visible) View.VISIBLE else View.INVISIBLE
                    if (!visible) view.clearFocus()
                },
                onRelease = { view ->
                    view.stopLoading()
                    view.removeJavascriptInterface("MeerkatCapture")
                    view.destroy()
                },
            )
        }
        Surface(tonalElevation = 2.dp) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { webView?.goBack() }, enabled = webView?.canGoBack() == true) {
                    Icon(Icons.Default.ArrowBack, "上一頁")
                }
                IconButton(onClick = { webView?.goForward() }, enabled = webView?.canGoForward() == true) {
                    Icon(Icons.Default.ArrowForward, "下一頁")
                }
                IconButton(onClick = { if (loading) webView?.stopLoading() else webView?.reload() },
                    enabled = webView?.url != null) {
                    Icon(Icons.Default.Refresh, if (loading) "停止載入" else "重新整理")
                }
                IconButton(onClick = {
                    val previousView = webView
                    webView = null
                    onWebViewReady(null)
                    previousView?.stopLoading()
                    activeUrl = null
                    localAddress = ""
                    onAddressChange("")
                    pageError = null
                    loading = false
                    progress = 0
                    focusManager.clearFocus()
                    keyboard?.hide()
                    showResources = false
                }) { Icon(Icons.Default.Home, "首頁") }
                TextButton(onClick = { showResources = true }) { Text("資源 ${liveResources.size}") }
            }
        }
    }
    if (visible && showResources) {
        ModalBottomSheet(onDismissRequest = { showResources = false }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("瀏覽器資源 ${liveResources.size}", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = onClear, enabled = liveResources.isNotEmpty()) {
                        Icon(Icons.Default.Delete, "清除資源")
                    }
                    TextButton(onClick = { showResources = false; onOpenResources() }) { Text("全部") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(UiResourceCategory.IMAGE to imageCount, UiResourceCategory.VIDEO to videoCount,
                        UiResourceCategory.DOCUMENT to documentCount, UiResourceCategory.OTHER to otherCount)
                        .forEach { (category, count) ->
                            Surface(
                                onClick = { resourceCategory = if (resourceCategory == category) null else category },
                                modifier = Modifier.weight(1f),
                                shape = MaterialTheme.shapes.medium,
                                color = if (resourceCategory == category) MaterialTheme.colorScheme.secondaryContainer
                                    else MaterialTheme.colorScheme.surface,
                                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            ) {
                                Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 10.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(category.label, style = MaterialTheme.typography.labelLarge,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                    Text(count.toString(), style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                }
                            }
                        }
                }
                val filtered = liveResources.filter { resourceCategory == null || it.uiCategory() == resourceCategory }
                if (filtered.isEmpty()) {
                    Text("尚未找到資源，瀏覽網頁或播放影片後會自動加入。", Modifier.padding(vertical = 24.dp))
                } else {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(filtered, key = { it.id }) { ResourceRow(it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExternalAppPane(
    captureActive: Boolean,
    captureStatus: CaptureSnapshot,
    resourceCount: Int,
    blockQuic: Boolean,
    inspectHttps: Boolean,
    onInspectHttpsChange: (Boolean) -> Unit,
    onBlockQuicChange: (Boolean) -> Unit,
    overlayRunning: Boolean,
    onOverlayChange: (Boolean) -> Unit,
    caManuallyConfirmed: Boolean,
    onConfirmCa: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    caInstalled: Boolean,
    caFingerprint: String,
    onInstallCa: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(captureStatus.summary(), style = MaterialTheme.typography.bodyMedium)
        Text("本次找到 $resourceCount 項資源", style = MaterialTheme.typography.bodyMedium)
        if (captureStatus.running && captureStatus.connections > 0 && resourceCount == 0) {
            val hint = when {
                !captureStatus.httpsEnabled ->
                    "目前只轉送 HTTPS 加密流量，無法從中讀取媒體網址。請安裝目前的 CA、啟用「嘗試 HTTPS 解密」，再重新開始嗅探。"
                captureStatus.decryptedConnections == 0L && captureStatus.opaqueTlsConnections > 0L ->
                    "已有加密連線無法解密。目標 App 可能不信任使用者 CA 或使用憑證釘選；停用 HTTP/3 也無法解除此限制。"
                captureStatus.decryptedConnections == 0L && captureStatus.failures > 0L ->
                    "尚無 HTTPS 連線成功解析，且已有 ${captureStatus.failures} 次轉送失敗。請查看上方的最近失敗類型；這不一定是憑證問題。"
                captureStatus.decryptedConnections == 0L && captureStatus.tcp443Connections == 0L && captureStatus.quicConnections > 0L ->
                    "本次未收到 TCP/443 連線；UDP/443 可能是 HTTP/3，目前只能轉送。若停用後頁面無法載入，請在 Meerkat 內建瀏覽器開啟同一個貼文網址。"
                captureStatus.decryptedConnections == 0L && captureStatus.quicConnections > 0L ->
                    "已收到 UDP/443，但尚未解析出 HTTPS。若停用 UDP/443 後頁面無法載入，請在 Meerkat 內建瀏覽器開啟同一個貼文網址。"
                captureStatus.decryptedConnections > 0L ->
                    "已解析 HTTPS，但目前沒有辨識到可下載的媒體網址。請在目標 App 播放影片或載入圖片後再查看。"
                else -> "已收到連線，但尚未收到可解析的 HTTP 資源。請讓目標 App 重新載入內容。"
            }
            Text(hint, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Column(
                Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("全域 App 嗅探", fontWeight = FontWeight.SemiBold)
                Text(
                    "按下開始後，Meerkat 會在背景持續嗅探。你可以直接離開 Meerkat，自行開啟 Instagram、Threads、瀏覽器或其他 App，不需要事先選擇目標 App。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    if (caInstalled) {
                        "已核對目前的 Meerkat Local CA，可嘗試 HTTPS 嗅探。"
                    } else if (caManuallyConfirmed) {
                        "你已手動確認安裝 CA；系統尚未能自動核對，將嘗試 HTTPS 嗅探。"
                    } else {
                        "尚未偵測到目前的 Meerkat Local CA。如果你已安裝，可以手動確認；若曾移除並重裝 App，請重新匯出及安裝目前的憑證。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                if (!caInstalled) {
                    OutlinedButton(onClick = onInstallCa) {
                        Text("匯出 CA 並開啟設定")
                    }
                    TextButton(onClick = onConfirmCa) { Text("我已安裝目前的 CA") }
                }
                Text(
                    "CA SHA-256：" + caFingerprint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "使用 certificate pinning 或拒絕使用者 CA 的 App 仍可能無法解密；Meerkat 會盡量保持其網路連線正常。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("嘗試 HTTPS 解密")
                Text("預設只轉送加密流量。安裝 CA 後可啟用；若其他 App 功能異常，停止嗅探並關閉此選項後重啟。",
                    style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = inspectHttps, onCheckedChange = onInspectHttpsChange,
                enabled = !captureActive && !captureStatus.starting && (caInstalled || caManuallyConfirmed))
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("嘗試 TCP 嗅探（停用 HTTP/3）")
                Text("可以在嗅探中切換，部分 App 會改用 HTTPS；若無法載入請關閉。仍無法解密憑證釘選。",
                    style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = blockQuic, onCheckedChange = onBlockQuicChange,
                enabled = !captureStatus.starting)
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onStart,
                enabled = !captureActive && !captureStatus.starting,
            ) {
                Text(if (captureStatus.starting) "啟動中…" else if (inspectHttps) "開始全域 HTTPS 嗅探" else "開始全域嗅探")
            }
            OutlinedButton(
                onClick = onStop,
                enabled = captureActive || captureStatus.starting,
            ) {
                Text("停止嗅探")
            }
            if (captureActive) {
                AssistChip(
                    onClick = {},
                    label = { Text("背景嗅探中") },
                )
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("懸浮球")
                Text("在其他 App 上顯示嗅探狀態與資源數量。關閉懸浮球不會停止嗅探。",
                    style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = overlayRunning, onCheckedChange = onOverlayChange)
        }

        if (captureActive) {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("現在可以直接離開 Meerkat", fontWeight = FontWeight.SemiBold)
                    Text(
                        "自行開啟你要使用的 App。嗅探到的資源會加入目前工作階段，懸浮按鈕會顯示目前捕獲數量。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
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
) {
    val context = LocalContext.current
    var selectedCategory by remember { mutableStateOf<UiResourceCategory?>(null) }
    var query by remember { mutableStateOf("") }
    var currentOnly by remember { mutableStateOf(true) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }

    val visible = remember(resources, selectedCategory, query, currentOnly, currentSessionId) {
        resources.filter { resource ->
            (!currentOnly || (currentSessionId != null && resource.sessionId == currentSessionId)) &&
                (selectedCategory == null || resource.uiCategory() == selectedCategory) &&
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

    LaunchedEffect(visible.map { it.id }) {
        selectedIds = selectedIds.intersect(visible.mapTo(linkedSetOf()) { it.id })
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
            OutlinedButton(onClick = {
                selectionMode = !selectionMode
                if (!selectionMode) selectedIds = emptySet()
            }) {
                Text(if (selectionMode) "取消選取" else "批次選取")
            }
            if (selectionMode) {
                OutlinedButton(onClick = {
                    selectedIds = if (selectedIds.size == visible.size) emptySet()
                    else visible.mapTo(linkedSetOf()) { it.id }
                }) {
                    Text(if (selectedIds.size == visible.size && visible.isNotEmpty()) "取消全選" else "全選目前")
                }
            }
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
                UiResourceCategory.IMAGE to "圖片",
                UiResourceCategory.VIDEO to "影片",
                UiResourceCategory.DOCUMENT to "文件",
                UiResourceCategory.OTHER to "其他",
            ).forEach { (category, label) ->
                FilterChip(
                    selected = selectedCategory == category,
                    onClick = { selectedCategory = category },
                    label = { Text(label) },
                )
            }
        }

        Text("已保存 ${visible.size} 項可下載資源", fontWeight = FontWeight.SemiBold)

        if (selectionMode) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "已選 ${selectedIds.size} 項",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    enabled = selectedIds.isNotEmpty(),
                    onClick = {
                        val targets = visible.filter { it.id in selectedIds }
                        var queued = 0
                        var failed = 0
                        targets.forEach { resource ->
                            runCatching {
                                DownloadHelper.enqueue(
                                    context,
                                    resource,
                                    if (resource.type == ResourceType.STREAM) DownloadQuality.HIGH
                                    else DownloadQuality.HIGH,
                                )
                            }.onSuccess { queued++ }
                                .onFailure { failed++ }
                        }
                        Toast.makeText(
                            context,
                            if (failed == 0) "已加入 $queued 項下載"
                            else "已加入 $queued 項，$failed 項失敗",
                            Toast.LENGTH_SHORT,
                        ).show()
                        selectedIds = emptySet()
                        selectionMode = false
                    },
                ) {
                    Icon(Icons.Default.Download, null)
                    Spacer(Modifier.width(4.dp))
                    Text("下載選取")
                }
            }
        }

        if (visible.isEmpty()) {
            Text("尚未偵測到符合條件的資源。", style = MaterialTheme.typography.bodySmall)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(visible, key = { it.id }) { resource ->
                    ResourceRow(
                        resource = resource,
                        selectionMode = selectionMode,
                        selected = resource.id in selectedIds,
                        onToggleSelected = {
                            selectedIds = if (resource.id in selectedIds) {
                                selectedIds - resource.id
                            } else {
                                selectedIds + resource.id
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ResourceRow(
    resource: Resource,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onToggleSelected: () -> Unit = {},
) {
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
                if (selectionMode) {
                    Checkbox(
                        checked = selected,
                        onCheckedChange = { onToggleSelected() },
                    )
                }
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
                    label = { Text(resource.uiCategory().label) },
                )
            }

            Text(
                resourceSummaryLine(resource),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (resource.validationState == ValidationState.FAILED) {
                Text(
                    "連結檢查失敗，可能已過期或需要登入；預覽和下載可能無法使用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Text(
                url,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 2,
            )

            if (!selectionMode) {
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
                    resource.type == ResourceType.AUDIO ||
                    resource.type == ResourceType.STREAM
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
            } else {
                TextButton(onClick = onToggleSelected) {
                    Text(if (selected) "取消選取" else "選取")
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
    resource.fileName
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { return it }

    val sourceUrl = resource.finalUrl ?: resource.url
    val pathName = runCatching {
        Uri.decode(Uri.parse(sourceUrl).lastPathSegment.orEmpty())
    }.getOrNull()
        ?.substringBefore('?')
        ?.trim()
        ?.takeIf { it.isNotBlank() && it != "/" }
        ?.take(120)
    if (!pathName.isNullOrBlank()) return pathName

    return when (resource.uiCategory()) {
        UiResourceCategory.IMAGE -> "圖片資源"
        UiResourceCategory.VIDEO -> "影片資源"
        UiResourceCategory.DOCUMENT -> resource.extension?.uppercase()?.let { "$it 文件" } ?: "文件資源"
        UiResourceCategory.OTHER -> resource.extension?.uppercase() ?: resourceTypeLabel(resource.type)
    }
}

private fun resourceSummaryLine(resource: Resource): String {
    val parts = buildList {
        if (resource.width != null && resource.height != null) {
            add("${resource.width}×${resource.height}")
        }
        resource.contentLength?.let { add(formatBytes(it)) }
        resource.durationMs?.takeIf { it > 0 }?.let { add(formatDuration(it)) }
        resource.extension?.takeIf { it.isNotBlank() }?.uppercase()?.let { ext ->
            if (none { it.equals(ext, true) }) add(ext)
        }
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
    val previewScope = rememberCoroutineScope()

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

                    ResourceType.VIDEO, ResourceType.AUDIO, ResourceType.STREAM -> {
                        val context = LocalContext.current
                        val mediaUrl = resource.localCachePath
                            ?.takeIf { File(it).isFile }
                            ?.let { Uri.fromFile(File(it)) }
                            ?: Uri.parse(MediaPreviewSupport.playbackUrl(resource.finalUrl ?: url))
                        val headers = remember(resource.id, mediaUrl, resource.cookie) {
                            buildMap {
                                resource.userAgent?.takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }
                                resource.referer?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
                                (resource.cookie?.takeIf { it.isNotBlank() }
                                    ?: runCatching { CookieManager.getInstance().getCookie(mediaUrl.toString()) }.getOrNull())
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let { put("Cookie", it) }
                            }
                        }
                        val player = remember(resource.id, mediaUrl, headers) {
                            val dataSourceFactory = DefaultHttpDataSource.Factory()
                                .setAllowCrossProtocolRedirects(true)
                                .setDefaultRequestProperties(headers)
                            ExoPlayer.Builder(context)
                                .setMediaSourceFactory(
                                    DefaultMediaSourceFactory(context)
                                        .setDataSourceFactory(DefaultDataSource.Factory(context, dataSourceFactory))
                                )
                                .build()
                                .apply {
                                    addListener(object : Player.Listener {
                                        override fun onPlayerError(error: PlaybackException) {
                                            previewError = previewFailureMessage(error)
                                            if (mediaUrl.scheme in listOf("http", "https") &&
                                                error.errorCode in listOf(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                                                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED)) {
                                                previewScope.launch {
                                                    previewError = withContext(Dispatchers.IO) {
                                                        MediaPreviewSupport.diagnose(mediaUrl.toString(), headers)
                                                    }
                                                }
                                            }
                                        }
                                    })
                                    val item = MediaItem.Builder().setUri(mediaUrl)
                                    when (resource.streamType) {
                                        StreamType.HLS -> item.setMimeType(MimeTypes.APPLICATION_M3U8)
                                        StreamType.DASH -> item.setMimeType(MimeTypes.APPLICATION_MPD)
                                        else -> Unit
                                    }
                                    setMediaItem(item.build())
                                    prepare()
                                    playWhenReady = false
                                }
                        }
                        DisposableEffect(player) {
                            onDispose { player.release() }
                        }
                        AndroidView(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(300.dp),
                            factory = { ctx ->
                                PlayerView(ctx).apply {
                                    useController = true
                                    controllerAutoShow = true
                                    controllerHideOnTouch = true
                                    this.player = player
                                    setBackgroundColor(android.graphics.Color.BLACK)
                                }
                            },
                            update = { it.player = player },
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

private fun previewFailureMessage(error: PlaybackException): String = when (error.errorCode) {
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ->
        "無法解析這個媒體網址。它可能是需要播放清單的影片片段，或連結已失效。請選擇同一影片的 M3U8、MPD 或完整影片檔。"
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
        "媒體伺服器拒絕預覽要求；請重新開啟來源頁面，取得有效連結後再試。"
    PlaybackException.ERROR_CODE_DECODING_FAILED,
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ->
        "此裝置不支援這部影片的編碼格式。"
    else -> "媒體預覽失敗：${error.errorCodeName}"
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
          const MAX_RESULTS = 320;
          const MAX_PERFORMANCE = 180;
          const MAX_IMAGES = 100;

          function abs(url) {
            if (!url) return null;
            try { return new URL(url, document.baseURI).href; } catch (_) { return null; }
          }

          function put(url, kind, w, h, duration) {
            if (map.size >= MAX_RESULTS) return;
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
            return value.split(',').slice(0, 10).map(part => {
              const bits = part.trim().split(/\s+/);
              const descriptor = bits[1] || '';
              const width = descriptor.endsWith('w') ? parseInt(descriptor, 10) : null;
              return { url: bits[0], width: width };
            });
          }

          performance.getEntriesByType('resource')
            .slice(-MAX_PERFORMANCE)
            .forEach(e => put(e.name, null, null, null, null));

          Array.from(document.images).slice(0, MAX_IMAGES).forEach(e => {
            put(e.currentSrc || e.src, 'image', e.naturalWidth, e.naturalHeight, null);
            srcsetEntries(e.getAttribute('srcset')).forEach(x =>
              put(x.url, 'image', x.width, null, null)
            );
          });

          Array.from(document.querySelectorAll('picture source[srcset]')).slice(0, 80).forEach(e => {
            srcsetEntries(e.getAttribute('srcset')).forEach(x =>
              put(x.url, 'image', x.width, null, null)
            );
          });

          Array.from(document.querySelectorAll('video')).slice(0, 24).forEach(video => {
            put(video.currentSrc || video.src, 'video', video.videoWidth, video.videoHeight, video.duration);
            if (video.poster) put(video.poster, 'image', null, null, null);
            Array.from(video.querySelectorAll('source[src]')).slice(0, 8).forEach(e =>
              put(e.src, 'video', video.videoWidth, video.videoHeight, video.duration)
            );
          });

          Array.from(document.querySelectorAll('audio')).slice(0, 24).forEach(audio => {
            put(audio.currentSrc || audio.src, 'audio', null, null, audio.duration);
            Array.from(audio.querySelectorAll('source[src]')).slice(0, 8).forEach(e =>
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
            Array.from(document.querySelectorAll(pair[0])).slice(0, 6)
              .forEach(e => put(e.content, pair[1], null, null, null));
          });

          Array.from(document.querySelectorAll('[style*="url("]')).slice(0, 80).forEach(e => {
            const style = e.getAttribute('style') || '';
            const matches = style.match(/url\((['"]?)(.*?)\1\)/g) || [];
            matches.slice(0, 4).forEach(m => {
              const hit = m.match(/url\((['"]?)(.*?)\1\)/);
              if (hit && hit[2]) put(hit[2], 'image', null, null, null);
            });
          });

          return JSON.stringify(Array.from(map.values()).slice(0, MAX_RESULTS));
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

private fun browserUserAgent(context: Context, url: String): String {
    val mobile = WebSettings.getDefaultUserAgent(context)
        .replace("; wv", "")
        .replace("Version/4.0 ", "")
    return mobile
}

private fun WebView.loadBrowserUrl(url: String) {
    settings.userAgentString = browserUserAgent(context, url)
    loadUrl(url)
}

private fun resolveBrowsableUrl(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
        return trimmed
    }

    if (trimmed.startsWith("intent://", true)) {
        // Prefer reconstructing the original web target. App-provided
        // browser_fallback_url often points to Play Store and causes a loop.
        val body = trimmed
            .substringAfter("intent://")
            .substringBefore("#Intent;")
            .trimStart('/')
        if (body.isNotBlank() && body.substringBefore('/').contains('.')) {
            return "https://$body"
        }

        val parsed = runCatching {
            Intent.parseUri(trimmed, Intent.URI_INTENT_SCHEME)
        }.getOrNull()

        parsed?.dataString
            ?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
            ?.takeUnless(::isInstagramStoreUrl)
            ?.let { return it }

        parsed?.getStringExtra("browser_fallback_url")
            ?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
            ?.takeUnless(::isInstagramStoreUrl)
            ?.let { return it }
    }

    if (trimmed.startsWith("instagram://", true)) {
        val uri = runCatching { Uri.parse(trimmed) }.getOrNull() ?: return null
        val host = uri.host.orEmpty().lowercase()
        val path = uri.path.orEmpty().trim('/')
        val id = uri.getQueryParameter("id")
            ?: uri.getQueryParameter("shortcode")
            ?: path.takeIf { it.isNotBlank() }

        return when (host) {
            "reel", "reels" -> id?.let { "https://www.instagram.com/reel/$it/" }
            "p", "media" -> id?.let { "https://www.instagram.com/p/$it/" }
            "user", "profile" -> uri.getQueryParameter("username")
                ?.let { "https://www.instagram.com/$it/" }
            else -> null
        }
    }

    return null
}

private fun isInstagramHost(url: String?): Boolean {
    val host = runCatching { Uri.parse(url).host?.lowercase() }.getOrNull() ?: return false
    return host == "instagram.com" ||
        host == "www.instagram.com" ||
        host.endsWith(".instagram.com")
}

private fun isInstagramStoreUrl(url: String): Boolean {
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
    val scheme = uri.scheme?.lowercase()
    if (scheme == "market") {
        return uri.getQueryParameter("id") == "com.instagram.android"
    }

    val host = uri.host?.lowercase().orEmpty()
    if (host != "play.google.com") return false
    if (!uri.path.orEmpty().startsWith("/store/apps/")) return false
    return uri.getQueryParameter("id") == "com.instagram.android" ||
        url.contains("com.instagram.android", ignoreCase = true)
}

private fun sameBrowserTarget(a: String?, b: String?): Boolean {
    if (a.isNullOrBlank() || b.isNullOrBlank()) return false
    fun canonical(value: String): String {
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return value
        return uri.buildUpon()
            .fragment(null)
            .build()
            .toString()
            .trimEnd('/')
    }
    return canonical(a).equals(canonical(b), ignoreCase = true)
}

private fun normalizeUrl(value: String): String {
    val trimmed = value.trim()
    if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) return trimmed
    if (trimmed.any { it.isWhitespace() } || (!trimmed.contains(".") && !trimmed.contains(":"))) {
        return "https://www.google.com/search?q=" + Uri.encode(trimmed)
    }
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

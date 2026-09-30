package com.resourcesniffer.app.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import com.resourcesniffer.app.capture.MitmCertificateAuthority
import com.resourcesniffer.app.capture.SnifferVpnService
import com.resourcesniffer.app.core.MediaIdentity
import com.resourcesniffer.app.core.Resource
import com.resourcesniffer.app.core.ResourceClassifier
import com.resourcesniffer.app.core.ResourceType
import com.resourcesniffer.app.core.ResourceValidator
import com.resourcesniffer.app.overlay.OverlayService
import com.resourcesniffer.app.repository.SnifferRepository
import com.resourcesniffer.app.repository.SessionStore
import java.util.concurrent.atomic.AtomicLong

class MainViewModel(application: Application) : AndroidViewModel(application) {
    val resources = SnifferRepository.preferredResources
    val rawResources = SnifferRepository.resources
    val currentSession = SessionStore.current
    val browserSession = SessionStore.browser
    val externalSession = SessionStore.external
    private val ids = AtomicLong(System.currentTimeMillis())

    fun recordWebResource(
        url: String,
        mimeType: String? = null,
        requestHeaders: Map<String, String> = emptyMap(),
        width: Int? = null,
        height: Int? = null,
        durationMs: Long? = null,
    ) {
        val classification = ResourceClassifier.classify(url, mimeType)
        if (classification.type == ResourceType.OTHER) return

        val parsed = runCatching { Uri.parse(url) }.getOrNull()
        val extension = ResourceClassifier.extensionFromUrl(url).ifBlank { null }

        val session = SessionStore.ensureBrowserSession()
        val resource = Resource(
                id = ids.getAndIncrement(),
                sessionId = session.id,
                sourceAppPackage = null,
                sourceAppName = "內建瀏覽器",
                url = url,
                host = parsed?.host ?: "未知來源",
                mimeType = mimeType,
                extension = extension,
                contentLength = null,
                type = classification.type,
                streamType = classification.streamType,
                referer = requestHeaders.entries.firstOrNull { it.key.equals("Referer", true) }?.value,
                userAgent = requestHeaders.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value,
                cookie = requestHeaders.entries.firstOrNull { it.key.equals("Cookie", true) }?.value,
                width = width,
                height = height,
                durationMs = durationMs,
                mediaGroupKey = MediaIdentity.groupKey(url),
            )
        val generation = SnifferRepository.generation
        SnifferRepository.add(resource)
        ResourceValidator.validate(resource) { validated ->
            SnifferRepository.addIfGeneration(validated, generation)
        }
    }

    fun recordLocalResource(
        sourceUrl: String,
        localCachePath: String,
        mimeType: String?,
        contentLength: Long,
        fileName: String? = null,
        referer: String? = null,
    ) {
        val classificationUrl = if (!fileName.isNullOrBlank()) {
            sourceUrl + (if (sourceUrl.contains("?")) "&" else "?") + "filename=" + Uri.encode(fileName)
        } else sourceUrl
        val classification = ResourceClassifier.classify(classificationUrl, mimeType)
        if (classification.type == ResourceType.OTHER) return

        val session = SessionStore.ensureBrowserSession()
        val host = runCatching { Uri.parse(referer).host }.getOrNull() ?: "blob"
        SnifferRepository.add(
            Resource(
                id = ids.getAndIncrement(),
                sessionId = session.id,
                sourceAppPackage = null,
                sourceAppName = "內建瀏覽器",
                url = sourceUrl,
                host = host,
                mimeType = mimeType,
                extension = ResourceClassifier.extensionFromUrl(classificationUrl).ifBlank { null },
                fileName = fileName,
                localCachePath = localCachePath,
                contentLength = contentLength,
                type = classification.type,
                streamType = classification.streamType,
                referer = referer,
                mediaGroupKey = "local:" + localCachePath,
            )
        )
    }
    fun exportMitmCaCertificate(): Uri =
        MitmCertificateAuthority(getApplication<Application>()).exportToDownloads()

    fun caSettingsIntent(): Intent =
        Intent(Settings.ACTION_SECURITY_SETTINGS)

    fun isMitmCaInstalled(): Boolean =
        MitmCertificateAuthority(getApplication<Application>()).isInstalledInAndroidCaStore()

    fun mitmCaFingerprint(): String =
        MitmCertificateAuthority(getApplication<Application>()).fingerprintSha256()
    fun startExternalCapture(blockQuic: Boolean = false) {
        val context = getApplication<Application>()
        ContextCompat.startForegroundService(
            context,
            Intent(context, SnifferVpnService::class.java).apply {
                action = SnifferVpnService.ACTION_START
                putExtra(SnifferVpnService.EXTRA_BLOCK_QUIC, blockQuic)
                putExtra(
                    SnifferVpnService.EXTRA_ENABLE_HTTPS_MITM,
                    MitmCertificateAuthority(context).isInstalledInAndroidCaStore(),
                )
            }
        )
    }

    fun stopExternalCapture() {
        val context = getApplication<Application>()
        context.startService(
            Intent(context, SnifferVpnService::class.java).apply {
                action = SnifferVpnService.ACTION_STOP
            }
        )
    }

    fun startOverlay() {
        val context = getApplication<Application>()
        ContextCompat.startForegroundService(context, Intent(context, OverlayService::class.java))
    }

    fun clear() = SnifferRepository.clear()

    fun clearCurrentSession() = SnifferRepository.clearSession(SessionStore.idOrDefault())
}



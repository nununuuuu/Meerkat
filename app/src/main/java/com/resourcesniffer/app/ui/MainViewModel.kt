package com.resourcesniffer.app.ui

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import com.resourcesniffer.app.capture.SnifferVpnService
import com.resourcesniffer.app.core.InstalledApp
import com.resourcesniffer.app.core.Resource
import com.resourcesniffer.app.core.ResourceClassifier
import com.resourcesniffer.app.core.ResourceType
import com.resourcesniffer.app.overlay.OverlayService
import com.resourcesniffer.app.repository.SnifferRepository
import com.resourcesniffer.app.repository.SessionStore
import java.util.concurrent.atomic.AtomicLong

class MainViewModel(application: Application) : AndroidViewModel(application) {
    val resources = SnifferRepository.resources
    val currentSession = SessionStore.current
    private val ids = AtomicLong(System.currentTimeMillis())

    fun recordWebResource(
        url: String,
        mimeType: String? = null,
        requestHeaders: Map<String, String> = emptyMap(),
    ) {
        val classification = ResourceClassifier.classify(url, mimeType)
        if (classification.type == ResourceType.OTHER) return

        val parsed = runCatching { Uri.parse(url) }.getOrNull()
        val extension = parsed?.lastPathSegment
            ?.substringAfterLast('.', "")
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }

        val session = SessionStore.ensureBrowserSession()
        SnifferRepository.add(
            Resource(
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
            )
        )
    }

    fun installedApps(): List<InstalledApp> {
        val context = getApplication<Application>()
        val pm = context.packageManager
        val apps = if (Build.VERSION.SDK_INT >= 33) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(0)
        }

        return apps.asSequence()
            .filter { it.packageName != context.packageName }
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .map {
                InstalledApp(
                    label = pm.getApplicationLabel(it).toString(),
                    packageName = it.packageName,
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    fun startExternalCapture(packageName: String) {
        val context = getApplication<Application>()
        val pm = context.packageManager
        val appName = runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrNull()
        SessionStore.start(packageName, appName)
        ContextCompat.startForegroundService(
            context,
            Intent(context, SnifferVpnService::class.java).apply {
                action = SnifferVpnService.ACTION_START
                putExtra(SnifferVpnService.EXTRA_TARGET_PACKAGE, packageName)
            }
        )
        context.packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launch)
        }
    }

    fun stopExternalCapture() {
        val context = getApplication<Application>()
        SessionStore.stop()
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

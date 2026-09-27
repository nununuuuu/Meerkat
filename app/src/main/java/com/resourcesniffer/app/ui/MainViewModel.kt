package com.resourcesniffer.app.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import com.resourcesniffer.app.core.Resource
import com.resourcesniffer.app.core.ResourceClassifier
import com.resourcesniffer.app.core.ResourceType
import com.resourcesniffer.app.overlay.OverlayService
import com.resourcesniffer.app.repository.SnifferRepository
import java.util.concurrent.atomic.AtomicLong

class MainViewModel(application: Application) : AndroidViewModel(application) {
    val resources = SnifferRepository.resources
    private val ids = AtomicLong(System.currentTimeMillis())

    fun recordWebResource(url: String, mimeType: String? = null) {
        val classification = ResourceClassifier.classify(url, mimeType)
        if (classification.type == ResourceType.OTHER) return

        val parsed = runCatching { Uri.parse(url) }.getOrNull()
        val extension = parsed?.lastPathSegment
            ?.substringAfterLast('.', "")
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }

        SnifferRepository.add(
            Resource(
                id = ids.getAndIncrement(),
                sessionId = 1,
                sourceAppPackage = null,
                sourceAppName = "內建瀏覽器",
                url = url,
                host = parsed?.host ?: "未知來源",
                mimeType = mimeType,
                extension = extension,
                contentLength = null,
                type = classification.type,
                streamType = classification.streamType
            )
        )
    }

    fun startOverlay() {
        val context = getApplication<Application>()
        ContextCompat.startForegroundService(context, Intent(context, OverlayService::class.java))
    }

    fun clear() = SnifferRepository.clear()
}

package com.resourcesniffer.app.ui

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import com.resourcesniffer.app.capture.SnifferVpnService
import com.resourcesniffer.app.overlay.OverlayService
import com.resourcesniffer.app.repository.SnifferRepository

class MainViewModel(application: Application) : AndroidViewModel(application) {
    val resources = SnifferRepository.resources

    fun startCapture(targetPackage: String? = null) {
        val context = getApplication<Application>()
        val intent = Intent(context, SnifferVpnService::class.java).apply {
            action = SnifferVpnService.ACTION_START
            putExtra(SnifferVpnService.EXTRA_TARGET_PACKAGE, targetPackage)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun stopCapture() {
        val context = getApplication<Application>()
        context.startService(Intent(context, SnifferVpnService::class.java).apply {
            action = SnifferVpnService.ACTION_STOP
        })
    }

    fun startOverlay() {
        val context = getApplication<Application>()
        ContextCompat.startForegroundService(context, Intent(context, OverlayService::class.java))
    }

    fun clear() = SnifferRepository.clear()
}

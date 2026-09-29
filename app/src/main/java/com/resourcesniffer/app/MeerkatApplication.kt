package com.resourcesniffer.app

import android.app.Application
import com.resourcesniffer.app.repository.SnifferRepository
import com.resourcesniffer.app.download.DownloadRegistry

class MeerkatApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SnifferRepository.initialize(this)
        DownloadRegistry.initialize(this)
    }
}

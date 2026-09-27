package com.resourcesniffer.app

import android.app.Application
import com.resourcesniffer.app.repository.SnifferRepository

class MeerkatApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SnifferRepository.initialize(this)
    }
}

package com.resourcesniffer.app.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.resourcesniffer.app.R

class OverlayService : Service() {
    companion object {
        private const val CHANNEL_ID = "sniffer_overlay"
        private const val NOTIFICATION_ID = 1002
    }

    private lateinit var windowManager: WindowManager
    private var bubble: View? = null

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        createChannel()
        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("Meerkat 資源嗅探")
                .setContentText("懸浮控制已啟用")
                .setOngoing(true)
                .build()
        )
        showBubble()
    }

    private fun showBubble() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val view = TextView(this).apply {
            text = "M"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundResource(android.R.drawable.presence_online)
            setPadding(22, 22, 22, 22)
        }
        val params = WindowManager.LayoutParams(
            64,
            64,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 20
            y = 300
        }

        var lastX = 0
        var lastY = 0
        var downX = 0f
        var downY = 0f
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = params.x
                    lastY = params.y
                    downX = event.rawX
                    downY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = lastX - (event.rawX - downX).toInt()
                    params.y = lastY + (event.rawY - downY).toInt()
                    windowManager.updateViewLayout(view, params)
                    true
                }
                else -> false
            }
        }
        windowManager.addView(view, params)
        bubble = view
    }

    override fun onDestroy() {
        bubble?.let { runCatching { windowManager.removeView(it) } }
        bubble = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.overlay_channel_name), NotificationManager.IMPORTANCE_LOW)
            )
        }
    }
}

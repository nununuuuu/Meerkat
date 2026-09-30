package com.resourcesniffer.app.overlay

import kotlinx.coroutines.flow.asStateFlow
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.resourcesniffer.app.MainActivity
import com.resourcesniffer.app.R
import com.resourcesniffer.app.capture.CaptureStatus
import com.resourcesniffer.app.core.ResourceType
import com.resourcesniffer.app.repository.SnifferRepository
import com.resourcesniffer.app.repository.SessionStore

class OverlayService : Service() {
    companion object {
        private val mutableRunning = kotlinx.coroutines.flow.MutableStateFlow(false)
        val running = mutableRunning.asStateFlow()
        private const val CHANNEL_ID = "sniffer_overlay"
        private const val NOTIFICATION_ID = 1002
    }

    private lateinit var windowManager: WindowManager
    private var bubble: TextView? = null
    private var panel: View? = null
    private var lastSessionId = 0L
    private val handler = Handler(Looper.getMainLooper())

    private fun resourceSessionId(): Long {
        val external = SessionStore.externalIdOrDefault()
        if (external != 0L) lastSessionId = external
        if (lastSessionId == 0L) return SessionStore.browserIdOrDefault()
        return lastSessionId
    }

    private val updateCount = object : Runnable {
        override fun run() {
            val sessionId = resourceSessionId()
            val items = if (sessionId == 0L) {
                emptyList()
            } else {
                SnifferRepository.preferredResources.value.filter { it.sessionId == sessionId }
            }
            bubble?.text = items.size.toString()
            panel?.findViewWithTag<TextView>("summary")?.text = buildSummary(items)
            handler.postDelayed(this, 250)
        }
    }

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
                .setContentTitle("Meerkat 資源面板")
                .setContentText("顯示 Meerkat 已抓到的圖片、影片、文件與其他資源")
                .setOngoing(true)
                .setContentIntent(
                    PendingIntent.getActivity(
                        this,
                        0,
                        Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                )
                .build()
        )
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        showBubble()
        mutableRunning.value = true
        handler.post(updateCount)
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun showBubble() {
        val density = resources.displayMetrics.density
        val size = (58 * density).toInt()
        val view = TextView(this).apply {
            text = resourceSessionId().let { sessionId ->
                if (sessionId == 0L) 0
                else SnifferRepository.preferredResources.value.count { it.sessionId == sessionId }
            }.toString()
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF005140.toInt())
                setStroke((1 * density).toInt().coerceAtLeast(1), 0xFF78E6C0.toInt())
            }
            setPadding(12, 12, 12, 12)
        }
        val params = WindowManager.LayoutParams(
            size,
            size,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 18
            y = 300
        }

        var lastX = 0
        var lastY = 0
        var downX = 0f
        var downY = 0f
        var moved = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = params.x
                    lastY = params.y
                    downX = event.rawX
                    downY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) moved = true
                    params.x = lastX - dx.toInt()
                    params.y = lastY + dy.toInt()
                    windowManager.updateViewLayout(view, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) togglePanel()
                    true
                }
                else -> false
            }
        }

        windowManager.addView(view, params)
        bubble = view
    }

    private fun togglePanel() {
        if (panel != null) hidePanel() else showPanel()
    }

    private fun showPanel() {
        val density = resources.displayMetrics.density
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16*density).toInt(), (14*density).toInt(), (16*density).toInt(), (14*density).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 22 * density
                setColor(0xF21A201D.toInt())
                setStroke((1 * density).toInt().coerceAtLeast(1), 0xFF3F4945.toInt())
            }
        }

        val title = TextView(this).apply {
            text = "Meerkat 資源面板"
            setTextColor(0xFF96F4D5.toInt())
            textSize = 18f
        }
        val summary = TextView(this).apply {
            tag = "summary"
            val sessionId = resourceSessionId()
            text = buildSummary(
                if (sessionId == 0L) emptyList()
                else SnifferRepository.preferredResources.value.filter { it.sessionId == sessionId }
            )
            setTextColor(0xFFBEC9C4.toInt())
            textSize = 14f
            setPadding(0, (8*density).toInt(), 0, (10*density).toInt())
        }
        val open = Button(this).apply {
            text = "查看資源"
            setTextColor(0xFF00382C.toInt())
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF78E6C0.toInt())
            setOnClickListener {
                startActivity(
                    Intent(this@OverlayService, MainActivity::class.java)
                        .putExtra("open_resources", true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
                hidePanel()
            }
        }
        val clear = Button(this).apply {
            text = "清空目前資源"
            setTextColor(0xFFE0E4E1.toInt())
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF354C43.toInt())
            setOnClickListener { SnifferRepository.clearSession(resourceSessionId()) }
        }
        val close = Button(this).apply {
            text = "收起面板"
            setTextColor(0xFFFFDAD6.toInt())
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF8C1D18.toInt())
            setOnClickListener {
                hidePanel()
            }
        }
        val dismissOverlay = Button(this).apply {
            text = "關閉懸浮窗"
            setTextColor(0xFFE0E4E1.toInt())
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF354C43.toInt())
            setOnClickListener { stopSelf() }
        }
        val bottomActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(close, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(dismissOverlay, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        layout.addView(title)
        layout.addView(summary)
        layout.addView(open)
        layout.addView(clear)
        layout.addView(bottomActions)

        val params = WindowManager.LayoutParams(
            minOf((320*density).toInt(), resources.displayMetrics.widthPixels - (32*density).toInt()),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 18
            y = (100*density).toInt()
        }

        windowManager.addView(layout, params)
        panel = layout
    }

    private fun hidePanel() {
        panel?.let { runCatching { windowManager.removeView(it) } }
        panel = null
    }

    private fun buildSummary(items: List<com.resourcesniffer.app.core.Resource>): String {
        if (items.isEmpty()) {
            return CaptureStatus.state.value.summary() + "\n尚未找到可下載資源"
        }
        val imageCount = items.count { it.type == ResourceType.IMAGE }
        val videoCount = items.count {
            it.type == ResourceType.VIDEO || it.type == ResourceType.STREAM
        }
        val documentCount = items.count { it.type == ResourceType.DOCUMENT }
        val otherCount = items.count {
            it.type == ResourceType.AUDIO ||
                it.type == ResourceType.ARCHIVE ||
                it.type == ResourceType.OTHER
        }
        return CaptureStatus.state.value.summary() + "\n圖片 $imageCount　影片 $videoCount\n" +
            "文件 $documentCount　其他 $otherCount\n" +
            "總計 ${items.size}"
    }

    override fun onDestroy() {
        mutableRunning.value = false
        handler.removeCallbacks(updateCount)
        hidePanel()
        bubble?.let { runCatching { windowManager.removeView(it) } }
        bubble = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.overlay_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }
    }
}

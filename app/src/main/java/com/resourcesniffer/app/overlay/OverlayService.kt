package com.resourcesniffer.app.overlay

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
import com.resourcesniffer.app.capture.SnifferVpnService
import com.resourcesniffer.app.core.ResourceType
import com.resourcesniffer.app.repository.SnifferRepository
import com.resourcesniffer.app.repository.SessionStore

class OverlayService : Service() {
    companion object {
        private const val CHANNEL_ID = "sniffer_overlay"
        private const val NOTIFICATION_ID = 1002
    }

    private lateinit var windowManager: WindowManager
    private var bubble: TextView? = null
    private var panel: View? = null
    private val handler = Handler(Looper.getMainLooper())

    private val updateCount = object : Runnable {
        override fun run() {
            val sessionId = SessionStore.idOrDefault()
            val items = SnifferRepository.resources.value.filter {
                sessionId == 0L || it.sessionId == sessionId
            }
            bubble?.text = items.size.toString()
            panel?.findViewWithTag<TextView>("summary")?.text = buildSummary(items)
            handler.postDelayed(this, 700)
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
                .setContentTitle("Meerkat 資源嗅探")
                .setContentText("懸浮控制已啟用")
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
            text = SnifferRepository.resources.value.count {
                val sessionId = SessionStore.idOrDefault()
                sessionId == 0L || it.sessionId == sessionId
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
            text = "Meerkat 資源嗅探"
            setTextColor(0xFF96F4D5.toInt())
            textSize = 18f
        }
        val summary = TextView(this).apply {
            tag = "summary"
            text = buildSummary(
                SnifferRepository.resources.value.filter {
                    val sessionId = SessionStore.idOrDefault()
                    sessionId == 0L || it.sessionId == sessionId
                }
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
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
                hidePanel()
            }
        }
        val clear = Button(this).apply {
            text = "清空目前資源"
            setTextColor(0xFFE0E4E1.toInt())
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF354C43.toInt())
            setOnClickListener { SnifferRepository.clearSession(SessionStore.idOrDefault()) }
        }
        val stop = Button(this).apply {
            text = "停止嗅探"
            setTextColor(0xFFFFDAD6.toInt())
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF8C1D18.toInt())
            setOnClickListener {
                startService(
                    Intent(this@OverlayService, SnifferVpnService::class.java).apply {
                        action = SnifferVpnService.ACTION_STOP
                    }
                )
                stopSelf()
            }
        }

        layout.addView(title)
        layout.addView(summary)
        layout.addView(open)
        layout.addView(clear)
        layout.addView(stop)

        val params = WindowManager.LayoutParams(
            (280*density).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 18
            y = (370*density).toInt()
        }

        windowManager.addView(layout, params)
        panel = layout
    }

    private fun hidePanel() {
        panel?.let { runCatching { windowManager.removeView(it) } }
        panel = null
    }

    private fun buildSummary(items: List<com.resourcesniffer.app.core.Resource>): String {
        fun count(type: ResourceType) = items.count { it.type == type }
        if (items.isEmpty()) {
            return "本次尚未取得可下載資源\nHTTPS App 通常無法從加密流量取得完整 URL"
        }
        return "圖片 ${count(ResourceType.IMAGE)}　影片 ${count(ResourceType.VIDEO)}\n" +
            "音訊 ${count(ResourceType.AUDIO)}　串流 ${count(ResourceType.STREAM)}\n" +
            "文件 ${count(ResourceType.DOCUMENT)}　總計 ${items.size}"
    }

    override fun onDestroy() {
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

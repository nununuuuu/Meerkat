package com.resourcesniffer.app

import android.app.Dialog
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension

class GeckoPocActivity : ComponentActivity() {
    companion object {
        private var runtime: GeckoRuntime? = null
        private const val CAT_CATCH_ID = "xifangczy@gmail.com"
        private const val CAT_CATCH_URI = "resource://android/assets/catcatch/"
    }

    private lateinit var session: GeckoSession
    private lateinit var geckoView: GeckoView
    private lateinit var status: TextView
    private lateinit var address: EditText
    private var catCatchAction: WebExtension.Action? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        status = TextView(this).apply {
            text = "GeckoView 啟動中…"
            setPadding(20, 12, 20, 8)
        }
        address = EditText(this).apply {
            setSingleLine(true)
            setText("https://www.google.com/")
            hint = "網址"
        }
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val open = Button(this).apply { text = "開啟" }
        val back = Button(this).apply { text = "返回" }
        val reload = Button(this).apply { text = "重新整理" }
        val cat = Button(this).apply {
            text = "貓抓"
            isEnabled = false
        }
        controls.addView(open, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        controls.addView(back, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        controls.addView(reload, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        controls.addView(cat, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        geckoView = GeckoView(this)
        root.addView(status)
        root.addView(address)
        root.addView(controls)
        root.addView(geckoView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        val geckoRuntime = runtime ?: GeckoRuntime.create(this).also { runtime = it }
        session = GeckoSession()
        session.contentDelegate = object : GeckoSession.ContentDelegate {}
        session.open(geckoRuntime)
        geckoView.setSession(session)

        open.setOnClickListener {
            val value = normalize(address.text.toString())
            address.setText(value)
            session.loadUri(value)
        }
        back.setOnClickListener { session.goBack() }
        reload.setOnClickListener { session.reload() }
        cat.setOnClickListener {
            catCatchAction?.click() ?: run { status.text = "Cat Catch action 尚未就緒" }
        }

        geckoRuntime.webExtensionController
            .ensureBuiltIn(CAT_CATCH_URI, CAT_CATCH_ID)
            .accept({ extension ->
                val delegate = object : WebExtension.ActionDelegate {
                    override fun onBrowserAction(
                        extension: WebExtension,
                        session: GeckoSession?,
                        action: WebExtension.Action
                    ) {
                        catCatchAction = action
                        runOnUiThread {
                            cat.isEnabled = true
                            status.text = "Cat Catch " + extension.metaData.version + " 已載入"
                        }
                    }

                    override fun onOpenPopup(
                        extension: WebExtension,
                        action: WebExtension.Action
                    ): GeckoResult<GeckoSession> = showPopup(geckoRuntime)

                    override fun onTogglePopup(
                        extension: WebExtension,
                        action: WebExtension.Action
                    ): GeckoResult<GeckoSession> = showPopup(geckoRuntime)
                }
                extension.setActionDelegate(delegate)
                runOnUiThread {
                    cat.isEnabled = true
                    status.text = "Cat Catch " + extension.metaData.version + " 已安裝"
                }
            }, { error ->
                runOnUiThread {
                    cat.isEnabled = false
                    status.text = "Cat Catch 安裝失敗：" + (error.message ?: error.javaClass.simpleName)
                }
            })

        session.loadUri(address.text.toString())
    }

    private fun showPopup(geckoRuntime: GeckoRuntime): GeckoResult<GeckoSession> {
        val popupSession = GeckoSession()
        popupSession.contentDelegate = object : GeckoSession.ContentDelegate {}
        popupSession.open(geckoRuntime)
        runOnUiThread {
            val popupView = GeckoView(this)
            popupView.setSession(popupSession)
            Dialog(this).apply {
                setTitle("Cat Catch")
                setContentView(
                    popupView,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        (resources.displayMetrics.heightPixels * 0.72f).toInt(),
                    ),
                )
                setOnDismissListener { runCatching { popupSession.close() } }
                show()
                window?.setLayout(
                    (resources.displayMetrics.widthPixels * 0.94f).toInt(),
                    (resources.displayMetrics.heightPixels * 0.78f).toInt(),
                )
            }
        }
        return GeckoResult.fromValue(popupSession)
    }

    override fun onDestroy() {
        runCatching { session.close() }
        super.onDestroy()
    }

    private fun normalize(value: String): String {
        val text = value.trim()
        return if (text.startsWith("http://") || text.startsWith("https://")) text
        else "https://" + text
    }
}

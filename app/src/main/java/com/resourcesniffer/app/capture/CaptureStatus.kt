package com.resourcesniffer.app.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CaptureSnapshot(
    val running: Boolean = false,
    val starting: Boolean = false,
    val httpsEnabled: Boolean = false,
    val connections: Long = 0,
    val bytes: Long = 0,
    val quicConnections: Long = 0,
    val opaqueTlsConnections: Long = 0,
    val decryptedConnections: Long = 0,
    val failures: Long = 0,
    val error: String? = null,
) {
    fun summary(): String = when {
        starting -> "正在啟動 VPN…"
        error != null && !running -> "嗅探啟動失敗：$error"
        !running -> "全域嗅探已停止"
        connections == 0L -> "VPN 已啟動，等待其他 App 的新連線"
        else -> "已收到 $connections 條連線 · ${bytes / 1024} KB\n" +
            "HTTPS 已解析 $decryptedConnections · 無法解密 $opaqueTlsConnections · HTTP/3 $quicConnections\n轉送失敗 $failures" +
            if (!httpsEnabled) "\n尚未安裝 CA：HTTPS 只能轉送，無法取得資源網址" else ""
    }
}

/** Only the VPN service reports readiness; UI never predicts a running tunnel. */
object CaptureStatus {
    private val mutable = MutableStateFlow(CaptureSnapshot())
    val state = mutable.asStateFlow()

    @Synchronized fun starting() { mutable.value = CaptureSnapshot(starting = true) }
    @Synchronized fun started(https: Boolean) {
        mutable.value = mutable.value.copy(starting = false, running = true, httpsEnabled = https)
    }
    @Synchronized fun stopped(error: String? = null) {
        mutable.value = mutable.value.copy(starting = false, running = false, error = error)
    }
    @Synchronized fun connection(quic: Boolean = false, opaqueTls: Boolean = false) {
        val s = mutable.value
        mutable.value = s.copy(connections = s.connections + 1,
            quicConnections = s.quicConnections + if (quic) 1 else 0,
            opaqueTlsConnections = s.opaqueTlsConnections + if (opaqueTls) 1 else 0)
    }
    @Synchronized fun transferred(count: Int) {
        if (count > 0) mutable.value = mutable.value.copy(bytes = mutable.value.bytes + count)
    }
    @Synchronized fun decrypted() {
        mutable.value = mutable.value.copy(decryptedConnections = mutable.value.decryptedConnections + 1)
    }
    @Synchronized fun opaqueTls() {
        mutable.value = mutable.value.copy(opaqueTlsConnections = mutable.value.opaqueTlsConnections + 1)
    }
    @Synchronized fun failure() {
        mutable.value = mutable.value.copy(failures = mutable.value.failures + 1)
    }
}

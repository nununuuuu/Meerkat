package com.resourcesniffer.app.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CaptureSnapshot(
    val running: Boolean = false,
    val starting: Boolean = false,
    val httpsEnabled: Boolean = false,
    val blockQuic: Boolean = false,
    val connections: Long = 0,
    val bytes: Long = 0,
    val tcp443Connections: Long = 0,
    val quicConnections: Long = 0,
    val tlsClientHellos: Long = 0,
    val nonTls443Connections: Long = 0,
    val opaqueTlsConnections: Long = 0,
    val decryptedConnections: Long = 0,
    val failures: Long = 0,
    val lastFailure: String? = null,
    val error: String? = null,
) {
    fun summary(): String = when {
        starting -> "正在啟動 VPN…"
        error != null && !running -> "嗅探啟動失敗：$error"
        !running -> "全域嗅探已停止"
        connections == 0L -> "VPN 已啟動，等待其他 App 的新連線"
        else -> "已收到 $connections 條連線 · ${bytes / 1024} KB\n" +
            "TCP/443 $tcp443Connections · UDP/443 $quicConnections（${if (blockQuic) "已停用" else "轉送中"}）\n" +
            "TLS 握手辨識 $tlsClientHellos · 非 TLS/443 $nonTls443Connections · HTTPS 已解析 $decryptedConnections · ${if (httpsEnabled) "無法解密" else "HTTPS 未檢查"} $opaqueTlsConnections\n轉送失敗 $failures" +
            (lastFailure?.let { " · 最近：$it" } ?: "") +
            if (!httpsEnabled) "\n本次未啟用 HTTPS 解密：加密流量只會轉送，無法取得資源網址" else ""
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
    @Synchronized fun quicMode(enabled: Boolean) { mutable.value = mutable.value.copy(blockQuic = enabled) }
    @Synchronized fun stopped(error: String? = null) {
        mutable.value = mutable.value.copy(starting = false, running = false, error = error)
    }
    @Synchronized fun connection(quic: Boolean = false, opaqueTls: Boolean = false, tcp443: Boolean = false) {
        val s = mutable.value
        mutable.value = s.copy(connections = s.connections + 1,
            tcp443Connections = s.tcp443Connections + if (tcp443) 1 else 0,
            quicConnections = s.quicConnections + if (quic) 1 else 0,
            opaqueTlsConnections = s.opaqueTlsConnections + if (opaqueTls) 1 else 0)
    }
    @Synchronized fun tlsClientHello() {
        mutable.value = mutable.value.copy(tlsClientHellos = mutable.value.tlsClientHellos + 1)
    }
    @Synchronized fun nonTls443() {
        mutable.value = mutable.value.copy(nonTls443Connections = mutable.value.nonTls443Connections + 1)
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
    @Synchronized fun failure(reason: String? = null) {
        mutable.value = mutable.value.copy(
            failures = mutable.value.failures + 1,
            lastFailure = reason ?: mutable.value.lastFailure,
        )
    }
}

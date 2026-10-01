package com.resourcesniffer.app.capture

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CaptureStatusTest {
    @Before fun reset() {
        CaptureStatus.starting()
        CaptureStatus.started(false)
    }

    @Test fun tcpCompatibilityCanToggleWithoutCaOrRestartingCapture() {
        CaptureStatus.connection(quic = true)
        CaptureStatus.transferred(2048)
        CaptureStatus.quicMode(true)
        val blocked = CaptureStatus.state.value
        assertTrue(blocked.running)
        assertFalse(blocked.httpsEnabled)
        assertTrue(blocked.blockQuic)
        assertTrue(blocked.summary().contains("已停用"))
        CaptureStatus.quicMode(false)
        val resumed = CaptureStatus.state.value
        assertTrue(resumed.running)
        assertFalse(resumed.blockQuic)
        assertEquals(2048L, resumed.bytes)
        assertEquals(1L, resumed.connections)
        assertEquals(1L, resumed.quicConnections)
    }

    @Test fun failedStartupNeverReportsRunningCapture() {
        CaptureStatus.starting()
        CaptureStatus.stopped("VPN permission denied")
        val state = CaptureStatus.state.value
        assertFalse(state.running)
        assertFalse(state.starting)
        assertTrue(state.summary().contains("VPN permission denied"))
    }

    @Test fun diagnosticsDistinguishTcpTlsFromUdp443() {
        CaptureStatus.connection(quic = true)
        CaptureStatus.connection(tcp443 = true)
        CaptureStatus.tlsClientHello()
        val state = CaptureStatus.state.value
        assertEquals(1L, state.tcp443Connections)
        assertEquals(1L, state.quicConnections)
        assertEquals(1L, state.tlsClientHellos)
        assertTrue(state.summary().contains("TCP/443 1 · UDP/443 1"))
    }

    @Test fun proxyStagesShowWhereTlsStopped() {
        CaptureStatus.starting()
        CaptureStatus.started(true)
        CaptureStatus.connection(tcp443 = true)
        CaptureStatus.proxyAccepted()
        CaptureStatus.proxyUpstreamConnected()
        val state = CaptureStatus.state.value
        assertEquals(1L, state.proxyAccepted)
        assertEquals(1L, state.proxyUpstreamConnected)
        assertEquals(0L, state.proxyClientTlsCompleted)
        assertTrue(state.summary().contains("代理接入 1 · 上游連接 1 · 裝置 TLS 完成 0"))
    }
    @Test fun unmatchedTrafficIsNotMisreportedAsWaitingForConnections() {
        CaptureStatus.observed()
        CaptureStatus.unattributed("系統未回傳連線所屬 App")
        val status = CaptureStatus.state.value
        assertEquals(0L, status.connections)
        assertTrue(status.summary().contains("無法辨識歸屬 1"))
        assertFalse(status.summary().contains("等待其他 App"))
    }
}

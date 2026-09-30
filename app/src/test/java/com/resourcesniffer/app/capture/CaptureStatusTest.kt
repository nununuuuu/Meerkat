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
}

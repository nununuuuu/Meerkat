package com.resourcesniffer.app.capture

import java.net.Socket
import org.junit.Assert.*
import org.junit.Test

class UpstreamSocketTest {
    @Test fun descriptorIsBoundBeforeProtectionAndBeforeRemoteConnect() {
        Socket().use { socket ->
            prepareUpstreamSocket(socket) {
                assertTrue(it.isBound)
                assertTrue(it.localPort > 0)
                assertFalse(it.isConnected)
                true
            }
        }
    }
    @Test(expected = IllegalStateException::class)
    fun failedProtectionIsReported() {
        Socket().use { prepareUpstreamSocket(it) { false } }
    }
}

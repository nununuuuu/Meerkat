package com.resourcesniffer.app.capture

import org.junit.Assert.*
import org.junit.Test
import javax.net.ssl.SSLHandshakeException

class TlsDiagnosticsTest {
    @Test fun nestedCertificateAlertRetainsActualReason() {
        val error = SSLHandshakeException("Handshake failed").apply { initCause(java.io.IOException("TLS alert certificate_unknown")) }
        val details = tlsCauseChain(error)
        assertTrue(details.contains("TLS alert certificate_unknown"))
        assertTrue(describeClientHandshake(details).contains("App 拒絕代理憑證"))
    }
    @Test fun protocolFailureIsNotLabeledCertificatePinning() {
        val result = describeClientHandshake("TLS alert no_application_protocol")
        assertTrue(result.contains("ALPN"))
        assertFalse(result.contains("憑證釘選"))
    }
    @Test fun unknownFailureDoesNotClaimCertificateRejection() {
        assertFalse(describeClientHandshake("connection reset by peer").contains("拒絕代理憑證"))
    }
}

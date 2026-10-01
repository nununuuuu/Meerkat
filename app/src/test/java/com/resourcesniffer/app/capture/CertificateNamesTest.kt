package com.resourcesniffer.app.capture

import javax.security.auth.x500.X500Principal
import org.bouncycastle.asn1.x500.X500Name
import org.junit.Assert.*
import org.junit.Test

class CertificateNamesTest {
    @Test fun issuerPreservesCaSubjectRdnOrder() {
        val original = X500Name("CN=Meerkat Local CA,O=Meerkat")
        val principal = X500Principal(original.encoded)
        assertFalse(original.encoded.contentEquals(X500Name(principal.name).encoded))
        assertArrayEquals(original.encoded, certificateIssuer(principal).encoded)
    }
}

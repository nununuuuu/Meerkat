package com.resourcesniffer.app.settings

import org.junit.Assert.*
import org.junit.Test

class CredentialOriginTest {
    @Test fun onlyExactHttpsOriginMatches() {
        assertEquals(credentialOrigin("https://EXAMPLE.com:443/login"), credentialOrigin("https://example.com/account"))
        assertNotEquals(credentialOrigin("https://example.com"), credentialOrigin("https://example.com.evil.test"))
        assertNotEquals(credentialOrigin("https://example.com"), credentialOrigin("https://example.com:8443"))
        assertNotEquals(credentialOrigin("https://example.com"), credentialOrigin("https://login.example.com"))
    }
    @Test fun rejectsUnsafeOrigins() {
        listOf("http://example.com", "javascript:alert(1)", "https://user@example.com", "not a url", "file:///example.com").forEach {
            assertNull(credentialOrigin(it))
        }
    }
}

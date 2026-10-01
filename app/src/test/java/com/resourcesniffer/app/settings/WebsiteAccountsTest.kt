package com.resourcesniffer.app.settings

import org.junit.Assert.*
import org.junit.Test

class WebsiteAccountsTest {
    @Test fun acceptsLoginPathsAndAddsHttps() {
        assertEquals("https://example.com/login?next=%2Fhome", websiteUrl("example.com/login?next=%2Fhome"))
        assertEquals("http://localhost:8080/login", websiteUrl("http://localhost:8080/login"))
    }
    @Test fun rejectsExecutableUrlsAndEmbeddedCredentials() {
        listOf("javascript:alert(1)", "file:///etc/passwd", "intent://login", "https://user:password@example.com", "", "https://bad host").forEach {
            assertNull(it, websiteUrl(it))
        }
    }
}

package com.resourcesniffer.app.core

import org.junit.Assert.*
import org.junit.Test

class ResourceEligibilityTest {
    @Test fun ignoresHtmlPlayersEvenWhenDomReportsVideo() {
        assertTrue(ResourceEligibility.isPageOrBackgroundResponse("https://example.com/player.html", "video/*"))
        assertTrue(ResourceEligibility.isPageOrBackgroundResponse("https://example.com/a.mp4", "text/html; charset=utf-8"))
    }
    @Test fun doesNotCallPlainTextApiRepliesDocuments() {
        assertTrue(ResourceEligibility.isPageOrBackgroundResponse("https://example.com/log/web?content_type=pv", "text/plain"))
    }
    @Test fun preservesExplicitDocumentsAndActualMediaEndpoints() {
        assertFalse(ResourceEligibility.isPageOrBackgroundResponse("https://example.com/report.txt", "text/plain"))
        assertFalse(ResourceEligibility.isPageOrBackgroundResponse("https://example.com/file?filename=report.txt", "text/plain"))
        assertFalse(ResourceEligibility.isPageOrBackgroundResponse("https://example.com/download", "text/plain", "notes.txt"))
        assertFalse(ResourceEligibility.isPageOrBackgroundResponse("https://example.com/download.php", "video/mp4"))
        assertFalse(ResourceEligibility.isPageOrBackgroundResponse("https://example.com/photo.png@1200w", "image/webp"))
        assertFalse(ResourceEligibility.isPageOrBackgroundResponse("https://example.com/document", "application/pdf"))
    }
}

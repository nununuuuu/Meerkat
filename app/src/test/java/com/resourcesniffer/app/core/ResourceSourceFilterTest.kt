package com.resourcesniffer.app.core

import org.junit.Assert.*
import org.junit.Test

class ResourceSourceFilterTest {
    @Test fun includesMediaCdnAlongsidePageDomain() {
        assertTrue(isMetaResourceHost("www.instagram.com"))
        assertTrue(isMetaResourceHost("instagram.fkhh1-2.fna.fbcdn.net"))
        assertTrue(isMetaResourceHost("Scontent.CdnInstagram.com."))
    }
    @Test fun excludesUnrelatedBackgroundAndLookalikeHosts() {
        assertFalse(isMetaResourceHost("h.trace.qq.com"))
        assertFalse(isMetaResourceHost("paydns.wechat.com"))
        assertFalse(isMetaResourceHost("instagram.com.example.org"))
        assertFalse(isMetaResourceHost("notfbcdn.net"))
    }
}

package com.resourcesniffer.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaPreviewSupportTest {
    @Test fun restoresWholeCdnFileWithoutReencodingSignature() {
        assertEquals("https://video.fbcdn.net/a.mp4?sig=a%2Fb%3D&x=2",
            MediaPreviewSupport.playbackUrl("https://video.fbcdn.net/a.mp4?sig=a%2Fb%3D&bytestart=123&byteend=456&x=2"))
    }
    @Test fun leavesOtherServersUntouched() {
        val url = "https://example.com/a.mp4?bytestart=123&byteend=456"
        assertEquals(url, MediaPreviewSupport.playbackUrl(url))
    }
}

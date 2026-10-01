package com.resourcesniffer.app.capture

import org.junit.Assert.*
import org.junit.Test

class ForegroundTimelineTest {
    @Test fun delayedResumeIsStillInsideNextQueryWindow() {
        val state = ForegroundTimeline()
        state.accept(1000, "meerkat")
        assertTrue(state.queryStart(5000) < 3000)
        state.accept(3000, "browser")
        assertEquals("browser", state.packageName)
    }
    @Test fun replayAndOlderEventsDoNotReplaceNewestApp() {
        val state = ForegroundTimeline()
        state.accept(3000, "browser")
        state.accept(1000, "meerkat")
        state.accept(3000, "browser")
        assertEquals("browser", state.packageName)
        state.accept(4000, "photos")
        assertEquals("photos", state.packageName)
    }
    @Test fun lockedScreenDoesNotReplayPreviousApp() {
        val state = ForegroundTimeline()
        state.accept(1000, "browser")
        state.accept(2000, null)
        state.accept(1000, "browser")
        assertNull(state.packageName)
        state.accept(3000, "browser")
        assertEquals("browser", state.packageName)
    }
}

package com.resourcesniffer.app.capture

import org.junit.Assert.*
import org.junit.Test

class ForegroundSelectionTest {
    @Test fun rejectsUnknownAndBackgroundOwners() {
        val state = ForegroundSelection()
        assertFalse(state.accepts(null, state.generation))
        state.change("instagram")
        assertTrue(state.accepts("instagram", state.generation))
        assertFalse(state.accepts("wechat", state.generation))
    }
    @Test fun switchingAwayAndBackDoesNotReactivateOldConnection() {
        val state = ForegroundSelection()
        state.change("instagram")
        val original = state.generation
        state.change("wechat")
        state.change("instagram")
        assertFalse(state.accepts("instagram", original))
        assertTrue(state.accepts("instagram", state.generation))
    }
    @Test fun pauseLockAndRestartInvalidateConnections() {
        val state = ForegroundSelection()
        state.change("instagram")
        val original = state.generation
        state.change(null)
        assertFalse(state.accepts("instagram", original))
        state.change("instagram")
        val resumed = state.generation
        state.reset()
        assertFalse(state.accepts("instagram", resumed))
    }
}

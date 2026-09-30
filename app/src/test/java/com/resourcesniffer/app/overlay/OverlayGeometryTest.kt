package com.resourcesniffer.app.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayGeometryTest {
    @Test fun bubbleStaysOnPanelCornerWhenOpened() {
        val position = OverlayGeometry.openAtBubble(
            bubbleX = 20, bubbleY = 800, panelWidth = 300, panelHeight = 300,
            bubbleSize = 60, screenWidth = 600, screenHeight = 1000,
        )
        assertEquals(50, position.panelX)
        assertEquals(530, position.panelY)
        assertEquals(20, position.bubbleX)
        assertEquals(800, position.bubbleY)
        assertTrue(position.corner.right)
        assertTrue(position.corner.bottom)
    }

    @Test fun bottomCollisionMovesBubbleToTopCorner() {
        val position = OverlayGeometry.place(
            desiredPanelX = 100, desiredPanelY = 800,
            corner = OverlayCorner(right = true, bottom = true),
            panelWidth = 300, panelHeight = 300, bubbleSize = 60,
            screenWidth = 600, screenHeight = 1000,
        )
        assertEquals(670, position.panelY)
        assertEquals(640, position.bubbleY)
        assertFalse(position.corner.bottom)
    }

    @Test fun sideCollisionMovesBubbleToOppositeCorner() {
        val position = OverlayGeometry.place(
            desiredPanelX = 0, desiredPanelY = 100,
            corner = OverlayCorner(right = true, bottom = false),
            panelWidth = 300, panelHeight = 300, bubbleSize = 60,
            screenWidth = 600, screenHeight = 1000,
        )
        assertEquals(30, position.panelX)
        assertEquals(300, position.bubbleX)
        assertFalse(position.corner.right)
    }

    @Test fun narrowScreenKeepsChosenCornerUntilDraggedPastEdge() {
        val position = OverlayGeometry.openAtBubble(
            bubbleX = 0, bubbleY = 100, panelWidth = 302, panelHeight = 300,
            bubbleSize = 58, screenWidth = 360, screenHeight = 800,
        )
        assertEquals(29, position.panelX)
        assertEquals(0, position.bubbleX)
        assertTrue(position.corner.right)
    }
}

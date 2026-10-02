package com.resourcesniffer.app.core

import org.junit.Assert.*
import org.junit.Test

class CaptureReportGateTest {
    @Test fun repeatedObservationsDoNotEnqueueAnotherCheck() {
        val gate = CaptureReportGate()
        val report = listOf(1L, 0L, "https://example.com/video.mp4", "video/mp4")
        assertTrue(gate.accept(report))
        repeat(500) { assertFalse(gate.accept(report.toList())) }
        assertTrue(gate.accept(report + "poster.jpg"))
        assertTrue(gate.accept(listOf(1L, 1L) + report.drop(2)))
    }
    @Test fun memoryRemainsBoundedAndEvictedItemsCanReturn() {
        val gate = CaptureReportGate(2)
        assertTrue(gate.accept(listOf("a")))
        assertTrue(gate.accept(listOf("b")))
        assertTrue(gate.accept(listOf("c")))
        assertTrue(gate.accept(listOf("a")))
    }
}

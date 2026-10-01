package com.resourcesniffer.app.settings

import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class UpdateDownloadTest {
    @Test fun reportsProgressAcrossChunksAndPreservesBytes() {
        val source = ByteArray(200_000) { (it % 251).toByte() }
        val progress = mutableListOf<Long>()
        val output = ByteArrayOutputStream()
        copyUpdatePayload(source.inputStream(), output, source.size.toLong()) { read, size ->
            assertEquals(source.size.toLong(), size)
            progress += read
        }
        assertEquals(0L, progress.first())
        assertEquals(source.size.toLong(), progress.last())
        assertTrue(progress.size > 3)
        assertTrue(progress.zipWithNext().all { (a, b) -> b > a })
        assertArrayEquals(source, output.toByteArray())
    }
    @Test fun verifiesDigestOfDownloadedBytes() {
        val hash = copyUpdatePayload("abc".byteInputStream(), ByteArrayOutputStream(), 3) { _, _ -> }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hash)
    }
}

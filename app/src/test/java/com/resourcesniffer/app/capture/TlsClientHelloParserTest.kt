package com.resourcesniffer.app.capture

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class TlsClientHelloParserTest {
    private fun hello(): ByteArray {
        val host = "media.example.com".toByteArray()
        val extension = ByteArrayOutputStream().apply {
            write(0); write(host.size + 3); write(0)
            write(0); write(host.size); write(host)
        }.toByteArray()
        val body = ByteArrayOutputStream().apply {
            write(byteArrayOf(3, 3)); write(ByteArray(32))
            write(0) // session id
            write(byteArrayOf(0, 2, 0, 47)) // cipher suites
            write(byteArrayOf(1, 0)) // compression
            write(0); write(extension.size + 4)
            write(byteArrayOf(0, 0)); write(0); write(extension.size); write(extension)
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            write(byteArrayOf(22, 3, 3)); write(0); write(body.size + 4)
            write(byteArrayOf(1, 0, 0)); write(body.size); write(body)
        }.toByteArray()
    }

    @Test fun readsFragmentedHeaderAndBodyWithoutLosingBytes() {
        val expected = hello()
        val input = ByteArrayInputStream(expected)
        val actual = TlsClientHelloParser.readRecord { input.read(it, 0, minOf(3, it.size)) }
        assertArrayEquals(expected, actual)
        assertEquals("media.example.com", TlsClientHelloParser.parseSni(actual, 0, actual.size))
    }

    @Test fun retainsBytesFollowingClientHello() {
        val expected = hello() + byteArrayOf(20, 3, 3, 0, 1, 1)
        val input = ByteArrayInputStream(expected)
        assertArrayEquals(expected, TlsClientHelloParser.readRecord { input.read(it) })
    }

    @Test fun recognizesClientHelloForInspection() {
        val expected = hello()
        val input = ByteArrayInputStream(expected)
        val result = TlsClientHelloParser.readInitialRecord { input.read(it, 0, minOf(3, it.size)) }
        assertNotNull(result)
        assertTrue(result!!.isClientHello)
        assertArrayEquals(expected, result.bytes)
    }

    @Test fun preservesNonTlsPort443BytesForDirectRelay() {
        val expected = "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n".toByteArray()
        val input = ByteArrayInputStream(expected)
        val result = TlsClientHelloParser.readInitialRecord { input.read(it) }
        assertNotNull(result)
        assertFalse(result!!.isClientHello)
        assertArrayEquals(expected, result.bytes)
    }

    @Test fun nonTlsPrefaceDoesNotWaitForFiveBytes() {
        var reads = 0
        val result = TlsClientHelloParser.readInitialRecord { buffer ->
            reads++
            buffer[0] = 'G'.code.toByte()
            1
        }
        assertEquals(1, reads)
        assertNotNull(result)
        assertFalse(result!!.isClientHello)
        assertArrayEquals(byteArrayOf('G'.code.toByte()), result.bytes)
    }

    @Test fun closedIncompleteHelloIsNotCountedAsProxyFailure() {
        val input = ByteArrayInputStream(hello().dropLast(2).toByteArray())
        assertNull(TlsClientHelloParser.readInitialRecord { input.read(it) })
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTruncatedRecordInsteadOfIssuingCertificateForIp() {
        val input = ByteArrayInputStream(hello().dropLast(2).toByteArray())
        TlsClientHelloParser.readRecord { input.read(it) }
    }

    @Test fun absentAlpnStillUsesHttp1Inspector() {
        assertTrue(TlsClientHelloParser.isHttp1(""))
        assertTrue(TlsClientHelloParser.isHttp1("http/1.1"))
        assertFalse(TlsClientHelloParser.isHttp1("h2"))
    }

    @Test fun truncatedClientHelloReturnsNull() {
        val bytes = byteArrayOf(22, 3, 3, 0, 38, 1, 0, 0, 34) + ByteArray(34)
        assertNull(TlsClientHelloParser.parseSni(bytes, 0, bytes.size))
    }
}

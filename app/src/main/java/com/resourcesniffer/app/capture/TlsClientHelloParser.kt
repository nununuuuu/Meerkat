package com.resourcesniffer.app.capture

/** Parses a complete TLS ClientHello record, independent of TCP read boundaries. */
object TlsClientHelloParser {
    /** Preserve every byte read, including bytes following the first record. */
    fun readRecord(read: (ByteArray) -> Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(32 * 1024)
        var required = 5
        while (output.size() < required) {
            val n = read(buffer)
            require(n in 1..buffer.size) { "TLS ClientHello 已中斷" }
            output.write(buffer, 0, n)
            val bytes = output.toByteArray()
            if (bytes.size >= 5) {
                require((bytes[0].toInt() and 0xff) == 22) { "不是 TLS ClientHello" }
                val length = u16(bytes, 3)
                require(length in 1..18432) { "TLS record 長度無效" }
                required = 5 + length
            }
        }
        return output.toByteArray()
    }

    fun isHttp1(protocol: String): Boolean = protocol.isEmpty() || protocol.equals("http/1.1", true)

    fun parseSni(data: ByteArray, offset: Int, length: Int): String? {
        val end = offset + length
        if (length < 9 || end > data.size) return null
        var p = offset
        if ((data[p].toInt() and 0xff) != 22) return null // handshake record
        val recordLen = u16(data, p + 3)
        if (p + 5 + recordLen > end) return null
        p += 5
        if ((data[p].toInt() and 0xff) != 1) return null // ClientHello
        p += 4
        if (p + 34 >= end) return null
        p += 34 // version + random
        val sidLen = data[p].toInt() and 0xff; p += 1 + sidLen
        if (p + 2 > end) return null
        val cipherLen = u16(data, p); p += 2 + cipherLen
        if (p + 1 > end) return null
        val compLen = data[p].toInt() and 0xff; p += 1 + compLen
        if (p + 2 > end) return null
        val extLen = u16(data, p); p += 2
        val extEnd = minOf(end, p + extLen)
        while (p + 4 <= extEnd) {
            val type = u16(data, p); val len = u16(data, p + 2); p += 4
            if (p + len > extEnd) return null
            if (type == 0 && len >= 5) {
                var q = p + 2
                val listEnd = p + len
                while (q + 3 <= listEnd) {
                    val nameType = data[q].toInt() and 0xff
                    val nameLen = u16(data, q + 1); q += 3
                    if (q + nameLen > listEnd) return null
                    if (nameType == 0) return data.copyOfRange(q, q + nameLen).toString(Charsets.US_ASCII)
                    q += nameLen
                }
            }
            p += len
        }
        return null
    }
    private fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xff) shl 8) or (b[o+1].toInt() and 0xff)
}


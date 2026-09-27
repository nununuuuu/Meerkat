package com.resourcesniffer.app.capture

import java.net.InetAddress

/** Minimal DNS parser: extracts A-record answers and queried host names from one UDP packet. */
object DnsParser {
    data class Result(val queryName: String?, val ipv4Answers: List<Pair<String, InetAddress>>)

    fun parse(payload: ByteArray, offset: Int, length: Int): Result? {
        if (length < 12 || offset < 0 || offset + length > payload.size) return null
        val end = offset + length
        val qd = u16(payload, offset + 4)
        val an = u16(payload, offset + 6)
        var p = offset + 12
        var firstQuery: String? = null
        repeat(qd) {
            val (name, next) = readName(payload, p, end, offset) ?: return null
            if (firstQuery == null) firstQuery = name
            p = next
            if (p + 4 > end) return null
            p += 4
        }
        val answers = mutableListOf<Pair<String, InetAddress>>()
        repeat(an) {
            val (name, next) = readName(payload, p, end, offset) ?: return@repeat
            p = next
            if (p + 10 > end) return@repeat
            val type = u16(payload, p); val clazz = u16(payload, p + 2); val rdlen = u16(payload, p + 8)
            p += 10
            if (p + rdlen > end) return@repeat
            if (type == 1 && clazz == 1 && rdlen == 4) {
                answers += name to InetAddress.getByAddress(payload.copyOfRange(p, p + 4))
            }
            p += rdlen
        }
        return Result(firstQuery, answers)
    }

    private fun readName(b: ByteArray, start: Int, end: Int, dnsStart: Int): Pair<String, Int>? {
        var p = start; var jumped = false; var returnPos = -1; var guard = 0
        val labels = mutableListOf<String>()
        while (p < end && guard++ < 64) {
            val len = b[p].toInt() and 0xff
            if (len == 0) {
                p++
                return labels.joinToString(".") to (if (jumped) returnPos else p)
            }
            if ((len and 0xC0) == 0xC0) {
                if (p + 1 >= end) return null
                val ptr = ((len and 0x3f) shl 8) or (b[p + 1].toInt() and 0xff)
                if (!jumped) { returnPos = p + 2; jumped = true }
                p = dnsStart + ptr
                continue
            }
            p++
            if (len == 0 || p + len > end) return null
            labels += b.copyOfRange(p, p + len).toString(Charsets.UTF_8)
            p += len
        }
        return null
    }

    private fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xff) shl 8) or (b[o+1].toInt() and 0xff)
}

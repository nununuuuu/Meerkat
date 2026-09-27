package com.resourcesniffer.app.capture

import java.net.InetAddress

object Ipv4PacketParser {
    fun parse(packet: ByteArray, length: Int): PacketInfo? {
        if (length < 20) return null
        val version = (packet[0].toInt() ushr 4) and 0x0F
        if (version != 4) return null
        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (ihl < 20 || length < ihl + 4) return null
        val protocol = packet[9].toInt() and 0xFF
        if (protocol != 6 && protocol != 17) return null
        val src = InetAddress.getByAddress(packet.copyOfRange(12, 16))
        val dst = InetAddress.getByAddress(packet.copyOfRange(16, 20))
        val srcPort = u16(packet, ihl)
        val dstPort = u16(packet, ihl + 2)
        val payloadOffset = if (protocol == 6) {
            if (length < ihl + 20) return null
            val tcpHeader = ((packet[ihl + 12].toInt() ushr 4) and 0x0F) * 4
            ihl + tcpHeader
        } else ihl + 8
        if (payloadOffset > length) return null
        return PacketInfo(
            4, protocol, src, dst, srcPort, dstPort,
            ihl, payloadOffset, length - payloadOffset
        )
    }

    private fun u16(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xff) shl 8) or (b[o + 1].toInt() and 0xff)
}

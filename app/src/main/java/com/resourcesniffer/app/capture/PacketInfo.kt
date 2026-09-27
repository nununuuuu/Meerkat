package com.resourcesniffer.app.capture

import java.net.InetAddress

data class PacketInfo(
    val ipVersion: Int,
    val protocol: Int,
    val sourceAddress: InetAddress,
    val destinationAddress: InetAddress,
    val sourcePort: Int,
    val destinationPort: Int,
    val transportOffset: Int,
    val payloadOffset: Int,
    val payloadLength: Int
)

package com.resourcesniffer.app.capture

import com.resourcesniffer.app.core.Resource
import com.resourcesniffer.app.core.ResourceClassifier
import com.resourcesniffer.app.repository.SnifferRepository
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Metadata pipeline for raw IPv4 packets. No TLS decryption and no TCP stream reassembly yet. */
class PacketMetadataParser(private val ownerResolver: ConnectionOwnerResolver?) {
    private val ids = AtomicLong(1)
    private val dnsNames = ConcurrentHashMap<String, String>()

    fun inspect(packet: ByteArray, length: Int) {
        val info = Ipv4PacketParser.parse(packet, length) ?: return

        if (info.protocol == 17 && (info.sourcePort == 53 || info.destinationPort == 53)) {
            DnsParser.parse(packet, info.payloadOffset, info.payloadLength)?.let { dns ->
                dns.ipv4Answers.forEach { (name, address) -> dnsNames[address.hostAddress ?: return@forEach] = name }
            }
        }

        val dstIp = info.destinationAddress.hostAddress ?: return
        val tlsSni = if (info.protocol == 6 && info.destinationPort == 443 && info.payloadLength > 0)
            TlsClientHelloParser.parseSni(packet, info.payloadOffset, info.payloadLength)
        else null
        val host = tlsSni ?: dnsNames[dstIp] ?: dstIp
        val scheme = when {
            info.destinationPort == 443 -> "tls"
            info.destinationPort == 80 -> "http"
            info.protocol == 17 -> "udp"
            else -> "tcp"
        }
        val pseudoUrl = "$scheme://$host:${info.destinationPort}"
        val classification = ResourceClassifier.classify(pseudoUrl, null)
        SnifferRepository.add(
            Resource(
                id = ids.getAndIncrement(), sessionId = 1,
                sourceAppPackage = ownerResolver?.packageFor(info), sourceAppName = null,
                url = pseudoUrl, host = host, mimeType = null, extension = null,
                contentLength = null, type = classification.type, streamType = classification.streamType
            )
        )
    }
}

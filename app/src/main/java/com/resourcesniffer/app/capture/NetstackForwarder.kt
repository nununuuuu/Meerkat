package com.resourcesniffer.app.capture

import android.net.VpnService
import java.io.File
import dev.netvalve.bridge.Bridge
import dev.netvalve.bridge.Handler
import dev.netvalve.bridge.TCPConn
import dev.netvalve.bridge.Tunnel
import dev.netvalve.bridge.UDPConn
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Production TUN forwarding layer backed by gVisor netstack.
 *
 * TCP/UDP protocol state is owned by gVisor. This class only opens protected
 * upstream sockets and relays application payload bytes.
 */
class NetstackForwarder(
    private val vpnService: VpnService,
    private val sourcePackage: String?,
    private val sourceName: String?,
    private val localProxyPort: Int? = null,
) {
    private val executor = Executors.newCachedThreadPool()
    private val stopped = AtomicBoolean(false)
    private val openSockets = ConcurrentHashMap.newKeySet<java.io.Closeable>()
    @Volatile private var tunnel: Tunnel? = null

    fun start(tunFd: Int, mtu: Int = 1500) {
        val handler = object : Handler {
            override fun handleTCP(
                srcIp: String,
                srcPort: Long,
                dstIp: String,
                dstPort: Long,
                conn: TCPConn,
            ) {
                executor.execute { relayTcp(dstIp, dstPort.toInt(), conn) }
            }

            override fun handleUDP(
                srcIp: String,
                srcPort: Long,
                dstIp: String,
                dstPort: Long,
                conn: UDPConn,
            ) {
                executor.execute { relayUdp(dstIp, dstPort.toInt(), conn) }
            }

            override fun log(level: Long, msg: String) = Unit
        }
        tunnel = Bridge.newTunnel(tunFd.toLong(), mtu.toLong(), "RELAY", handler)
    }

    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        runCatching { tunnel?.stop() }
        tunnel = null
        openSockets.toList().forEach { runCatching { it.close() } }
        openSockets.clear()
        executor.shutdownNow()
    }

    private fun relayTcp(dstIp: String, dstPort: Int, conn: TCPConn) {
        if (localProxyPort != null && (dstPort == 80 || dstPort == 443)) {
            relayTcpViaProxy(dstIp, dstPort, conn, localProxyPort)
            return
        }
        val socket = Socket()
        openSockets += socket
        val inspector = HttpResourceStreamInspector(
            sourcePackage,
            sourceName,
            responseCacheDir = File(vpnService.filesDir, "captured-responses"),
        )
        try {
            if (!vpnService.protect(socket)) return
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(InetAddress.getByName(dstIp), dstPort), 12_000)

            val upstreamIn = socket.getInputStream()
            val upstreamOut = socket.getOutputStream()
            val closed = AtomicBoolean(false)

            val upload = Thread {
                val buffer = ByteArray(32 * 1024)
                try {
                    while (!stopped.get() && !closed.get()) {
                        val n = try { conn.read(buffer).toInt() } catch (_: Exception) { -1 }
                        if (n <= 0) break
                        inspector.onClientBytes(buffer, n)
                        upstreamOut.write(buffer, 0, n)
                        upstreamOut.flush()
                    }
                } catch (_: Exception) {
                } finally {
                    closed.set(true)
                    runCatching { socket.shutdownOutput() }
                    runCatching { conn.close() }
                }
            }.apply { name = "Meerkat-TCP-Up"; isDaemon = true }

            val download = Thread {
                val buffer = ByteArray(32 * 1024)
                try {
                    while (!stopped.get() && !closed.get()) {
                        val n = upstreamIn.read(buffer)
                        if (n <= 0) break
                        inspector.onServerBytes(buffer, n)
                        conn.write(if (n == buffer.size) buffer else buffer.copyOfRange(0, n))
                    }
                } catch (_: Exception) {
                } finally {
                    closed.set(true)
                    runCatching { socket.shutdownInput() }
                    runCatching { conn.close() }
                }
            }.apply { name = "Meerkat-TCP-Down"; isDaemon = true }

            upload.start()
            download.start()
            upload.join()
            download.join()
        } catch (_: Exception) {
        } finally {
            runCatching { conn.close() }
            runCatching { socket.close() }
            openSockets -= socket
        }
    }

    private fun relayTcpViaProxy(dstIp: String, dstPort: Int, conn: TCPConn, proxyPort: Int) {
        val socket = Socket()
        openSockets += socket
        try {
            val firstBuffer = ByteArray(64 * 1024)
            val firstCount = try { conn.read(firstBuffer).toInt() } catch (_: Exception) { -1 }
            if (firstCount <= 0) return
            val firstBytes = firstBuffer.copyOfRange(0, firstCount)
            val host = if (dstPort == 443) {
                TlsClientHelloParser.parseSni(firstBytes, 0, firstBytes.size) ?: dstIp
            } else dstIp

            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), proxyPort), 5_000)
            val proxyIn = socket.getInputStream()
            val proxyOut = socket.getOutputStream()
            val preface = "MEERKAT\t" + host + "\t" + dstIp + "\t" + dstPort + "\n"
            proxyOut.write(preface.toByteArray(Charsets.US_ASCII))
            proxyOut.write(firstBytes)
            proxyOut.flush()

            val closed = AtomicBoolean(false)
            val upload = Thread {
                val buffer = ByteArray(32 * 1024)
                try {
                    while (!stopped.get() && !closed.get()) {
                        val n = try { conn.read(buffer).toInt() } catch (_: Exception) { -1 }
                        if (n <= 0) break
                        proxyOut.write(buffer, 0, n)
                        proxyOut.flush()
                    }
                } catch (_: Exception) {
                } finally {
                    closed.set(true)
                    runCatching { socket.shutdownOutput() }
                    runCatching { conn.close() }
                }
            }.apply { name = "Meerkat-Proxy-Up"; isDaemon = true }

            val download = Thread {
                val buffer = ByteArray(32 * 1024)
                try {
                    while (!stopped.get() && !closed.get()) {
                        val n = proxyIn.read(buffer)
                        if (n <= 0) break
                        conn.write(if (n == buffer.size) buffer else buffer.copyOfRange(0, n))
                    }
                } catch (_: Exception) {
                } finally {
                    closed.set(true)
                    runCatching { socket.shutdownInput() }
                    runCatching { conn.close() }
                }
            }.apply { name = "Meerkat-Proxy-Down"; isDaemon = true }

            upload.start()
            download.start()
            upload.join()
            download.join()
        } catch (_: Exception) {
        } finally {
            runCatching { conn.close() }
            runCatching { socket.close() }
            openSockets -= socket
        }
    }
    private fun relayUdp(dstIp: String, dstPort: Int, conn: UDPConn) {
        // Deep capture cannot inspect QUIC/HTTP3 payloads. Dropping UDP/443
        // makes normal clients retry over TCP/TLS where the local proxy can
        // negotiate HTTP/1.1 and inspect resource responses.
        if (localProxyPort != null && dstPort == 443) {
            runCatching { conn.close() }
            return
        }
        val socket = DatagramSocket(null)
        openSockets += socket
        try {
            if (!vpnService.protect(socket)) return
            socket.reuseAddress = true
            socket.soTimeout = 60_000
            socket.connect(InetSocketAddress(InetAddress.getByName(dstIp), dstPort))

            val closed = AtomicBoolean(false)
            val upload = Thread {
                try {
                    while (!stopped.get() && !closed.get()) {
                        val data = try { conn.receive() } catch (_: Exception) { null } ?: break
                        socket.send(DatagramPacket(data, data.size))
                    }
                } catch (_: Exception) {
                } finally {
                    closed.set(true)
                    runCatching { conn.close() }
                }
            }.apply { name = "Meerkat-UDP-Up"; isDaemon = true }

            val download = Thread {
                val buffer = ByteArray(65_535)
                try {
                    while (!stopped.get() && !closed.get()) {
                        val packet = DatagramPacket(buffer, buffer.size)
                        try {
                            socket.receive(packet)
                        } catch (_: SocketTimeoutException) {
                            break
                        }
                        conn.send(packet.data.copyOfRange(packet.offset, packet.offset + packet.length))
                    }
                } catch (_: Exception) {
                } finally {
                    closed.set(true)
                    runCatching { conn.close() }
                }
            }.apply { name = "Meerkat-UDP-Down"; isDaemon = true }

            upload.start()
            download.start()
            upload.join()
            download.join()
        } catch (_: Exception) {
        } finally {
            runCatching { conn.close() }
            runCatching { socket.close() }
            openSockets -= socket
        }
    }
}

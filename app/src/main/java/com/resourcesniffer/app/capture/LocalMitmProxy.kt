package com.resourcesniffer.app.capture

import android.net.VpnService
import android.util.Log
import java.io.File
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket

class LocalMitmProxy(
    private val vpnService: VpnService,
    private val sourcePackage: String?,
    private val sourceName: String?,
    private val ca: MitmCertificateAuthority,
) {
    private val executor = Executors.newCachedThreadPool()
    private val stopped = AtomicBoolean(false)
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val bypassHosts = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var server: ServerSocket? = null

    fun start(): Int {
        if (server != null) return server!!.localPort
        val socket = ServerSocket(0, 64, InetAddress.getLoopbackAddress())
        server = socket
        executor.execute {
            while (!stopped.get()) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                sockets += client
                executor.execute { handle(client) }
            }
        }
        return socket.localPort
    }

    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        runCatching { server?.close() }
        server = null
        sockets.toList().forEach { runCatching { it.close() } }
        sockets.clear()
        executor.shutdownNow()
    }

    private fun handle(client: Socket) {
        try {
            client.tcpNoDelay = true
            val rawInput = client.getInputStream()
            val preface = readLine(rawInput, 1024) ?: return
            val parts = preface.split('\t')
            if (parts.size != 4 || parts[0] != "MEERKAT") return
            val host = parts[1]
            val dstIp = parts[2]
            val port = parts[3].toIntOrNull() ?: return
            if (port == 443) handleTls(client, host, dstIp, port)
            else handlePlain(client, BufferedInputStream(rawInput), dstIp, port)
        } catch (error: Throwable) {
            CaptureStatus.failure(if (error is ClientHandshakeFailure) error.message else
                "本機代理：${if (error is IllegalStateException) error.message else error.javaClass.simpleName}")
            Log.w("MeerkatProxy", "Proxy connection failed", error)
        } finally {
            sockets -= client
            runCatching { client.close() }
        }
    }

    private fun handlePlain(client: Socket, clientInput: BufferedInputStream, dstIp: String, port: Int) {
        val upstream = Socket()
        sockets += upstream
        try {
            prepareUpstreamSocket(upstream, vpnService::protect)
            upstream.tcpNoDelay = true
            upstream.connect(InetSocketAddress(dstIp, port), 12_000)
            val inspector = HttpResourceStreamInspector(
                sourcePackage,
                sourceName,
                secure = false,
                responseCacheDir = File(vpnService.filesDir, "captured-responses"),
            )
            relay(
                clientInput, BufferedOutputStream(client.getOutputStream()),
                BufferedInputStream(upstream.getInputStream()), BufferedOutputStream(upstream.getOutputStream()),
                inspector,
            )
        } finally {
            sockets -= upstream
            runCatching { upstream.close() }
        }
    }

    private fun handleTls(client: Socket, host: String, dstIp: String, port: Int) {
        CaptureStatus.proxyAccepted()
        if (host.lowercase() in bypassHosts) {
            CaptureStatus.opaqueTls()
            relayRawTls(client, dstIp, port)
            return
        }

        val serverContext = ca.serverContext(host)
        val clientTls = serverContext.socketFactory.createSocket(
            client, client.inetAddress.hostAddress, client.port, false,
        ) as SSLSocket
        clientTls.useClientMode = false
        clientTls.sslParameters = clientTls.sslParameters.apply {
            applicationProtocols = arrayOf("http/1.1")
        }

        val upstreamBase = Socket()
        sockets += upstreamBase
        try {
            prepareUpstreamSocket(upstreamBase, vpnService::protect)
            upstreamBase.tcpNoDelay = true
            upstreamBase.connect(InetSocketAddress(dstIp, port), 12_000)
            CaptureStatus.proxyUpstreamConnected()
            val upstreamTls = SSLContext.getDefault().socketFactory.createSocket(
                upstreamBase, host, port, false,
            ) as SSLSocket
            upstreamTls.useClientMode = true
            upstreamTls.sslParameters = upstreamTls.sslParameters.apply {
                endpointIdentificationAlgorithm = "HTTPS"
                applicationProtocols = arrayOf("http/1.1")
                if (!isIpAddress(host)) serverNames = listOf(SNIHostName(host))
            }

            clientTls.soTimeout = 15_000
            upstreamTls.soTimeout = 15_000
            try {
                clientTls.startHandshake()
            } catch (error: SSLHandshakeException) {
                CaptureStatus.opaqueTls()
                bypassHosts += host.lowercase()
                throw ClientHandshakeFailure(describeClientHandshake(error.message.orEmpty()), error)
            }
            CaptureStatus.proxyClientTlsCompleted()
            upstreamTls.startHandshake()
            clientTls.soTimeout = 0
            upstreamTls.soTimeout = 0
            val clientProtocol = clientTls.applicationProtocol.orEmpty()
            val upstreamProtocol = upstreamTls.applicationProtocol.orEmpty()
            val inspector = if (
                TlsClientHelloParser.isHttp1(clientProtocol) &&
                TlsClientHelloParser.isHttp1(upstreamProtocol)
            ) {
                HttpResourceStreamInspector(
                    sourcePackage,
                    sourceName,
                    secure = true,
                    responseCacheDir = File(vpnService.filesDir, "captured-responses"),
                )
            } else {
                null
            }
            if (inspector != null) CaptureStatus.decrypted()
            relay(
                BufferedInputStream(clientTls.inputStream), BufferedOutputStream(clientTls.outputStream),
                BufferedInputStream(upstreamTls.inputStream), BufferedOutputStream(upstreamTls.outputStream),
                inspector,
            )
            runCatching { upstreamTls.close() }
            runCatching { clientTls.close() }
        } finally {
            sockets -= upstreamBase
            runCatching { upstreamBase.close() }
        }
    }

    private fun relayRawTls(client: Socket, dstIp: String, port: Int) {
        val upstream = Socket()
        sockets += upstream
        try {
            prepareUpstreamSocket(upstream, vpnService::protect)
            upstream.tcpNoDelay = true
            upstream.connect(InetSocketAddress(dstIp, port), 12_000)
            val clientIn = BufferedInputStream(client.getInputStream())
            val clientOut = BufferedOutputStream(client.getOutputStream())
            val upstreamIn = BufferedInputStream(upstream.getInputStream())
            val upstreamOut = BufferedOutputStream(upstream.getOutputStream())
            val closed = AtomicBoolean(false)

            val up = Thread {
                val buffer = ByteArray(32 * 1024)
                try {
                    while (!stopped.get() && !closed.get()) {
                        val n = clientIn.read(buffer)
                        if (n <= 0) break
                        upstreamOut.write(buffer, 0, n)
                        upstreamOut.flush()
                    }
                } catch (_: Throwable) {
                } finally { closed.set(true) }
            }.apply { isDaemon = true; name = "Meerkat-TLS-Bypass-Up" }

            val down = Thread {
                val buffer = ByteArray(32 * 1024)
                try {
                    while (!stopped.get() && !closed.get()) {
                        val n = upstreamIn.read(buffer)
                        if (n <= 0) break
                        clientOut.write(buffer, 0, n)
                        clientOut.flush()
                    }
                } catch (_: Throwable) {
                } finally { closed.set(true) }
            }.apply { isDaemon = true; name = "Meerkat-TLS-Bypass-Down" }

            up.start()
            down.start()
            up.join()
            down.join()
        } finally {
            sockets -= upstream
            runCatching { upstream.close() }
        }
    }
    private fun relay(
        clientIn: BufferedInputStream, clientOut: BufferedOutputStream,
        upstreamIn: BufferedInputStream, upstreamOut: BufferedOutputStream,
        inspector: HttpResourceStreamInspector?,
    ) {
        val closed = AtomicBoolean(false)
        val inspectionFailed = AtomicBoolean(false)
        val up = Thread {
            val buffer = ByteArray(32 * 1024)
            try {
                while (!stopped.get() && !closed.get()) {
                    val n = clientIn.read(buffer)
                    if (n <= 0) break
                    if (!inspectionFailed.get()) {
                        runCatching { inspector?.onClientBytes(buffer, n) }.onFailure {
                            inspectionFailed.set(true)
                            CaptureStatus.failure()
                        }
                    }
                    upstreamOut.write(buffer, 0, n)
                    upstreamOut.flush()
                }
            } catch (_: Throwable) {
            } finally {
                closed.set(true)
            }
        }.apply { isDaemon = true; name = "Meerkat-MITM-Up" }

        val down = Thread {
            val buffer = ByteArray(32 * 1024)
            try {
                while (!stopped.get() && !closed.get()) {
                    val n = upstreamIn.read(buffer)
                    if (n <= 0) break
                    if (!inspectionFailed.get()) {
                        runCatching { inspector?.onServerBytes(buffer, n) }.onFailure {
                            inspectionFailed.set(true)
                            CaptureStatus.failure()
                        }
                    }
                    clientOut.write(buffer, 0, n)
                    clientOut.flush()
                }
            } catch (_: Throwable) {
            } finally {
                closed.set(true)
            }
        }.apply { isDaemon = true; name = "Meerkat-MITM-Down" }

        up.start()
        down.start()
        up.join()
        down.join()
    }

    private fun readLine(input: InputStream, maxBytes: Int): String? {
        val out = ArrayList<Byte>()
        repeat(maxBytes) {
            val value = input.read()
            if (value < 0) return null
            if (value == '\n'.code) return out.toByteArray().toString(Charsets.US_ASCII).trimEnd('\r')
            out += value.toByte()
        }
        return null
    }

    private fun isIpAddress(host: String): Boolean = runCatching {
        val address = InetAddress.getByName(host)
        host == address.hostAddress || host.contains(":")
    }.getOrDefault(false)
}

private class ClientHandshakeFailure(message: String, cause: Throwable) : java.io.IOException(message, cause)

internal fun describeClientHandshake(message: String): String {
    val lower = message.lowercase()
    return when {
        "unknown ca" in lower || "certificate unknown" in lower || "bad certificate" in lower || "certificate_unknown" in lower ->
            "裝置 TLS：App 拒絕代理憑證（憑證鏈、信任設定或憑證釘選）"
        "protocol version" in lower || "no shared cipher" in lower || "no application protocol" in lower ->
            "裝置 TLS：協定、加密套件或 ALPN 不相容"
        else -> "裝置 TLS：握手失敗（SSLHandshakeException），尚未讀取 HTTPS 請求"
    }
}

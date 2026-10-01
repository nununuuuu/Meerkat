package com.resourcesniffer.app.capture

import java.net.InetSocketAddress
import java.net.Socket

/** Binding creates the native descriptor before Android's VpnService.protect inspects it. */
internal fun prepareUpstreamSocket(socket: Socket, protect: (Socket) -> Boolean) {
    if (!socket.isBound) socket.bind(InetSocketAddress(0))
    check(protect(socket)) { "VPN 無法保護上游連線" }
}

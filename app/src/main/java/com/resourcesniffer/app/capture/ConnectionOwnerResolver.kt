package com.resourcesniffer.app.capture

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import android.system.OsConstants
import java.net.InetSocketAddress

class ConnectionOwnerResolver(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val pm = context.packageManager

    fun packageFor(packet: PacketInfo): String? {
        if (Build.VERSION.SDK_INT < 29) return null
        val proto = when (packet.protocol) { 6 -> OsConstants.IPPROTO_TCP; 17 -> OsConstants.IPPROTO_UDP; else -> return null }
        val uid = runCatching {
            cm.getConnectionOwnerUid(
                proto,
                InetSocketAddress(packet.sourceAddress, packet.sourcePort),
                InetSocketAddress(packet.destinationAddress, packet.destinationPort)
            )
        }.getOrDefault(Process.INVALID_UID)
        if (uid == Process.INVALID_UID) return null
        return pm.getPackagesForUid(uid)?.firstOrNull()
    }
}

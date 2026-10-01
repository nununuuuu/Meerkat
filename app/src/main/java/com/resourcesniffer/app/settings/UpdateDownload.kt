package com.resourcesniffer.app.settings

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

internal fun copyUpdatePayload(input: InputStream, output: OutputStream, size: Long,
    onProgress: (Long, Long) -> Unit): String {
    onProgress(0, size)
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        if (Thread.currentThread().isInterrupted) error("下載已取消")
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        check(total <= 256L * 1024 * 1024) { "更新檔案過大" }
        digest.update(buffer, 0, count)
        output.write(buffer, 0, count)
        onProgress(total, size)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

package com.resourcesniffer.app.capture

internal fun tlsCauseChain(error: Throwable): String {
    val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
    val lines = mutableListOf<String>()
    var cause: Throwable? = error
    while (cause != null && lines.size < 6 && seen.add(cause)) {
        val current = cause
        lines += current.javaClass.simpleName + ": " + current.message.orEmpty().replace('\n', ' ').replace('\r', ' ').take(500)
        cause = current.cause
    }
    return lines.joinToString("\n")
}

internal fun describeClientHandshake(message: String): String {
    val lower = message.lowercase().replace('_', ' ')
    return when {
        "certificate expired" in lower -> "裝置 TLS：憑證有效期遭拒絕，請檢查裝置時間與憑證日期"
        "unknown ca" in lower || "certificate unknown" in lower || "bad certificate" in lower ->
            "裝置 TLS：App 拒絕代理憑證（信任設定、憑證鏈或憑證釘選）"
        "protocol version" in lower || "no shared cipher" in lower || "no application protocol" in lower ->
            "裝置 TLS：協定、加密套件或 ALPN 不相容"
        else -> "裝置 TLS：握手未完成，尚未讀取 HTTPS 請求；請查看診斷明細"
    }
}

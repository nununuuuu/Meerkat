package com.resourcesniffer.app.settings

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal fun encryptAccounts(json: String, key: SecretKey): String {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, key)
    val data = cipher.doFinal(json.toByteArray(Charsets.UTF_8))
    return Base64.getEncoder().encodeToString(cipher.iv) + ":" + Base64.getEncoder().encodeToString(data)
}

internal fun decryptAccounts(encoded: String, key: SecretKey): String {
    val parts = encoded.split(':')
    require(parts.size == 2)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.getDecoder().decode(parts[0])))
    return String(cipher.doFinal(Base64.getDecoder().decode(parts[1])), Charsets.UTF_8)
}

package com.resourcesniffer.app.settings

import org.junit.Assert.*
import org.junit.Test
import javax.crypto.KeyGenerator

class VaultCipherTest {
    private fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    @Test fun roundTripUsesFreshIvAndNeverStoresPlaintext() {
        val key = key()
        val json = "帳號：alice；密碼：p@ss\\word\"🙂"
        val first = encryptAccounts(json, key)
        val second = encryptAccounts(json, key)
        assertNotEquals(first, second)
        assertFalse(first.contains("alice"))
        assertEquals(json, decryptAccounts(first, key))
    }
    @Test fun rejectsWrongKeyAndTampering() {
        val key = key()
        val encoded = encryptAccounts("secret", key)
        assertTrue(runCatching { decryptAccounts(encoded, key()) }.isFailure)
        val parts = encoded.split(':')
        val bytes = java.util.Base64.getDecoder().decode(parts[1])
        bytes[0] = (bytes[0].toInt() xor 1).toByte()
        val tampered = parts[0] + ":" + java.util.Base64.getEncoder().encodeToString(bytes)
        assertTrue(runCatching { decryptAccounts(tampered, key) }.isFailure)
    }
}

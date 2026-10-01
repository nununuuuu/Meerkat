package com.resourcesniffer.app.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Credentials never leave app-private storage unencrypted. Backups are disabled. */
internal class AccountVault(context: Context) {
    private val prefs = context.getSharedPreferences("website_accounts", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("meerkat.accounts.v1", null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("meerkat.accounts.v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun load(): List<WebsiteAccount> {
        val encrypted = prefs.getString("vault", null)
        val json = if (encrypted != null) decryptAccounts(encrypted, key())
            else prefs.getString("entries", "[]") ?: "[]"
        val array = JSONArray(json)
        return List(array.length()) { index -> array.getJSONObject(index).let {
            WebsiteAccount(it.getString("id"), it.getString("name"), it.getString("url"), it.optString("username"), it.optString("password"))
        } }
    }
    fun save(accounts: List<WebsiteAccount>) {
        val json = JSONArray().apply { accounts.forEach { a -> put(JSONObject()
            .put("id", a.id).put("name", a.name).put("url", a.url).put("username", a.username).put("password", a.password)) } }.toString()
        val encoded = encryptAccounts(json, key())
        check(prefs.edit().putString("vault", encoded).remove("entries").commit()) { "無法儲存帳號，請檢查裝置空間" }
    }
}

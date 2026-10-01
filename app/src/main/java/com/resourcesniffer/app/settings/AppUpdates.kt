package com.resourcesniffer.app.settings

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.resourcesniffer.app.BuildConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.json.JSONArray

internal data class AppRelease(val name: String, val build: Int, val url: String, val digest: String, val size: Long = 0)

internal object AppUpdates {
    private fun connection(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        require(URL(url).protocol == "https")
        connectTimeout = 15_000
        readTimeout = 30_000
        setRequestProperty("User-Agent", "Meerkat/${BuildConfig.VERSION_NAME}")
    }

    fun latest(): AppRelease {
        val conn = connection("https://api.github.com/repos/nununuuuu/Meerkat/releases?per_page=30")
        try {
            check(conn.responseCode == 200) { "無法檢查更新：HTTP ${conn.responseCode}" }
            val releases = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
            return (0 until releases.length()).mapNotNull { index ->
                val release = releases.getJSONObject(index)
                if (release.optBoolean("draft")) return@mapNotNull null
                val build = Regex("-build(\\d+)").find(release.getString("tag_name"))?.groupValues?.get(1)?.toIntOrNull()
                    ?: return@mapNotNull null
                val assets = release.getJSONArray("assets")
                val apk = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.getString("name").endsWith(".apk") }
                    ?: return@mapNotNull null
                val digest = apk.optString("digest").removePrefix("sha256:")
                if (!digest.matches(Regex("[a-fA-F0-9]{64}"))) return@mapNotNull null
                AppRelease(release.optString("name"), build, apk.getString("browser_download_url"), digest, apk.optLong("size"))
            }.maxByOrNull { it.build } ?: error("尚未找到可更新的版本")
        } finally { conn.disconnect() }
    }

    @Suppress("DEPRECATION")
    fun download(context: Context, release: AppRelease, onProgress: (Long, Long) -> Unit = { _, _ -> }): File {
        require(release.url.startsWith("https://github.com/nununuuuu/Meerkat/releases/download/"))
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val part = File(dir, "update.pending.apk")
        val apk = File(dir, "update.apk")
        val conn = connection(release.url)
        try {
            check(conn.responseCode == 200) { "下載失敗：HTTP ${conn.responseCode}" }
            val length = conn.contentLengthLong.takeIf { it > 0 } ?: release.size
            val hash = conn.inputStream.use { input -> part.outputStream().use { output ->
                copyUpdatePayload(input, output, length, onProgress)
            } }
            check(hash.equals(release.digest, true)) { "更新檔案驗證失敗，請重新下載" }
            val pm = context.packageManager
            val candidate = pm.getPackageArchiveInfo(part.path, PackageManager.GET_SIGNING_CERTIFICATES)
                ?: error("不是有效的 APK")
            val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            check(candidate.packageName == context.packageName && candidate.longVersionCode > installed.longVersionCode) { "更新版本不適用" }
            fun signers(info: android.content.pm.PackageInfo) = info.signingInfo?.apkContentsSigners
                ?.map { signature -> MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).toList() }?.toSet()
            val expected = signers(installed)
            check(!expected.isNullOrEmpty() && signers(candidate) == expected) { "更新簽章與目前 App 不符" }
            check(part.renameTo(apk)) { "無法儲存更新檔案" }
            return apk
        } finally { conn.disconnect(); part.delete() }
    }

    fun install(context: Context, apk: File): Boolean {
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        return true
    }
}

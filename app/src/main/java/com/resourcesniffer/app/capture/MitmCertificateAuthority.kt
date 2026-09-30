package com.resourcesniffer.app.capture

import android.content.Context
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.net.InetAddress
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

class MitmCertificateAuthority(private val context: Context) {
    private val dir = File(context.noBackupFilesDir, "mitm-ca").apply { mkdirs() }
    private val certFile = File(dir, "meerkat-ca.crt")
    private val keyFile = File(dir, "meerkat-ca.pk8")
    private val secureRandom = SecureRandom()
    private val leafContexts = ConcurrentHashMap<String, SSLContext>()

    @Synchronized
    fun ensureCa(): X509Certificate {
        loadCa()?.let { return it.second }
        val keyPair = generateRsaKeyPair()
        val now = System.currentTimeMillis()
        val subject = X500Name("CN=Meerkat Local CA,O=Meerkat")
        val builder = JcaX509v3CertificateBuilder(
            subject,
            randomSerial(),
            Date(now - 24L * 60 * 60 * 1000),
            Date(now + 10L * 365 * 24 * 60 * 60 * 1000),
            subject,
            keyPair.public,
        )
        val ext = JcaX509ExtensionUtils()
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
        builder.addExtension(
            Extension.keyUsage,
            true,
            KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign or KeyUsage.digitalSignature),
        )
        builder.addExtension(Extension.subjectKeyIdentifier, false, ext.createSubjectKeyIdentifier(keyPair.public))
        builder.addExtension(Extension.authorityKeyIdentifier, false, ext.createAuthorityKeyIdentifier(keyPair.public))
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        val certificate = JcaX509CertificateConverter().getCertificate(builder.build(signer))
        certificate.verify(keyPair.public)
        keyFile.writeBytes(keyPair.private.encoded)
        certFile.writeBytes(certificate.encoded)
        return certificate
    }

    fun exportToDownloads(): Uri {
        val certificate = ensureCa()
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "Meerkat-Local-CA.crt")
            put(MediaStore.Downloads.MIME_TYPE, "application/x-x509-ca-cert")
            put(
                MediaStore.Downloads.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/Meerkat",
            )
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("無法建立 CA 憑證檔案")
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                output.write(certificate.encoded)
                output.flush()
            } ?: error("無法寫入 CA 憑證檔案")
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null,
                null,
            )
            return uri
        } catch (error: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }

    fun isInstalledInAndroidCaStore(): Boolean {
        val own = runCatching { ensureCa().encoded }.getOrNull() ?: return false
        val ownHash = sha256(own)
        // Some devices fail on individual aliases; one unreadable entry must not
        // hide a matching certificate elsewhere in the store.
        val storeMatch = runCatching {
            val store = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
            val aliases = store.aliases()
            while (aliases.hasMoreElements()) {
                val alias = aliases.nextElement()
                val cert = runCatching { store.getCertificate(alias) as? X509Certificate }.getOrNull()
                    ?: continue
                if (MessageDigest.isEqual(ownHash, sha256(cert.encoded))) return@runCatching true
            }
            false
        }.getOrDefault(false)
        if (storeMatch) return true
        return runCatching {
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(null as KeyStore?)
            factory.trustManagers.filterIsInstance<X509TrustManager>().any { manager ->
                manager.acceptedIssuers.any { MessageDigest.isEqual(ownHash, sha256(it.encoded)) }
            }
        }.getOrDefault(false)
    }

    fun isManuallyConfirmed(): Boolean =
        context.getSharedPreferences("mitm-settings", Context.MODE_PRIVATE)
            .getString("confirmed-ca", null) == fingerprintSha256()

    fun confirmInstalled() {
        context.getSharedPreferences("mitm-settings", Context.MODE_PRIVATE).edit()
            .putString("confirmed-ca", fingerprintSha256()).apply()
    }

    fun fingerprintSha256(): String = sha256(ensureCa().encoded)
        .joinToString(":") { byte -> "%02X".format(byte) }

    fun serverContext(host: String): SSLContext = leafContexts.getOrPut(host.lowercase()) {
        val ca = loadCa() ?: run { ensureCa(); loadCa() } ?: error("CA unavailable")
        val leaf = createLeaf(host, ca.first, ca.second)
        val password = "meerkat".toCharArray()
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setKeyEntry("leaf", leaf.first, password, arrayOf(leaf.second, ca.second))
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, password)
        }
        SSLContext.getInstance("TLS").apply {
            init(kmf.keyManagers, null, secureRandom)
        }
    }

    private fun loadCa(): Pair<PrivateKey, X509Certificate>? {
        if (!keyFile.isFile || !certFile.isFile) return null
        return runCatching {
            val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
            val cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(certFile.inputStream()) as X509Certificate
            key to cert
        }.getOrNull()
    }

    private fun createLeaf(
        host: String,
        caKey: PrivateKey,
        caCert: X509Certificate,
    ): Pair<PrivateKey, X509Certificate> {
        val keyPair = generateRsaKeyPair()
        val now = System.currentTimeMillis()
        val issuer = X500Name(caCert.subjectX500Principal.name)
        val subject = X500Name("CN=" + host)
        val builder = JcaX509v3CertificateBuilder(
            issuer,
            randomSerial(),
            Date(now - 60L * 60 * 1000),
            Date(now + 30L * 24 * 60 * 60 * 1000),
            subject,
            keyPair.public,
        )
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        builder.addExtension(
            Extension.keyUsage,
            true,
            KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment),
        )
        val san = if (isIpAddress(host)) GeneralName(GeneralName.iPAddress, host)
        else GeneralName(GeneralName.dNSName, host)
        builder.addExtension(Extension.subjectAlternativeName, false, GeneralNames(san))
        val ext = JcaX509ExtensionUtils()
        builder.addExtension(Extension.subjectKeyIdentifier, false, ext.createSubjectKeyIdentifier(keyPair.public))
        builder.addExtension(Extension.authorityKeyIdentifier, false, ext.createAuthorityKeyIdentifier(caCert))
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(caKey)
        val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))
        cert.verify(caCert.publicKey)
        return keyPair.private to cert
    }

    private fun generateRsaKeyPair(): KeyPair = KeyPairGenerator.getInstance("RSA").run {
        initialize(2048, secureRandom)
        generateKeyPair()
    }

    private fun randomSerial(): BigInteger = BigInteger(160, secureRandom).abs().max(BigInteger.ONE)

    private fun isIpAddress(host: String): Boolean = runCatching {
        val address = InetAddress.getByName(host)
        host == address.hostAddress || host.contains(":")
    }.getOrDefault(false)

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}


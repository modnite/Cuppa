package com.cuppa.app.server

import android.content.Context
import com.cuppa.app.util.CuppaLog
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * Generates and persists a self-signed X.509 certificate + RSA keypair for Cuppa's own hosted
 * IPP listener, so it can offer IPPS (IPP-over-TLS) to other devices printing to Cuppa.
 *
 * This mirrors how real IPP Everywhere / AirPrint printers work: the printer's TLS identity is
 * a self-signed cert with no trusted CA chain, and clients (CUPS, AirPrint, Windows) accept it
 * without validation since there's no central authority for LAN print servers. The keypair is
 * generated once and persisted so it stays stable across restarts.
 */
object TlsCertificateManager {
    private const val TAG = "TlsCertificateManager"
    private const val KEYSTORE_FILE = "cuppa_tls_keystore.p12"
    private const val KEY_ALIAS = "cuppa-ipps"
    private val KEYSTORE_PASSWORD = "cuppa-local".toCharArray()

    init {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())
        }
    }

    /**
     * Builds an SSLContext backed by the persisted (or freshly generated) self-signed cert.
     * Safe to call repeatedly — generation only happens once per app install.
     */
    @Synchronized
    fun getSslContext(context: Context): SSLContext? {
        return try {
            val keyStore = loadOrCreateKeyStore(context)
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(keyStore, KEYSTORE_PASSWORD)

            val sslContext = SSLContext.getInstance("TLSv1.2")
            sslContext.init(kmf.keyManagers, null, null)
            sslContext
        } catch (e: Exception) {
            CuppaLog.e(TAG, "Failed to build SSLContext for local IPPS listener", e)
            null
        }
    }

    private fun loadOrCreateKeyStore(context: Context): KeyStore {
        val file = File(context.filesDir, KEYSTORE_FILE)
        val keyStore = KeyStore.getInstance("PKCS12")

        if (file.exists()) {
            try {
                file.inputStream().use { keyStore.load(it, KEYSTORE_PASSWORD) }
                if (keyStore.containsAlias(KEY_ALIAS)) {
                    CuppaLog.i(TAG, "Loaded existing self-signed TLS identity from $KEYSTORE_FILE")
                    return keyStore
                }
            } catch (e: Exception) {
                CuppaLog.w(TAG, "Existing keystore unreadable, regenerating: ${e.message}")
            }
        }

        CuppaLog.i(TAG, "Generating new self-signed TLS identity for local IPPS listener")
        keyStore.load(null, null)

        val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
        keyPairGenerator.initialize(2048)
        val keyPair = keyPairGenerator.generateKeyPair()

        val now = Date()
        val notAfter = Date(now.time + TimeUnit.DAYS.toMillis(3650)) // 10 years
        val subject = X500Name("CN=Cuppa Print Server, O=Cuppa")
        val serial = BigInteger.valueOf(System.currentTimeMillis())

        val certBuilder: X509v3CertificateBuilder = JcaX509v3CertificateBuilder(
            subject, serial, now, notAfter, subject, keyPair.public
        )
        // Subject Alternative Names so clients that do check SANs (some Windows IPP clients do)
        // see this cert as valid for "any host" on a LAN rather than mismatching the CN.
        val altNames = GeneralNames(
            arrayOf(
                GeneralName(GeneralName.dNSName, "cuppa.local"),
                GeneralName(GeneralName.iPAddress, "0.0.0.0")
            )
        )
        certBuilder.addExtension(org.bouncycastle.asn1.x509.Extension.subjectAlternativeName, false, altNames)

        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        val certHolder = certBuilder.build(signer)
        val cert: X509Certificate = JcaX509CertificateConverter().getCertificate(certHolder)

        keyStore.setKeyEntry(KEY_ALIAS, keyPair.private, KEYSTORE_PASSWORD, arrayOf(cert))
        file.outputStream().use { keyStore.store(it, KEYSTORE_PASSWORD) }
        CuppaLog.i(TAG, "Self-signed TLS identity generated and persisted to $KEYSTORE_FILE")

        return keyStore
    }
}

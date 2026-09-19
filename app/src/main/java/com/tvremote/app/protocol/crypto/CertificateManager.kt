package com.tvremote.app.protocol.crypto

import android.content.Context
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Security
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

/**
 * The Android TV Remote Service protocol authenticates the client not with a
 * username/password but with a self-signed TLS client certificate: the TV
 * shows a one-time code the first time it sees a new certificate, and after
 * that trusts the same certificate on every future connection. This class
 * creates that certificate once and persists it, exactly like the official
 * Google TV app or Google Home app do internally.
 */
class CertificateManager(context: Context) {

    private val certFile = File(context.filesDir, "tv_remote_client.crt")
    private val keyFile = File(context.filesDir, "tv_remote_client.key")

    init {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    private val identity: Pair<X509Certificate, PrivateKey> by lazy { loadOrCreate() }

    val certificate: X509Certificate get() = identity.first
    val privateKey: PrivateKey get() = identity.second

    private fun loadOrCreate(): Pair<X509Certificate, PrivateKey> {
        if (certFile.exists() && keyFile.exists()) {
            try {
                val cf = CertificateFactory.getInstance("X.509")
                val cert = certFile.inputStream().use { cf.generateCertificate(it) } as X509Certificate
                val keyBytes = keyFile.readBytes()
                val key = KeyFactory.getInstance("RSA")
                    .generatePrivate(PKCS8EncodedKeySpec(keyBytes))
                return cert to key
            } catch (t: Throwable) {
                // Corrupt/unreadable files — fall through and regenerate.
            }
        }
        return generateAndPersist()
    }

    private fun generateAndPersist(): Pair<X509Certificate, PrivateKey> {
        val keyPairGen = KeyPairGenerator.getInstance("RSA")
        keyPairGen.initialize(2048)
        val keyPair = keyPairGen.generateKeyPair()

        val now = Date()
        val notAfter = Date(now.time + 100L * 365 * 24 * 60 * 60 * 1000) // ~100 years
        val subject = X500Name("CN=TvRemoteApp")
        val serial = BigInteger.valueOf(System.currentTimeMillis())

        val certBuilder = X509v3CertificateBuilder(
            subject,
            serial,
            now,
            notAfter,
            subject,
            SubjectPublicKeyInfo.getInstance(keyPair.public.encoded)
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        val holder = certBuilder.build(signer)
        val cert = JcaX509CertificateConverter().getCertificate(holder)

        certFile.writeBytes(cert.encoded)
        keyFile.writeBytes(keyPair.private.encoded)

        return cert to keyPair.private
    }
}

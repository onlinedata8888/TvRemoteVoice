package com.tvremote.app.protocol.crypto

import java.math.BigInteger
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Builds the mutual-TLS context both the pairing (6467) and remote (6466)
 * sockets use. Real trust here isn't established via a CA chain — it's
 * established by the pairing-code exchange (see [computePairingSecret]) —
 * so, exactly like every open-source implementation of this protocol,
 * the trust manager accepts any certificate the TV presents.
 */
object TlsSupport {

    fun buildSslContext(certManager: CertificateManager): SSLContext {
        val passwordChars = CLIENT_KEY_PASSWORD.toCharArray()
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry(
                "client",
                certManager.privateKey,
                passwordChars,
                arrayOf(certManager.certificate)
            )
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, passwordChars)
        }

        val trustAllManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }

        return SSLContext.getInstance("TLS").apply {
            init(kmf.keyManagers, arrayOf(trustAllManager), null)
        }
    }

    /** The TV's certificate from an established TLS session, needed for the pairing-secret hash. */
    fun peerCertificate(socket: SSLSocket): X509Certificate =
        socket.session.peerCertificates[0] as X509Certificate

    /**
     * Reproduces the exact secret-derivation algorithm used by every known
     * open-source implementation of this protocol (louis49/androidtv-remote,
     * tronikos/androidtvremote2): SHA-256 over the client's and server's RSA
     * public key components plus all but the first byte of the code shown
     * on the TV screen. The code's first byte must equal the hash's first
     * byte — that's how the app confirms the user typed the right code
     * before it ever sends anything back to the TV.
     */
    fun computePairingSecret(
        clientCert: X509Certificate,
        serverCert: X509Certificate,
        codeHex: String
    ): ByteArray? {
        if (codeHex.length < 2) return null
        val codeBytes = hexToBytes(codeHex) ?: return null

        val clientPub = clientCert.publicKey as RSAPublicKey
        val serverPub = serverCert.publicKey as RSAPublicKey

                val md = MessageDigest.getInstance("SHA-256")
        md.update(hexToBytes(evenHex(clientPub.modulus)) ?: return null)
        md.update(hexToBytes(evenHex(clientPub.publicExponent)) ?: return null)
        md.update(hexToBytes(evenHex(serverPub.modulus)) ?: return null)
        md.update(hexToBytes(evenHex(serverPub.publicExponent)) ?: return null)
        md.update(hexToBytes(codeHex.substring(2)) ?: return null) // drop the code's first byte
        val hash = md.digest()

        if (hash.isEmpty() || codeBytes.isEmpty() || hash[0] != codeBytes[0]) {
            return null // wrong code
        }
        return hash
    }

    private fun evenHex(value: BigInteger): String {
        var hex = value.toString(16)
        if (hex.length % 2 != 0) hex = "0$hex"
        return hex
    }

    private fun hexToBytes(hex: String): ByteArray? {
        val clean = if (hex.length % 2 != 0) "0$hex" else hex
        return try {
            ByteArray(clean.length / 2) { i ->
                ((Character.digit(clean[i * 2], 16) shl 4) + Character.digit(clean[i * 2 + 1], 16)).toByte()
            }
        } catch (t: Throwable) {
            null
        }
    }

    private const val CLIENT_KEY_PASSWORD = "tvremote"
}

package com.tvremote.app.protocol.pairing

import com.tvremote.app.protocol.crypto.CertificateManager
import com.tvremote.app.protocol.crypto.TlsSupport
import com.tvremote.app.proto.pairing.PairingConfiguration
import com.tvremote.app.proto.pairing.PairingEncoding
import com.tvremote.app.proto.pairing.PairingMessage
import com.tvremote.app.proto.pairing.PairingOption
import com.tvremote.app.proto.pairing.PairingRequest
import com.tvremote.app.proto.pairing.PairingSecret
import com.tvremote.app.proto.pairing.RoleType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.net.ssl.SSLSocket

private const val PAIRING_PORT = 6467
private const val CLIENT_NAME = "TvRemoteApp"
private const val SERVICE_NAME = "TvRemoteApp"
private const val PROTOCOL_VERSION = 2

/**
 * Pairs with an Android TV once, the same way the official Google TV app
 * does: open a mutual-TLS socket to port 6467 and walk through the exact
 * message sequence the real client/server use (verified against the
 * open-source reverse-engineered implementations this protocol is known
 * from — louis49/androidtv-remote and tronikos/androidtvremote2):
 *
 *   client -> PairingRequest
 *   server -> PairingRequestAck
 *   client -> PairingOption (what encodings we can accept)
 *   server -> PairingOption (what it will actually use)
 *   client -> PairingConfiguration (hex, 6 symbols)
 *   server -> PairingConfigurationAck   <- TV now shows the code on screen
 *   client -> PairingSecret (hash proving we read the code)
 *   server -> PairingSecretAck          <- paired
 *
 * After this succeeds once, [AndroidTvRemoteClient] can reconnect on port
 * 6466 indefinitely without repeating this, because the TV remembers our
 * certificate.
 */
class AndroidTvPairingClient(private val certManager: CertificateManager) {

    sealed class PairingResult {
        object Success : PairingResult()
        object WrongCode : PairingResult()
        data class Error(val message: String) : PairingResult()
    }

    private val hexEncoding = PairingEncoding.newBuilder()
        .setType(PairingEncoding.EncodingType.ENCODING_TYPE_HEXADECIMAL)
        .setSymbolLength(6)
        .build()

    /**
     * Connects, exchanges configuration, and suspends until [codeProvider]
     * returns the 6-digit hex code the user read off the TV screen.
     */
    suspend fun pair(host: String, codeProvider: suspend () -> String): PairingResult =
        withContext(Dispatchers.IO) {
            try {
                val sslContext = TlsSupport.buildSslContext(certManager)
                (sslContext.socketFactory.createSocket(host, PAIRING_PORT) as SSLSocket).use { socket ->
                    socket.startHandshake()

                    // 1) Request -> Ack
                    send(socket, PairingMessage.newBuilder().apply {
                        protocolVersion = PROTOCOL_VERSION
                        status = PairingMessage.Status.STATUS_OK
                        pairingRequest = PairingRequest.newBuilder()
                            .setServiceName(SERVICE_NAME)
                            .setClientName(CLIENT_NAME)
                            .build()
                    }.build())
                    val requestAck = receive(socket)
                    if (!requestAck.hasPairingRequestAck()) {
                        return@withContext PairingResult.Error("TV did not acknowledge pairing request")
                    }

                    // 2) Offer our supported encoding -> TV echoes back its choice
                    send(socket, PairingMessage.newBuilder().apply {
                        protocolVersion = PROTOCOL_VERSION
                        status = PairingMessage.Status.STATUS_OK
                        pairingOption = PairingOption.newBuilder()
                            .setPreferredRole(RoleType.ROLE_TYPE_INPUT)
                            .addInputEncodings(hexEncoding)
                            .build()
                    }.build())
                    val optionResponse = receive(socket)
                    if (!optionResponse.hasPairingOption()) {
                        return@withContext PairingResult.Error("TV rejected our pairing options")
                    }

                    // 3) Confirm the configuration -> TV shows the code on screen
                    send(socket, PairingMessage.newBuilder().apply {
                        protocolVersion = PROTOCOL_VERSION
                        status = PairingMessage.Status.STATUS_OK
                        pairingConfiguration = PairingConfiguration.newBuilder()
                            .setClientRole(RoleType.ROLE_TYPE_INPUT)
                            .setEncoding(hexEncoding)
                            .build()
                    }.build())
                    val configAck = receive(socket)
                    if (!configAck.hasPairingConfigurationAck()) {
                        return@withContext PairingResult.Error("TV rejected pairing configuration")
                    }

                    // 4) Now the code is on screen — wait for the person to type it in.
                    val code = codeProvider().trim().uppercase()

                    val clientCert = certManager.certificate
                    val serverCert = TlsSupport.peerCertificate(socket)
                    val secret = TlsSupport.computePairingSecret(clientCert, serverCert, code)
                        ?: return@withContext PairingResult.WrongCode

                    send(socket, PairingMessage.newBuilder().apply {
                        protocolVersion = PROTOCOL_VERSION
                        status = PairingMessage.Status.STATUS_OK
                        pairingSecret = PairingSecret.newBuilder()
                            .setSecret(com.google.protobuf.ByteString.copyFrom(secret))
                            .build()
                    }.build())
                    val secretAck = receive(socket)

                    if (secretAck.hasPairingSecretAck()) {
                        PairingResult.Success
                    } else {
                        PairingResult.Error("TV rejected pairing secret (status ${secretAck.status})")
                    }
                }
            } catch (t: Throwable) {
                PairingResult.Error(t.message ?: t.javaClass.simpleName)
            }
        }

    private fun send(socket: SSLSocket, message: PairingMessage) {
        message.writeDelimitedTo(socket.outputStream)
        socket.outputStream.flush()
    }

    private fun receive(socket: SSLSocket): PairingMessage =
        PairingMessage.parseDelimitedFrom(socket.inputStream)
            ?: throw IOException("Connection closed by TV during pairing")
}

package com.velocimetro.scooterlab

import java.security.KeyPair

enum class ScooterHandshakeState {
    NEW,
    WAITING_PUBLIC_KEY_CONTROL_ACK,
    WAITING_PUBLIC_KEY_DATA_ACK,
    WAITING_PEER_PUBLIC_KEY,
    WAITING_CONFIRMATION_CONTROL_ACK,
    WAITING_CONFIRMATION_DATA_ACK,
    WAITING_SESSION_STATUS,
    AUTHENTICATED,
    FAILED,
}

/**
 * Upper-layer state machine for the security-chip exchange. It has no access to Bluetooth
 * and emits only authentication frames, never application commands.
 */
class ScooterHandshake(private val secretProvider: ScooterSecretProvider) {
    var state: ScooterHandshakeState = ScooterHandshakeState.NEW
        private set
    var failure: String? = null
        private set

    private var keyPair: KeyPair? = null
    private var pendingPublicKey: ByteArray? = null
    private var pendingConfirmation: ByteArray? = null
    private var applicationSessionKey: ByteArray? = null

    fun begin(): List<ByteArray> {
        require(state == ScooterHandshakeState.NEW) { "Handshake was already started" }
        keyPair = ScooterSecurity.generateEphemeralKeyPair()
        pendingPublicKey = ScooterSecurity.encodePublicKey(requireNotNull(keyPair).public)
        state = ScooterHandshakeState.WAITING_PUBLIC_KEY_CONTROL_ACK
        return listOf(ScooterChannelFraming.flowControl(ScooterProtocol.publicKeyPacketType, 1))
    }

    fun onPacket(packet: ScooterChannelPacket): List<ByteArray> = try {
        when (packet) {
            is ScooterChannelPacket.Ack -> onAck(packet)
            is ScooterChannelPacket.SingleControl -> onSingleControl(packet)
            else -> emptyList()
        }
    } catch (error: Exception) {
        state = ScooterHandshakeState.FAILED
        failure = error.message ?: error.javaClass.simpleName
        emptyList()
    }

    /** Processes the final notification from characteristic 0010. */
    fun onSessionStatus(status: ByteArray) {
        if (state != ScooterHandshakeState.WAITING_SESSION_STATUS) return
        if (status.contentEquals(sessionAccepted)) {
            state = ScooterHandshakeState.AUTHENTICATED
        } else {
            state = ScooterHandshakeState.FAILED
            failure = "Scooter rejected the security session"
        }
    }

    /** Returns a defensive copy only after the scooter has explicitly accepted this session. */
    fun authenticatedSessionKey(): ByteArray {
        check(state == ScooterHandshakeState.AUTHENTICATED) { "The scooter session is not authenticated" }
        return requireNotNull(applicationSessionKey) { "Authenticated session key is unavailable" }.copyOf()
    }

    private fun onAck(ack: ScooterChannelPacket.Ack): List<ByteArray> {
        return when (state) {
            ScooterHandshakeState.WAITING_PUBLIC_KEY_CONTROL_ACK -> {
                require(ack.status == controlAccepted) { "Security-channel control acknowledgement failed: ${ack.status}" }
                state = ScooterHandshakeState.WAITING_PUBLIC_KEY_DATA_ACK
                listOf(ScooterChannelFraming.data(1, requireNotNull(pendingPublicKey)))
            }
            ScooterHandshakeState.WAITING_PUBLIC_KEY_DATA_ACK -> {
                require(ack.status == dataAccepted) { "Security-channel data acknowledgement failed: ${ack.status}" }
                state = ScooterHandshakeState.WAITING_PEER_PUBLIC_KEY
                emptyList()
            }
            ScooterHandshakeState.WAITING_CONFIRMATION_CONTROL_ACK -> {
                require(ack.status == controlAccepted) { "Security-channel control acknowledgement failed: ${ack.status}" }
                state = ScooterHandshakeState.WAITING_CONFIRMATION_DATA_ACK
                listOf(ScooterChannelFraming.data(1, requireNotNull(pendingConfirmation)))
            }
            ScooterHandshakeState.WAITING_CONFIRMATION_DATA_ACK -> {
                require(ack.status == dataAccepted) { "Security-channel data acknowledgement failed: ${ack.status}" }
                state = ScooterHandshakeState.WAITING_SESSION_STATUS
                emptyList()
            }
            else -> throw IllegalStateException("Unexpected acknowledgement in $state")
        }
    }

    private fun onSingleControl(packet: ScooterChannelPacket.SingleControl): List<ByteArray> {
        require(state == ScooterHandshakeState.WAITING_PEER_PUBLIC_KEY) { "Unexpected single-control packet in $state" }
        require(packet.packetType == ScooterProtocol.publicKeyPacketType) { "Unexpected peer packet type: ${packet.packetType}" }
        require(packet.payload.size == 64) { "Peer public key must contain 64 bytes" }
        val localKeyPair = requireNotNull(keyPair)
        val peerPublicKey = ScooterSecurity.decodePeerPublicKey(packet.payload, localKeyPair.public)
        val sessionKey = ScooterSecurity.deriveSessionKey(localKeyPair, peerPublicKey, secretProvider.effectiveLtmk())
        applicationSessionKey = sessionKey.copyOf()
        pendingConfirmation = ScooterSecurity.buildConfirmation(sessionKey, packet.payload)
        state = ScooterHandshakeState.WAITING_CONFIRMATION_CONTROL_ACK
        return listOf(
            ScooterChannelFraming.singleControlAcknowledgement(),
            ScooterChannelFraming.flowControl(ScooterProtocol.confirmationPacketType, 1),
        )
    }

    private companion object {
        const val controlAccepted = 1
        const val dataAccepted = 0
        val sessionAccepted = byteArrayOf(0x21, 0, 0, 0)
    }
}

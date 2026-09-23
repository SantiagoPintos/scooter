package com.velocimetro.scooterlab

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ScooterChannelFramingTest {
    @Test
    fun flowControlAndDataUseLittleEndianSequenceFields() {
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 3, 1, 0),
            ScooterChannelFraming.flowControl(ScooterProtocol.publicKeyPacketType, 1),
        )
        assertArrayEquals(
            byteArrayOf(1, 0, 9, 8, 7),
            ScooterChannelFraming.data(1, byteArrayOf(9, 8, 7)),
        )
    }

    @Test
    fun singleControlRoundTripsPeerPublicKey() {
        val publicKey = ByteArray(64) { it.toByte() }
        val frame = ScooterChannelFraming.singleControl(ScooterProtocol.publicKeyPacketType, publicKey)
        val decoded = ScooterChannelFraming.decode(frame) as ScooterChannelPacket.SingleControl
        assertEquals(ScooterProtocol.publicKeyPacketType, decoded.packetType)
        assertArrayEquals(publicKey, decoded.payload)
    }

    @Test
    fun singleControlAcknowledgementUsesDedicatedControlKind() {
        assertArrayEquals(
            byteArrayOf(0, 0, 3, 0),
            ScooterChannelFraming.singleControlAcknowledgement(),
        )
    }

    @Test
    fun handshakeOnlyEmitsAuthenticationFrames() {
        val machine = ScooterHandshake(ScooterSecretProvider { ByteArray(32) { 7 } })
        val start = machine.begin()
        assertEquals(ScooterHandshakeState.WAITING_PUBLIC_KEY_CONTROL_ACK, machine.state)
        assertEquals(1, start.size)
        val publicKeyData = machine.onPacket(ScooterChannelPacket.Ack(1, emptyList()))
        assertEquals(ScooterHandshakeState.WAITING_PUBLIC_KEY_DATA_ACK, machine.state)
        val data = ScooterChannelFraming.decode(publicKeyData.single()) as ScooterChannelPacket.DataFragment
        assertEquals(64, data.payload.size)
        machine.onPacket(ScooterChannelPacket.Ack(0, emptyList()))
        assertEquals(ScooterHandshakeState.WAITING_PEER_PUBLIC_KEY, machine.state)
    }

    @Test
    fun handshakeRequiresTheExplicitFinalSessionStatus() {
        val machine = ScooterHandshake(ScooterSecretProvider { ByteArray(32) { 7 } })
        machine.begin()
        machine.onPacket(ScooterChannelPacket.Ack(1, emptyList()))
        machine.onPacket(ScooterChannelPacket.Ack(0, emptyList()))
        val scooterKeyPair = ScooterSecurity.generateEphemeralKeyPair()
        val peerPublicKey = ScooterSecurity.encodePublicKey(scooterKeyPair.public)
        val confirmationStart = machine.onPacket(ScooterChannelPacket.SingleControl(ScooterProtocol.publicKeyPacketType, peerPublicKey))
        assertEquals(2, confirmationStart.size)
        machine.onPacket(ScooterChannelPacket.Ack(1, emptyList()))
        machine.onPacket(ScooterChannelPacket.Ack(0, emptyList()))
        assertEquals(ScooterHandshakeState.WAITING_SESSION_STATUS, machine.state)
        machine.onSessionStatus(byteArrayOf(0x21, 0, 0, 0))
        assertEquals(ScooterHandshakeState.AUTHENTICATED, machine.state)
    }
}

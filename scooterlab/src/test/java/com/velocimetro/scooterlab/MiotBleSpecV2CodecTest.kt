package com.velocimetro.scooterlab

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MiotBleSpecV2CodecTest {
    @Test
    fun phoneTimeMatchesXiaomiHomesUtcPlusLocalOffsetRule() {
        assertEquals(1_699_989_200L, MiotBleSpecV2Codec.phoneTimeSeconds(1_700_000_000L, -10_800))
        assertEquals(1_700_007_200L, MiotBleSpecV2Codec.phoneTimeSeconds(1_700_000_000L, 7_200))
    }

    @Test
    fun lockPropertyUsesMiotControlPropertyAndBooleanType() {
        val packet = MiotBleSpecV2Codec.setScooterLock(requestId = 2, locked = true)
        val fields = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals(12 or 0x2000, fields.short.toInt() and 0xffff)
        assertEquals(2, fields.short.toInt() and 0xffff)
        assertEquals(0, fields.get().toInt() and 0xff)
        assertEquals(1, fields.get().toInt() and 0xff)
        assertEquals(4, fields.get().toInt() and 0xff)
        assertEquals(6, fields.short.toInt() and 0xffff)
        assertEquals(1, fields.short.toInt() and 0xffff)
        assertEquals(1, fields.get().toInt() and 0xff)
    }

    @Test
    fun unlockOnlyChangesTheBooleanValue() {
        val lock = MiotBleSpecV2Codec.setScooterLock(requestId = 7, locked = true)
        val unlock = MiotBleSpecV2Codec.setScooterLock(requestId = 7, locked = false)

        assertArrayEquals(lock.copyOfRange(0, lock.lastIndex), unlock.copyOfRange(0, unlock.lastIndex))
        assertEquals(1, lock.last().toInt())
        assertEquals(0, unlock.last().toInt())
    }

    @Test
    fun batteryPropertyUsesThe6MaxBatteryInformationDefinition() {
        val packet = MiotBleSpecV2Codec.getProperty(requestId = 12, serviceId = 3, propertyId = 19)
        val fields = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals(9 or 0x2000, fields.short.toInt() and 0xffff)
        assertEquals(12, fields.short.toInt() and 0xffff)
        assertEquals(2, fields.get().toInt() and 0xff)
        assertEquals(1, fields.get().toInt() and 0xff)
        assertEquals(3, fields.get().toInt() and 0xff)
        assertEquals(19, fields.short.toInt() and 0xffff)
    }

    @Test
    fun requestIdsIncrementAndWrapBeforeTheReservedValue() {
        val counter = MiotBleSpecRequestCounter(0xfffd)

        assertEquals(0xfffe, counter.next())
        assertEquals(2, counter.next())
    }

    @Test
    fun outgoingCiphertextUsesFreshCounterAndAuthenticatesWithSyntheticSessionData() {
        val sessionKey = ByteArray(64) { it.toByte() }
        val plaintext = MiotBleSpecV2Codec.setScooterLock(requestId = 2, locked = true)
        val cipher = MiotBleApplicationCipher(sessionKey)

        val first = cipher.sealOutbound(plaintext)
        val second = cipher.sealOutbound(plaintext)

        assertEquals(plaintext.size + 6, first.size)
        assertNotEquals(first.toList(), second.toList())
        assertArrayEquals(byteArrayOf(0, 0), first.copyOfRange(0, 2))
        assertArrayEquals(byteArrayOf(1, 0), second.copyOfRange(0, 2))
    }

    @Test
    fun inboundCipherUsesTheHighBitTransitionAsItsNonceEpochBoundary() {
        val sessionKey = ByteArray(64) { (it + 51).toByte() }
        val plaintext = MiotBleSpecV2Codec.getProperty(requestId = 14, serviceId = 3, propertyId = 19)
        val nonce = ByteArray(12)
        sessionKey.copyOfRange(32, 36).copyInto(nonce)
        nonce[8] = 0
        nonce[9] = 0x80.toByte()
        nonce[10] = 1
        val payload = byteArrayOf(0, 0x80.toByte()) + AesCcm.encrypt(
            key = sessionKey.copyOfRange(0, 16),
            nonce = nonce,
            plaintext = plaintext,
            tagLength = 4,
        )

        assertArrayEquals(plaintext, MiotBleApplicationCipher(sessionKey).openInbound(payload))
    }

    @Test
    fun inboundCipherPreservesTheObservedSequenceAfterAnUnauthenticatedDelivery() {
        val sessionKey = ByteArray(64) { (it + 61).toByte() }
        val receiver = MiotBleApplicationCipher(sessionKey)
        try {
            receiver.openInbound(byteArrayOf(0, 0x80.toByte(), 0, 0, 0, 0))
            throw AssertionError("An invalid CCM tag must not be accepted")
        } catch (_: Exception) {
            // Xiaomi Home nevertheless retains this sequence before attempting CCM validation.
        }
        val plaintext = MiotBleSpecV2Codec.getProperty(requestId = 15, serviceId = 3, propertyId = 19)
        val nonce = ByteArray(12)
        sessionKey.copyOfRange(32, 36).copyInto(nonce)
        nonce[8] = 1
        nonce[9] = 0x80.toByte()
        nonce[10] = 1
        val payload = byteArrayOf(1, 0x80.toByte()) + AesCcm.encrypt(
            key = sessionKey.copyOfRange(0, 16),
            nonce = nonce,
            plaintext = plaintext,
            tagLength = 4,
        )

        assertArrayEquals(plaintext, receiver.openInbound(payload))
    }

    @Test
    fun commandComposerWaitsForFlowAcknowledgementBeforeSendingData() {
        val composer = MiotScooterCommandComposer(ByteArray(64) { (it + 11).toByte() })
        val frames = composer.beginLock(true)

        assertEquals(1, frames.size)
        val control = ScooterChannelFraming.decode(frames.first()) as ScooterChannelPacket.FlowControl
        assertEquals(0, control.packetType)
        assertEquals(1, control.frameCount)
        assertEquals(MiotScooterCommandState.WAITING_FLOW_ACK, composer.state)

        assertEquals(emptyList<ByteArray>(), composer.onApplicationFrame(ScooterChannelFraming.ack(status = 0)))
        assertEquals(MiotScooterCommandState.FAILED, composer.state)

        val acceptedComposer = MiotScooterCommandComposer(ByteArray(64) { (it + 11).toByte() })
        acceptedComposer.beginLock(true)
        val dataFrames = acceptedComposer.onApplicationFrame(ScooterChannelFraming.ack(status = 1))
        assertEquals(MiotScooterCommandState.WAITING_DATA_ACK, acceptedComposer.state)
        assertEquals(2, dataFrames.size)
        assertArrayEquals(dataFrames.first(), dataFrames.last())
        val data = ScooterChannelFraming.decode(dataFrames.first()) as ScooterChannelPacket.DataFragment
        assertEquals(1, data.sequence)
        assertEquals(18, data.payload.size)

        assertEquals(emptyList<ByteArray>(), acceptedComposer.onApplicationFrame(ScooterChannelFraming.ack(status = 0)))
        // A generic data receipt only confirms transport delivery. The authenticated MiOT
        // response for this exact property write is what confirms the physical operation.
        assertEquals(MiotScooterCommandState.WAITING_RESPONSE, acceptedComposer.state)
    }

    @Test
    fun commandComposerCanUseObservedNoReceiptFlowFallback() {
        val composer = MiotScooterCommandComposer(ByteArray(64) { (it + 11).toByte() })
        composer.beginLock(true)

        val dataFrames = composer.advanceWithoutFlowAcknowledgement()

        assertEquals(MiotScooterCommandState.WAITING_DATA_ACK, composer.state)
        assertEquals(2, dataFrames.size)
        assertEquals(1, (ScooterChannelFraming.decode(dataFrames.first()) as ScooterChannelPacket.DataFragment).sequence)
        assertArrayEquals(dataFrames.first(), dataFrames.last())
    }

    @Test
    fun batteryReaderWaitsForMatchingReadResponseBeforePublishingPercentage() {
        val reader = MiotScooterBatteryReader(
            MiotBleSpecRequestCounter(),
            MiotBleApplicationCipher(ByteArray(64) { (it + 31).toByte() }),
        )

        val flowFrames = reader.begin()
        val flow = ScooterChannelFraming.decode(flowFrames.single()) as ScooterChannelPacket.FlowControl
        assertEquals(0, flow.packetType)
        assertEquals(1, flow.frameCount)
        assertEquals(MiotScooterBatteryReadState.WAITING_FLOW_ACK, reader.state)

        val dataFrames = reader.onApplicationFrame(ScooterChannelFraming.ack(status = 1))
        assertEquals(2, dataFrames.size)
        assertArrayEquals(dataFrames.first(), dataFrames.last())
        assertEquals(MiotScooterBatteryReadState.WAITING_RESPONSE, reader.state)

        val unrelated = MiotInboundPayloadMetadata(12, 12, 2, 3, 1, 4, 6, 1, batteryPercentValue = 63)
        assertEquals(null, reader.onInboundResponse(unrelated))
        assertEquals(MiotScooterBatteryReadState.WAITING_RESPONSE, reader.state)

        val battery = MiotInboundPayloadMetadata(12, 12, 2, 3, 1, 3, 19, 1, batteryPercentValue = 82)
        assertEquals(82, reader.onInboundResponse(battery))
        assertEquals(MiotScooterBatteryReadState.COMPLETED, reader.state)
    }

    @Test
    fun batteryReaderRejectsOutOfRangeValues() {
        val reader = MiotScooterBatteryReader(
            MiotBleSpecRequestCounter(),
            MiotBleApplicationCipher(ByteArray(64) { (it + 33).toByte() }),
        )
        reader.begin()
        reader.advanceWithoutFlowAcknowledgement()

        val invalid = MiotInboundPayloadMetadata(12, 12, 2, 3, 1, 3, 19, 1, batteryPercentValue = 101)
        assertEquals(null, reader.onInboundResponse(invalid))
        assertEquals(MiotScooterBatteryReadState.WAITING_RESPONSE, reader.state)
    }

    @Test
    fun powerModeReaderUsesTheReadOnlyWorkStateProperty() {
        val reader = MiotScooterPowerModeReader(
            MiotBleSpecRequestCounter(),
            MiotBleApplicationCipher(ByteArray(64) { (it + 37).toByte() }),
        )

        val flow = ScooterChannelFraming.decode(reader.begin().single()) as ScooterChannelPacket.FlowControl
        assertEquals(0, flow.packetType)
        assertEquals(1, flow.frameCount)
        assertEquals(MiotScooterPowerModeReadState.WAITING_FLOW_ACK, reader.state)

        val dataFrames = reader.onApplicationFrame(ScooterChannelFraming.ack(status = 1))
        assertEquals(2, dataFrames.size)
        assertArrayEquals(dataFrames.first(), dataFrames.last())
        assertEquals(MiotScooterPowerModeReadState.WAITING_RESPONSE, reader.state)

        val unrelated = MiotInboundPayloadMetadata(12, 12, 2, 3, 1, 3, 1, 1, powerModeValue = 2)
        assertEquals(null, reader.onInboundResponse(unrelated))

        val sport = MiotInboundPayloadMetadata(14, 14, 2, 3, 1, 2, 1, 0, powerModeValue = 3)
        assertEquals(3, reader.onInboundResponse(sport))
        assertEquals(MiotScooterPowerModeReadState.COMPLETED, reader.state)
    }

    @Test
    fun commandComposerAcceptsMatchingMiotPropertyResponseWhenGenericReceiptIsOmitted() {
        val composer = MiotScooterCommandComposer(ByteArray(64) { (it + 11).toByte() })
        composer.beginLock(true)
        composer.advanceWithoutFlowAcknowledgement()

        val unrelated = MiotInboundPayloadMetadata(11, 11, 7, 1, 1, 4, 6, 0)
        assertEquals(false, composer.onInboundResponse(unrelated))
        assertEquals(MiotScooterCommandState.WAITING_DATA_ACK, composer.state)

        val matching = MiotInboundPayloadMetadata(11, 11, 2, 1, 1, 4, 6, 0)
        assertEquals(true, composer.onInboundResponse(matching))
        assertEquals(MiotScooterCommandState.COMPLETED, composer.state)
    }

    @Test
    fun initializerEmitsTheObservedStartupFlowsWithTheirDataImmediately() {
        val initializer = MiotScooterApplicationInitializer(
            MiotBleSpecRequestCounter(),
            MiotBleApplicationCipher(ByteArray(64) { (it + 21).toByte() }),
        )

        var frames = initializer.begin(phoneTimeSeconds = 1_700_000_000L)
        assertEquals(MiotScooterInitializationState.WAITING_FLOW_ACK, initializer.state)
        val payloadSizes = mutableListOf<Int>()
        repeat(4) { index ->
            val flow = ScooterChannelFraming.decode(frames.single()) as ScooterChannelPacket.FlowControl
            assertEquals(0, flow.packetType)
            assertEquals(1, flow.frameCount)
            frames = initializer.onApplicationFrame(ScooterChannelFraming.ack(status = 1))
            assertEquals(2, frames.size)
            payloadSizes += (ScooterChannelFraming.decode(frames.first()) as ScooterChannelPacket.DataFragment).payload.size
            assertArrayEquals(frames.first(), frames.last())
            frames = initializer.onApplicationFrame(ScooterChannelFraming.ack(status = 0))
            if (index < 3) assertEquals(1, frames.size)
        }
        assertEquals(listOf(11, 15, 15, 21), payloadSizes)
        assertEquals(MiotScooterInitializationState.COMPLETED, initializer.state)
    }

    @Test
    fun initializerCanAdvanceTheObservedNoReceiptStartupPath() {
        val initializer = MiotScooterApplicationInitializer(
            MiotBleSpecRequestCounter(),
            MiotBleApplicationCipher(ByteArray(64) { (it + 21).toByte() }),
        )
        var next = initializer.begin(phoneTimeSeconds = 1_700_000_000L)
        repeat(4) { index ->
            assertEquals(1, next.size)
            next = initializer.advanceWithoutFlowAcknowledgement()
            assertEquals(2, next.size)
            assertArrayEquals(next.first(), next.last())
            next = initializer.advanceWithoutDataAcknowledgement()
            if (index < 3) assertEquals(1, next.size)
        }
        assertEquals(MiotScooterInitializationState.COMPLETED, initializer.state)
    }

    @Test
    fun inboundMetadataRetainsOnlyMiotEnvelopeFields() {
        val receiverSession = ByteArray(64) { (it + 21).toByte() }
        val senderSession = ByteArray(64)
        receiverSession.copyInto(senderSession, destinationOffset = 16, startIndex = 0, endIndex = 16)
        receiverSession.copyInto(senderSession, destinationOffset = 36, startIndex = 32, endIndex = 36)
        val sender = MiotBleApplicationCipher(senderSession)
        val receiver = MiotScooterApplicationInitializer(
            MiotBleSpecRequestCounter(),
            MiotBleApplicationCipher(receiverSession),
        )
        val payload = sender.sealOutbound(MiotBleSpecV2Codec.setScooterLock(requestId = 9, locked = false))

        val metadata = receiver.consumeInitialPayload(payload)

        assertEquals(12, metadata?.plaintextLength)
        assertEquals(12, metadata?.declaredLength)
        assertEquals(9, metadata?.requestId)
        assertEquals(0, metadata?.operation)
        assertEquals(1, metadata?.propertyCount)
        assertEquals(4, metadata?.serviceId)
        assertEquals(6, metadata?.propertyId)
        assertEquals(1, metadata?.valueTypeAndLength)
    }

    @Test
    fun inboundConsumerExtractsOnlyBatteryPercentageFromBatteryInformation() {
        val receiverSession = ByteArray(64) { (it + 41).toByte() }
        val senderSession = ByteArray(64)
        receiverSession.copyInto(senderSession, destinationOffset = 16, startIndex = 0, endIndex = 16)
        receiverSession.copyInto(senderSession, destinationOffset = 36, startIndex = 32, endIndex = 36)
        val value = "{\"cb\":82}".encodeToByteArray()
        val plaintext = ByteBuffer.allocate(11 + value.size).order(ByteOrder.LITTLE_ENDIAN)
            .putShort((0x2000 or (11 + value.size)).toShort())
            .putShort(2)
            .put(3)
            .put(1)
            .put(3)
            .putShort(19)
            .putShort((0x3000 or value.size).toShort())
            .put(value)
            .array()

        val metadata = MiotScooterInboundResponseConsumer(MiotBleApplicationCipher(receiverSession))
            .consume(MiotBleApplicationCipher(senderSession).sealOutbound(plaintext))

        assertEquals(3, metadata?.serviceId)
        assertEquals(19, metadata?.propertyId)
        assertEquals(82, metadata?.batteryPercentValue)
    }
}

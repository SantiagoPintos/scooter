package com.velocimetro.scooterlab

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScooterApplicationChannelTest {
    @Test
    fun initialSingleControlIsAcknowledgedBeforeTheChannelIsReady() {
        val channel = ScooterApplicationChannel()

        val result = channel.onInboundFrame(
            ScooterChannelFraming.singleControl(packetType = 0, payload = ByteArray(18)),
        )

        assertTrue(result.becameReady)
        assertEquals(ScooterApplicationChannelState.READY, channel.state)
        assertArrayEquals(
            ScooterChannelFraming.singleControlAcknowledgement(),
            result.responseFrames.single(),
        )
    }

    @Test
    fun completeInboundResponseFlowReceivesFinalDataAcknowledgement() {
        val channel = ScooterApplicationChannel()

        val flow = channel.onInboundFrame(ScooterChannelFraming.flowControl(packetType = 0, frameCount = 2))
        val firstData = channel.onInboundFrame(ScooterChannelFraming.data(1, byteArrayOf(1)))
        val secondData = channel.onInboundFrame(ScooterChannelFraming.data(2, byteArrayOf(2)))

        assertArrayEquals(ScooterChannelFraming.ack(status = 1), flow.responseFrames.single())
        assertEquals(emptyList<ByteArray>(), firstData.responseFrames)
        assertArrayEquals(ScooterChannelFraming.ack(status = 0), secondData.responseFrames.single())
    }

    @Test
    fun incomingFlowControlIsAcceptedWithoutCreatingAnApplicationCommand() {
        val channel = ScooterApplicationChannel()

        val result = channel.onInboundFrame(ScooterChannelFraming.flowControl(packetType = 0, frameCount = 1))

        assertTrue(result.becameReady)
        assertArrayEquals(ScooterChannelFraming.ack(status = 1), result.responseFrames.single())
        assertEquals(ScooterApplicationChannelState.READY, channel.state)
    }

    @Test
    fun anAcknowledgementDoesNotMakeAChannelReadyByItself() {
        val channel = ScooterApplicationChannel()

        val result = channel.onInboundFrame(ScooterChannelFraming.ack(status = 1))

        assertFalse(result.becameReady)
        assertTrue(result.responseFrames.isEmpty())
        assertEquals(ScooterApplicationChannelState.INITIALIZING, channel.state)
    }
}

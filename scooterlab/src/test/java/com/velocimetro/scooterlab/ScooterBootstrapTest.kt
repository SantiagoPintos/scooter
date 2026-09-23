package com.velocimetro.scooterlab

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScooterBootstrapTest {
    @Test
    fun acknowledgesA4EnvelopeWithoutChangingItsPayload() {
        val incoming = byteArrayOf(0, 0, 4, 0, 0x12, 0x34)

        assertArrayEquals(
            byteArrayOf(0, 0, 5, 0, 0x12, 0x34),
            ScooterBootstrap.acknowledgement(incoming),
        )
        assertFalse(ScooterBootstrap.completesBootstrap(incoming))
    }

    @Test
    fun identifiesFinalBootstrapEnvelope() {
        val incoming = byteArrayOf(0, 0, 4, 1, 0x56, 0x78)

        assertTrue(ScooterBootstrap.completesBootstrap(incoming))
    }

    @Test
    fun ignoresOrdinarySecurityChannelFrames() {
        assertNull(ScooterBootstrap.acknowledgement(byteArrayOf(0, 0, 0, 3, 1, 0)))
    }
}

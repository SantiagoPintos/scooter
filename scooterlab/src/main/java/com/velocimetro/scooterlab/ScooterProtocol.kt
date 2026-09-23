package com.velocimetro.scooterlab

import java.util.UUID

/** Values that describe the observed security channel, not application commands. */
object ScooterProtocol {
    val serviceUuid: UUID = UUID.fromString("0000fe95-0000-1000-8000-00805f9b34fb")
    val capabilityUuid: UUID = UUID.fromString("00000004-0000-1000-8000-00805f9b34fb")
    val authenticationUuid: UUID = UUID.fromString("00000016-0000-1000-8000-00805f9b34fb")
    val sessionStatusUuid: UUID = UUID.fromString("00000010-0000-1000-8000-00805f9b34fb")
    val applicationWriteUuid: UUID = UUID.fromString("0000001a-0000-1000-8000-00805f9b34fb")
    val applicationResponseUuid: UUID = UUID.fromString("0000001b-0000-1000-8000-00805f9b34fb")

    /** Authentication-only controls observed before the P-256 exchange. */
    val bootstrapStart: ByteArray = byteArrayOf(0xa4.toByte())
    val sessionStart: ByteArray = byteArrayOf(0x20, 0, 0, 0)

    const val requiredAttMtu: Int = 247
    const val publicKeyPacketType: Int = 3
    const val confirmationPacketType: Int = 5
    const val defaultDataPayloadBytes: Int = 242
}

/** Supplies device-specific material without allowing it to enter source control or logs. */
fun interface ScooterSecretProvider {
    /** Returns the effective 32-byte LTMK expected by the security-chip connector. */
    fun effectiveLtmk(): ByteArray
}

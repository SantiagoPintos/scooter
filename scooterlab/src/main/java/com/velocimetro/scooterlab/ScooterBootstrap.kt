package com.velocimetro.scooterlab

/**
 * Handles the device-driven readiness exchange that precedes the security-channel framing.
 * The peer sends A4 envelopes for stages 0 and 1; the client acknowledges each envelope as
 * A5 without inspecting or exposing its payload. This is transport preparation, not an
 * application command.
 */
object ScooterBootstrap {
    private const val envelopePrefix = 0
    private const val a4 = 4
    private const val a5 = 5
    private const val finalStage = 1

    fun acknowledgement(incoming: ByteArray): ByteArray? {
        if (!isA4Envelope(incoming)) return null
        return incoming.copyOf().also { it[2] = a5.toByte() }
    }

    fun completesBootstrap(incoming: ByteArray): Boolean =
        isA4Envelope(incoming) && incoming[3].toInt() and 0xff == finalStage

    private fun isA4Envelope(frame: ByteArray): Boolean =
        frame.size >= 4 &&
            frame[0].toInt() and 0xff == envelopePrefix &&
            frame[1].toInt() and 0xff == envelopePrefix &&
            frame[2].toInt() and 0xff == a4
}

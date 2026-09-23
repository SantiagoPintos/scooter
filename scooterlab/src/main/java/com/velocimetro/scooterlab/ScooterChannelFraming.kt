package com.velocimetro.scooterlab

import java.nio.ByteBuffer
import java.nio.ByteOrder

sealed interface ScooterChannelPacket {
    data class FlowControl(val packetType: Int, val frameCount: Int) : ScooterChannelPacket
    data class Ack(val status: Int, val sequences: List<Int>) : ScooterChannelPacket
    data class SingleControl(val packetType: Int, val payload: ByteArray) : ScooterChannelPacket
    data class Management(val packetType: Int, val payload: ByteArray) : ScooterChannelPacket
    data class DataFragment(val sequence: Int, val payload: ByteArray) : ScooterChannelPacket
}

/** Codec for the security-channel control frames observed in Xiaomi Home. */
object ScooterChannelFraming {
    private const val flowControl = 0
    private const val ack = 1
    private const val singleControl = 2
    private const val singleControlAck = 3
    private const val management = 4

    fun flowControl(packetType: Int, frameCount: Int): ByteArray {
        require(packetType in 0..255 && frameCount in 1..0xffff)
        return ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0)
            .put(flowControl.toByte())
            .put(packetType.toByte())
            .putShort(frameCount.toShort())
            .array()
    }

    fun ack(status: Int, sequences: List<Int> = emptyList()): ByteArray {
        require(status in -128..255 && sequences.all { it in 0..0xffff })
        return ByteBuffer.allocate(4 + sequences.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0)
            .put(ack.toByte())
            .put(status.toByte())
            .apply { sequences.forEach { putShort(it.toShort()) } }
            .array()
    }

    fun singleControl(packetType: Int, payload: ByteArray): ByteArray {
        require(packetType in 0..255)
        return ByteBuffer.allocate(4 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0)
            .put(singleControl.toByte())
            .put(packetType.toByte())
            .put(payload)
            .array()
    }

    fun singleControlAcknowledgement(status: Int = 0): ByteArray {
        require(status in -128..255)
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0)
            .put(singleControlAck.toByte())
            .put(status.toByte())
            .array()
    }

    fun data(sequence: Int, payload: ByteArray): ByteArray {
        require(sequence in 1..0xffff)
        return ByteBuffer.allocate(2 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(sequence.toShort())
            .put(payload)
            .array()
    }

    /** Sends an upper-layer message through Xiaomi Home's flow-control route. */
    fun outboundFlow(packetType: Int, payload: ByteArray, maxPayloadBytes: Int = ScooterProtocol.defaultDataPayloadBytes): List<ByteArray> {
        require(maxPayloadBytes in 1..0xffff)
        val chunks = payload.asList().chunked(maxPayloadBytes).map { it.toByteArray() }
        val nonEmptyChunks = if (chunks.isEmpty()) listOf(ByteArray(0)) else chunks
        return buildList {
            add(flowControl(packetType, nonEmptyChunks.size))
            nonEmptyChunks.forEachIndexed { index, chunk -> add(data(index + 1, chunk)) }
        }
    }

    fun decode(frame: ByteArray): ScooterChannelPacket {
        require(frame.size >= 2) { "Channel frame is shorter than a sequence field" }
        val buffer = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
        val sequence = buffer.short.toInt() and 0xffff
        if (sequence != 0) return ScooterChannelPacket.DataFragment(sequence, frame.copyOfRange(2, frame.size))
        require(frame.size >= 4) { "Control frame is shorter than four bytes" }
        val kind = buffer.get().toInt() and 0xff
        val packetType = buffer.get().toInt() and 0xff
        return when (kind) {
            flowControl -> {
                require(frame.size == 6) { "Flow control must contain exactly six bytes" }
                ScooterChannelPacket.FlowControl(packetType, buffer.short.toInt() and 0xffff)
            }
            ack -> {
                require((frame.size - 4) % 2 == 0) { "ACK sequence list must contain 16-bit entries" }
                val sequences = buildList { while (buffer.remaining() >= 2) add(buffer.short.toInt() and 0xffff) }
                ScooterChannelPacket.Ack(packetType.toByte().toInt(), sequences)
            }
            singleControl -> ScooterChannelPacket.SingleControl(packetType, frame.copyOfRange(4, frame.size))
            management -> ScooterChannelPacket.Management(packetType, frame.copyOfRange(4, frame.size))
            else -> throw IllegalArgumentException("Unknown channel packet kind: $kind")
        }
    }
}

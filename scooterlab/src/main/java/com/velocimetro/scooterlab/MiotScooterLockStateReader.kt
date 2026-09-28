package com.velocimetro.scooterlab

/** Reads Xiaomi Home's current lock flag (`ls`) from MiOT device information (2.10). */
class MiotScooterLockStateReader(
    private val requestIds: MiotBleSpecRequestCounter,
    private val cipher: MiotBleApplicationCipher,
) {
    enum class State { IDLE, WAITING_FLOW_ACK, WAITING_RESPONSE, COMPLETED, FAILED }

    var state = State.IDLE
        private set
    private var pendingRequestId: Int? = null
    private var pendingDataFrames: List<ByteArray> = emptyList()

    fun begin(): List<ByteArray> {
        check(state == State.IDLE || state == State.COMPLETED || state == State.FAILED)
        val requestId = requestIds.next()
        val sealed = cipher.sealOutbound(
            MiotBleSpecV2Codec.getProperty(requestId, serviceId, propertyId),
        )
        val logicalFrames = sealed.asList().chunked(ScooterProtocol.defaultDataPayloadBytes)
            .mapIndexed { index, chunk -> ScooterChannelFraming.data(index + 1, chunk.toByteArray()) }
        pendingDataFrames = logicalFrames.flatMap { frame -> listOf(frame, frame.copyOf()) }
        pendingRequestId = requestId
        state = State.WAITING_FLOW_ACK
        return listOf(ScooterChannelFraming.flowControl(0, logicalFrames.size))
    }

    fun onApplicationFrame(frame: ByteArray): List<ByteArray> = try {
        val packet = ScooterChannelFraming.decode(frame)
        if (packet !is ScooterChannelPacket.Ack) emptyList() else when (state) {
            State.WAITING_FLOW_ACK -> {
                require(packet.status == 1)
                state = State.WAITING_RESPONSE
                pendingDataFrames
            }
            State.WAITING_RESPONSE -> {
                require(packet.status == 0 || packet.status == 1)
                emptyList()
            }
            else -> emptyList()
        }
    } catch (_: Exception) {
        cancel()
        emptyList()
    }

    fun advanceWithoutFlowAcknowledgement(): List<ByteArray> {
        if (state != State.WAITING_FLOW_ACK) return emptyList()
        state = State.WAITING_RESPONSE
        return pendingDataFrames
    }

    fun onInboundResponse(metadata: MiotInboundPayloadMetadata): Boolean? {
        if (responseRejectionReason(metadata) != null) return null
        val locked = metadata.lockStateValue ?: return null
        pendingDataFrames = emptyList()
        pendingRequestId = null
        state = State.COMPLETED
        return locked
    }

    /** Returns only a categorical reason; never exposes the reported lock value. */
    fun responseRejectionReason(metadata: MiotInboundPayloadMetadata): String? = when {
        metadata.lockStateValue == null -> "invalid_value:${metadata.lockStateParseStatus ?: "unavailable"}"
        state != State.WAITING_RESPONSE -> "transaction_state"
        metadata.requestId != pendingRequestId -> "request_correlation"
        metadata.operation !in getPropertyResponseOperations -> "operation"
        metadata.propertyCount != 1 -> "property_count"
        metadata.serviceId != serviceId || metadata.propertyId != propertyId -> "property"
        else -> null
    }

    fun cancel() {
        pendingDataFrames = emptyList()
        pendingRequestId = null
        state = State.FAILED
    }

    companion object {
        const val serviceId = 2
        const val propertyId = 10
        val getPropertyResponseOperations = setOf(1, 3)
    }
}

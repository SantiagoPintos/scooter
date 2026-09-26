package com.velocimetro.scooterlab

/** Tracks MiOT 3.2 from a matching read or an authenticated property notification. */
class MiotScooterChargingStatusReader(
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
        val sealed = cipher.sealOutbound(MiotBleSpecV2Codec.getProperty(requestId, serviceId, propertyId))
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

    /** Notifications have no client request ID; read replies must match the pending request. */
    fun onInboundResponse(metadata: MiotInboundPayloadMetadata): Int? {
        if (metadata.propertyCount != 1 || metadata.serviceId != serviceId ||
            metadata.propertyId != propertyId
        ) return null
        val value = metadata.chargingStatusValue ?: return null
        if (metadata.operation == 4) return value
        if (state != State.WAITING_RESPONSE || metadata.operation !in setOf(1, 3) ||
            metadata.requestId != pendingRequestId
        ) return null
        pendingDataFrames = emptyList()
        pendingRequestId = null
        state = State.COMPLETED
        return value
    }

    fun cancel() {
        pendingDataFrames = emptyList()
        pendingRequestId = null
        state = State.FAILED
    }

    companion object {
        const val serviceId = 3
        const val propertyId = 2

        /** MiOT 3.2 codes 1 (Charged) and 3 (QuickCharge) denote charging states. */
        fun isCharging(value: Int?): Boolean = value == 1 || value == 3
    }
}

package com.velocimetro.scooterlab

/**
 * Read-only probe for the VCU region code via the after-sales ECU SN property.
 *
 * The dashboard firmware property table (sec2_MCUAPP.bin) maps MiOT `7.2` (ecu-sn) to
 * getter/setter functions whose code publishes the `CountryCode.get.vcu.comm` /
 * `CountryCode.set.vcu.comm` UART topics. A GET on `7.2` therefore makes the dashboard ask
 * the VCU for its region and return the reply string. The Scooter 6 VCU research documents
 * region tokens shaped like `EU1`/`BU1`/`EU2`.
 *
 * Extraction exposes ONLY the region token. The ECU serial number itself is never returned,
 * logged, or retained by this reader; the reply bytes are wiped by the codec consumer after
 * extraction. The `7.2` mapping is derived from the firmware table and requires live
 * verification; a silent or refused property is reported as unavailable, never retried
 * automatically.
 */
class MiotScooterCountryCodeReader(
    private val requestIds: MiotBleSpecRequestCounter,
    private val cipher: MiotBleApplicationCipher,
) {
    enum class State { IDLE, WAITING_FLOW_ACK, WAITING_RESPONSE, COMPLETED, FAILED }

    var state = State.IDLE
        private set
    var replyStructure: String? = null
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
        if (state == State.WAITING_FLOW_ACK) state = State.WAITING_RESPONSE
        return pendingDataFrames
    }

    fun onInboundResponse(metadata: MiotInboundPayloadMetadata): String? {
        if (state != State.WAITING_RESPONSE ||
            metadata.requestId != pendingRequestId ||
            metadata.operation !in setOf(1, 3) ||
            metadata.propertyCount != 1 ||
            metadata.serviceId != serviceId || metadata.propertyId != propertyId
        ) return null
        replyStructure = metadata.ecuReplyStructure
        pendingDataFrames = emptyList()
        pendingRequestId = null
        state = State.COMPLETED
        return metadata.ecuRegionToken
    }

    fun cancel() {
        pendingDataFrames = emptyList()
        pendingRequestId = null
        state = State.FAILED
    }

    companion object {
        const val serviceId = 7
        const val propertyId = 2
    }
}

package com.velocimetro.scooterlab

/**
 * Reader for the device's CountryCode (region) property.
 *
 * This reader is a diagnostic tool to identify the VCU's configured region, which is
 * a primary hypothesis for the whole-scooter speed ceiling that cannot be explained by
 * the drive-mode limit (4.34).
 *
 * Protocol mapping derived from dashboard firmware (sec2_MCUAPP.bin):
 * - UART topic "CountryCode.get.vcu.comm" (handler 0x020DDA55, see FIRMWARE_ENCRYPTION_RESEARCH.md).
 * - The property ID is provisionally assigned as the next VCU comm property (11),
 *   following the pattern of driveSpeedLimit (2.10). It must be verified against live traffic
 *   before being treated as confirmed.
 */
class MiotScooterCountryCodeReader(
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
        if (state == State.WAITING_FLOW_ACK) state = State.WAITING_RESPONSE
        return pendingDataFrames
    }

    fun onInboundResponse(metadata: MiotInboundPayloadMetadata): Int? {
        if (state != State.WAITING_RESPONSE ||
            metadata.requestId != pendingRequestId ||
            metadata.operation !in setOf(1, 3) ||
            metadata.propertyCount != 1 ||
            metadata.serviceId != serviceId || metadata.propertyId != propertyId
        ) return null
        val countryCode = metadata.countryCodeValue ?: return null
        pendingDataFrames = emptyList()
        pendingRequestId = null
        state = State.COMPLETED
        return countryCode
    }

    fun cancel() {
        pendingDataFrames = emptyList()
        pendingRequestId = null
        state = State.FAILED
    }

    companion object {
        // Provisional MiOT property IDs for the VCU CountryCode, mirroring 2.10 structure.
        // Requires verification against live BLE traffic from Xiaomi Home or VCU response.
        const val serviceId = 2
        const val propertyId = 11
    }
}

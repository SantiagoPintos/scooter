package com.velocimetro.scooterlab

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pure codec for the MiOT BLE Spec v2 set-property payload used by the scooter.
 *
 * It operates entirely in memory. In particular, this class has no Bluetooth dependency and
 * does not transmit a frame. The Android laboratory UI supplies the transport separately.
 */
object MiotBleSpecV2Codec {
    private const val packetLengthFlag = 0x2000
    private const val setPropertyOpcode = 0
    private const val getPropertyOpcode = 2
    private const val boolValueType = 0
    private const val boolValueLength = 1
    private const val uint8ValueType = 1
    private const val uint8ValueLength = 1
    private const val scooterControlSiid = 4
    private const val scooterLockPiid = 6

    fun startupHeader(requestId: Int): ByteArray = header(requestId, 5)
        .put(startupOpcode.toByte())
        .array()

    fun getProperty(requestId: Int, serviceId: Int, propertyId: Int): ByteArray {
        require(serviceId in 0..255 && propertyId in 1..0xffff)
        return header(requestId, 9)
            .put(getPropertyOpcode.toByte())
            .put(1)
            .put(serviceId.toByte())
            .putShort(propertyId.toShort())
            .array()
    }

    /** Writes the observed four-byte startup value without carrying it beyond this session. */
    fun setStartupUint32Property(requestId: Int, value: Long): ByteArray {
        require(value in 0..0xffff_ffffL)
        return header(requestId, 15)
            .put(setPropertyOpcode.toByte())
            .put(1)
            .put(startupServiceId.toByte())
            .putShort(startupPropertyId.toShort())
            .putShort(startupUint32TypeAndLength.toShort())
            .putInt(value.toInt())
            .array()
    }

    /** Xiaomi Home publishes UTC seconds plus the phone's current UTC offset to 4.18. */
    fun phoneTimeSeconds(utcSeconds: Long, utcOffsetSeconds: Int): Long {
        val phoneTime = utcSeconds + utcOffsetSeconds
        require(phoneTime in 0..0xffff_ffffL)
        return phoneTime
    }

    /** Builds one boolean lock-property request with a caller-provided nonzero request id. */
    fun setScooterLock(requestId: Int, locked: Boolean): ByteArray {
        val packetLength = 12
        val typeAndLength = (boolValueType shl 12) or boolValueLength
        return header(requestId, packetLength)
            .put(setPropertyOpcode.toByte())
            .put(1.toByte()) // property count
            .put(scooterControlSiid.toByte())
            .putShort(scooterLockPiid.toShort())
            .putShort(typeAndLength.toShort())
            .put(if (locked) 1.toByte() else 0.toByte())
            .array()
    }

    private fun header(requestId: Int, length: Int): ByteBuffer {
        require(requestId in 1..0xffff) { "MiOT request id must be a nonzero unsigned short" }
        return ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
            .putShort((packetLengthFlag or length).toShort())
            .putShort(requestId.toShort())
    }

    private const val startupOpcode = 0xf0
    private const val startupServiceId = 4
    private const val startupPropertyId = 18
    private const val startupUint32TypeAndLength = 0x5004
}

/** Xiaomi Home's in-process MiOT request counter starts at one and increments before use. */
class MiotBleSpecRequestCounter(initialValue: Int = 1) {
    private var value = initialValue.also { require(it in 1 until 0xfffe) }

    fun next(): Int {
        if (value >= 0xfffe) value = 1
        value += 1
        return value
    }
}

/**
 * Session-local AES-CCM envelope for MiOT application messages.
 *
 * The session key is neither retained outside this object nor logged. Counters begin fresh for
 * every authenticated connection, just as the reference implementation resets them.
 */
class MiotBleApplicationCipher(sessionKey: ByteArray) {
    private val sessionKey = sessionKey.copyOf().also {
        require(it.size == sessionKeyLength) { "The session key must contain 64 bytes" }
    }
    private var outboundSequence = 0
    private var outboundEpoch = 0
    private var inboundSequence = 0
    private var inboundEpoch = 0

    /** Returns a per-session counter followed by AES-CCM ciphertext and its four-byte tag. */
    fun sealOutbound(plaintext: ByteArray): ByteArray {
        val sequence = outboundSequence
        val sealed = AesCcm.encrypt(
            key = sessionKey.copyOfRange(outboundKeyStart, outboundKeyEnd),
            nonce = nonce(sessionKey.copyOfRange(outboundNonceStart, outboundNonceEnd), sequence, outboundEpoch),
            plaintext = plaintext,
            tagLength = tagLength,
        )
        advanceOutboundCounter()
        return littleEndianShort(sequence) + sealed
    }

    /** Opens a response after the channel layer has reassembled its data fragments. */
    fun openInbound(ciphertext: ByteArray): ByteArray {
        require(ciphertext.size >= counterLength + tagLength) { "Application frame is too short" }
        val sequence = readLittleEndianShort(ciphertext, 0)
        val candidateEpoch = if (crossedNonceEpochBoundary(inboundSequence, sequence)) {
            (inboundEpoch + 1) and 0xffff
        } else {
            inboundEpoch
        }
        // Xiaomi Home advances the receive-side sequence before CCM authenticates the packet.
        // Some scooter deliveries share the channel but are not MiOT responses handled by this
        // app; they still define the epoch for the following valid delivery.
        inboundSequence = sequence
        inboundEpoch = candidateEpoch
        val plaintext = AesCcm.decrypt(
            key = sessionKey.copyOfRange(inboundKeyStart, inboundKeyEnd),
            nonce = nonce(sessionKey.copyOfRange(inboundNonceStart, inboundNonceEnd), sequence, candidateEpoch),
            ciphertextAndTag = ciphertext.copyOfRange(counterLength, ciphertext.size),
            tagLength = tagLength,
        )
        return plaintext
    }

    fun clear() {
        sessionKey.fill(0)
        outboundSequence = 0
        outboundEpoch = 0
        inboundSequence = 0
        inboundEpoch = 0
    }

    private fun advanceOutboundCounter() {
        val previous = outboundSequence
        outboundSequence = (outboundSequence + 1) and 0xffff
        if (crossedNonceEpochBoundary(previous, outboundSequence)) {
            outboundEpoch = (outboundEpoch + 1) and 0xffff
        }
    }

    /**
     * Xiaomi Home's reference counter treats the high bit as a nonce epoch boundary. The epoch
     * therefore changes at both 0x7fff -> 0x8000 and 0xffff -> 0x0000, rather than only at a
     * full unsigned-short rollover.
     */
    private fun crossedNonceEpochBoundary(previous: Int, next: Int): Boolean =
        (previous and 0x8000) != (next and 0x8000)

    private fun nonce(prefix: ByteArray, sequence: Int, epoch: Int): ByteArray {
        require(prefix.size == noncePrefixLength)
        return ByteArray(nonceLength).also { nonce ->
            prefix.copyInto(nonce, 0)
            littleEndianShort(sequence).copyInto(nonce, nonceCounterOffset)
            littleEndianShort(epoch).copyInto(nonce, nonceEpochOffset)
        }
    }

    private companion object {
        const val sessionKeyLength = 64
        const val outboundKeyStart = 16
        const val outboundKeyEnd = 32
        const val inboundKeyStart = 0
        const val inboundKeyEnd = 16
        const val inboundNonceStart = 32
        const val inboundNonceEnd = 36
        const val outboundNonceStart = 36
        const val outboundNonceEnd = 40
        const val noncePrefixLength = 4
        const val nonceLength = 12
        const val nonceCounterOffset = 8
        const val nonceEpochOffset = 10
        const val counterLength = 2
        const val tagLength = 4
    }
}

enum class MiotScooterCommandState {
    IDLE,
    WAITING_FLOW_ACK,
    WAITING_DATA_ACK,
    WAITING_RESPONSE,
    COMPLETED,
    FAILED,
}

enum class MiotScooterBatteryReadState {
    IDLE,
    WAITING_FLOW_ACK,
    WAITING_RESPONSE,
    COMPLETED,
    FAILED,
}

enum class MiotScooterPowerModeReadState {
    IDLE,
    WAITING_FLOW_ACK,
    WAITING_RESPONSE,
    COMPLETED,
    FAILED,
}

/**
 * Composes one read-only request for the scooter's reported battery percentage.
 *
 * Xiaomi Home's 6 Max battery panel reads MiOT service 3, property 19. Its returned structured
 * value contains the current percentage in its `cb` field. This transaction shares the
 * authenticated session's cipher and request counter, but never changes a scooter property or
 * performs a physical action.
 */
class MiotScooterBatteryReader(
    private val requestIds: MiotBleSpecRequestCounter,
    private val cipher: MiotBleApplicationCipher,
) {
    private var pendingDataFrames: List<ByteArray> = emptyList()
    private var pendingRequestId: Int? = null

    var state: MiotScooterBatteryReadState = MiotScooterBatteryReadState.IDLE
        private set

    /** Starts the read by announcing the following protected data frame. */
    fun begin(): List<ByteArray> {
        check(
            state == MiotScooterBatteryReadState.IDLE ||
                state == MiotScooterBatteryReadState.COMPLETED ||
                state == MiotScooterBatteryReadState.FAILED,
        ) { "A battery read is already in progress" }
        val requestId = requestIds.next()
        val sealed = cipher.sealOutbound(
            MiotBleSpecV2Codec.getProperty(requestId, batteryServiceId, batteryPropertyId),
        )
        val logicalFrames = sealed.asList().chunked(ScooterProtocol.defaultDataPayloadBytes)
            .mapIndexed { index, chunk -> ScooterChannelFraming.data(index + 1, chunk.toByteArray()) }
        pendingDataFrames = logicalFrames.flatMap { frame -> listOf(frame, frame.copyOf()) }
        pendingRequestId = requestId
        state = MiotScooterBatteryReadState.WAITING_FLOW_ACK
        return listOf(ScooterChannelFraming.flowControl(packetType, logicalFrames.size))
    }

    /** Moves the protected request forward when the scooter acknowledges its flow window. */
    fun onApplicationFrame(frame: ByteArray): List<ByteArray> {
        return try {
            val packet = ScooterChannelFraming.decode(frame)
            if (packet !is ScooterChannelPacket.Ack) {
                emptyList()
            } else when (state) {
                MiotScooterBatteryReadState.WAITING_FLOW_ACK -> {
                    require(packet.status == flowAccepted) { "Battery-read flow control was rejected" }
                    state = MiotScooterBatteryReadState.WAITING_RESPONSE
                    pendingDataFrames
                }
                // The scooter may omit this generic receipt. The matching MiOT response below is
                // authoritative; a successful generic receipt alone is never treated as battery data.
                MiotScooterBatteryReadState.WAITING_RESPONSE -> {
                    require(packet.status == dataAccepted || packet.status == flowAccepted) {
                        "Battery-read data was rejected"
                    }
                    emptyList()
                }
                else -> emptyList()
            }
        } catch (_: Exception) {
            fail()
            emptyList()
        }
    }

    /** Uses the observed no-flow-receipt path without turning the read into a physical command. */
    fun advanceWithoutFlowAcknowledgement(): List<ByteArray> {
        if (state != MiotScooterBatteryReadState.WAITING_FLOW_ACK) return emptyList()
        state = MiotScooterBatteryReadState.WAITING_RESPONSE
        return pendingDataFrames
    }

    /** Accepts only the matching MiOT get-property result and returns its extracted percentage. */
    fun onInboundResponse(metadata: MiotInboundPayloadMetadata): Int? {
        if (state != MiotScooterBatteryReadState.WAITING_RESPONSE) return null
        val percentage = metadata.batteryPercentValue
        if (
            metadata.requestId != pendingRequestId ||
            metadata.operation !in getPropertyResponseOperations ||
            metadata.propertyCount != propertyCount ||
            metadata.serviceId != batteryServiceId ||
            metadata.propertyId != batteryPropertyId ||
            percentage == null || percentage !in 0..100
        ) return null
        pendingDataFrames = emptyList()
        pendingRequestId = null
        state = MiotScooterBatteryReadState.COMPLETED
        return percentage
    }

    fun cancel() = fail()

    private fun fail() {
        pendingDataFrames = emptyList()
        pendingRequestId = null
        state = MiotScooterBatteryReadState.FAILED
    }

    companion object {
        const val packetType = 0
        const val flowAccepted = 1
        const val dataAccepted = 0
        val getPropertyResponseOperations = setOf(1, 3)
        const val propertyCount = 1
        const val batteryServiceId = 3
        const val batteryPropertyId = 19
    }
}

/** Reads Xiaomi Home's current riding-mode property without changing the scooter configuration. */
class MiotScooterPowerModeReader(
    private val requestIds: MiotBleSpecRequestCounter,
    private val cipher: MiotBleApplicationCipher,
) {
    private var pendingDataFrames: List<ByteArray> = emptyList()
    private var pendingRequestId: Int? = null

    var state: MiotScooterPowerModeReadState = MiotScooterPowerModeReadState.IDLE
        private set

    fun begin(): List<ByteArray> {
        check(
            state == MiotScooterPowerModeReadState.IDLE ||
                state == MiotScooterPowerModeReadState.COMPLETED ||
                state == MiotScooterPowerModeReadState.FAILED,
        ) { "A power-mode read is already in progress" }
        val requestId = requestIds.next()
        val sealed = cipher.sealOutbound(MiotBleSpecV2Codec.getProperty(requestId, workStateServiceId, powerModePropertyId))
        val logicalFrames = sealed.asList().chunked(ScooterProtocol.defaultDataPayloadBytes)
            .mapIndexed { index, chunk -> ScooterChannelFraming.data(index + 1, chunk.toByteArray()) }
        pendingDataFrames = logicalFrames.flatMap { frame -> listOf(frame, frame.copyOf()) }
        pendingRequestId = requestId
        state = MiotScooterPowerModeReadState.WAITING_FLOW_ACK
        return listOf(ScooterChannelFraming.flowControl(packetType, logicalFrames.size))
    }

    fun onApplicationFrame(frame: ByteArray): List<ByteArray> = try {
        val packet = ScooterChannelFraming.decode(frame)
        if (packet !is ScooterChannelPacket.Ack) emptyList() else when (state) {
            MiotScooterPowerModeReadState.WAITING_FLOW_ACK -> {
                require(packet.status == flowAccepted) { "Power-mode flow control was rejected" }
                state = MiotScooterPowerModeReadState.WAITING_RESPONSE
                pendingDataFrames
            }
            MiotScooterPowerModeReadState.WAITING_RESPONSE -> {
                require(packet.status == dataAccepted || packet.status == flowAccepted) {
                    "Power-mode data was rejected"
                }
                emptyList()
            }
            else -> emptyList()
        }
    } catch (_: Exception) {
        fail()
        emptyList()
    }

    fun advanceWithoutFlowAcknowledgement(): List<ByteArray> {
        if (state != MiotScooterPowerModeReadState.WAITING_FLOW_ACK) return emptyList()
        state = MiotScooterPowerModeReadState.WAITING_RESPONSE
        return pendingDataFrames
    }

    fun onInboundResponse(metadata: MiotInboundPayloadMetadata): Int? {
        if (state != MiotScooterPowerModeReadState.WAITING_RESPONSE) return null
        val mode = metadata.powerModeValue
        if (
            metadata.requestId != pendingRequestId ||
            metadata.operation !in getPropertyResponseOperations ||
            metadata.propertyCount != propertyCount ||
            metadata.serviceId != workStateServiceId ||
            metadata.propertyId != powerModePropertyId ||
            mode == null || mode !in supportedModes
        ) return null
        pendingDataFrames = emptyList()
        pendingRequestId = null
        state = MiotScooterPowerModeReadState.COMPLETED
        return mode
    }

    fun cancel() = fail()

    private fun fail() {
        pendingDataFrames = emptyList()
        pendingRequestId = null
        state = MiotScooterPowerModeReadState.FAILED
    }

    companion object {
        const val packetType = 0
        const val flowAccepted = 1
        const val dataAccepted = 0
        val getPropertyResponseOperations = setOf(1, 3)
        const val propertyCount = 1
        const val workStateServiceId = 2
        const val powerModePropertyId = 1
        val supportedModes = 1..4
    }
}

/**
 * Composes, but never sends, one lock-state transaction through the application channel.
 *
 * The flow-control frame is acknowledged before its declared data frames are transmitted. This
 * mirrors the channel state machine used by Xiaomi Home and prevents a protected payload from
 * arriving before the scooter has opened its receive window.
 */
class MiotScooterCommandComposer private constructor(
    private val requestIds: MiotBleSpecRequestCounter,
    private val cipher: MiotBleApplicationCipher,
) {
    constructor(sessionKey: ByteArray) : this(MiotBleSpecRequestCounter(), MiotBleApplicationCipher(sessionKey))

    private var pendingDataFrames: List<ByteArray> = emptyList()
    private var pendingLogicalFrameCount = 0
    private var pendingRequestId: Int? = null
    private var pendingServiceId: Int? = null
    private var pendingPropertyId: Int? = null

    var state: MiotScooterCommandState = MiotScooterCommandState.IDLE
        private set

    /** Starts a transaction by returning only its flow-control frame. */
    fun beginLock(locked: Boolean): List<ByteArray> {
        check(
            state == MiotScooterCommandState.IDLE || state == MiotScooterCommandState.COMPLETED ||
                state == MiotScooterCommandState.FAILED,
        ) { "An application command is already in progress" }
        val requestId = requestIds.next()
        val command = MiotBleSpecV2Codec.setScooterLock(requestId, locked)
        return beginPropertyWrite(requestId, MiotScooterCommandComposer.lockServiceId,
            MiotScooterCommandComposer.lockPropertyId, command)
    }

    private fun beginPropertyWrite(
        requestId: Int,
        serviceId: Int,
        propertyId: Int,
        command: ByteArray,
    ): List<ByteArray> {
        val sealed = cipher.sealOutbound(command)
        val chunks = sealed.asList().chunked(ScooterProtocol.defaultDataPayloadBytes)
            .map { it.toByteArray() }
        val logicalFrames = chunks.mapIndexed { index, chunk ->
            ScooterChannelFraming.data(index + 1, chunk)
        }
        pendingLogicalFrameCount = logicalFrames.size
        pendingDataFrames = logicalFrames.flatMap { frame -> listOf(frame, frame.copyOf()) }
        pendingRequestId = requestId
        pendingServiceId = serviceId
        pendingPropertyId = propertyId
        state = MiotScooterCommandState.WAITING_FLOW_ACK
        return listOf(ScooterChannelFraming.flowControl(setPropertyPacketType, pendingLogicalFrameCount))
    }

    /** Consumes a channel acknowledgement and returns any next data frames to be sent. */
    fun onApplicationFrame(frame: ByteArray): List<ByteArray> = try {
        val packet = ScooterChannelFraming.decode(frame)
        if (packet !is ScooterChannelPacket.Ack) {
            emptyList()
        } else when (state) {
            MiotScooterCommandState.WAITING_FLOW_ACK -> {
                require(packet.status == flowAccepted) { "Application flow-control was rejected" }
                state = MiotScooterCommandState.WAITING_DATA_ACK
                pendingDataFrames
            }
            MiotScooterCommandState.WAITING_DATA_ACK -> {
                if (packet.status == dataAccepted) {
                    pendingDataFrames = emptyList()
                    state = MiotScooterCommandState.WAITING_RESPONSE
                } else {
                    require(packet.status == flowAccepted) { "Application data was rejected" }
                }
                emptyList()
            }
            else -> emptyList()
        }
    } catch (_: Exception) {
        pendingDataFrames = emptyList()
        pendingLogicalFrameCount = 0
        state = MiotScooterCommandState.FAILED
        emptyList()
    }

    /**
     * The 6 Max returns the outcome of a property write as a protected MiOT response rather
     * than as the generic data-channel receipt. Accept it only while its matching command is
     * pending, and only for the property being written; unrelated telemetry must never complete
     * a command.
     */
    fun onInboundResponse(metadata: MiotInboundPayloadMetadata): Boolean {
        if (state != MiotScooterCommandState.WAITING_DATA_ACK &&
            state != MiotScooterCommandState.WAITING_RESPONSE
        ) return false
        if (
            metadata.requestId != pendingRequestId ||
            metadata.operation != responseOperation ||
            metadata.propertyCount != propertyCount ||
            metadata.serviceId != pendingServiceId ||
            metadata.propertyId != pendingPropertyId
        ) return false
        pendingDataFrames = emptyList()
        pendingLogicalFrameCount = 0
        pendingRequestId = null
        pendingServiceId = null
        pendingPropertyId = null
        state = MiotScooterCommandState.COMPLETED
        return true
    }

    /**
     * This scooter variant omits the generic channel's flow receipt. The reference trace shows
     * the data frame following a short write window instead. Call only after that window, and
     * only while an explicit receipt has not already advanced the transaction.
     */
    fun advanceWithoutFlowAcknowledgement(): List<ByteArray> {
        if (state != MiotScooterCommandState.WAITING_FLOW_ACK) return emptyList()
        state = MiotScooterCommandState.WAITING_DATA_ACK
        return pendingDataFrames
    }

    fun abort() {
        pendingDataFrames = emptyList()
        pendingRequestId = null
        pendingServiceId = null
        pendingPropertyId = null
        state = MiotScooterCommandState.FAILED
    }

    fun clear() {
        abort()
        cipher.clear()
    }

    companion object {
        /** Uses the same session-local counter and cipher that completed startup. */
        fun forInitializedSession(
            requestIds: MiotBleSpecRequestCounter,
            cipher: MiotBleApplicationCipher,
        ): MiotScooterCommandComposer = MiotScooterCommandComposer(requestIds, cipher)

        const val setPropertyPacketType = 0
        const val flowAccepted = 1
        const val dataAccepted = 0
        const val responseOperation = 1
        const val propertyCount = 1
        const val lockServiceId = 4
        const val lockPropertyId = 6
    }
}

enum class MiotScooterInitializationState {
    IDLE,
    WAITING_FLOW_ACK,
    WAITING_DATA_ACK,
    COMPLETED,
    FAILED,
}

/** Performs Xiaomi Home's observed non-physical MiOT startup exchange for this session. */
class MiotScooterApplicationInitializer(
    private val requestIds: MiotBleSpecRequestCounter,
    private val cipher: MiotBleApplicationCipher,
) {
    var state: MiotScooterInitializationState = MiotScooterInitializationState.IDLE
        private set

    private var pendingGroups: List<List<ByteArray>> = emptyList()
    private var groupIndex = 0

    /**
     * Consumes the scooter's encrypted startup delivery before outbound MiOT traffic begins.
     * Only the fixed MiOT envelope is classified; payload fields are never retained. Opening it
     * advances the session's inbound nonce state in the same way as Xiaomi Home.
     */
    fun consumeInitialPayload(payload: ByteArray): MiotInboundPayloadMetadata? = try {
        MiotScooterInboundResponseConsumer(cipher).consume(payload)
    } catch (_: Exception) {
        null
    }

    /**
     * Emits the complete observed Xiaomi Home startup burst. Application flow control announces
     * its following data frame; it does not wait for a separate receipt before that data is sent.
     */
    fun begin(phoneTimeSeconds: Long): List<ByteArray> {
        check(state == MiotScooterInitializationState.IDLE) { "Application initialization already started" }
        // The working Xiaomi Home session opens with these four messages. The two consecutive
        // reads are intentionally identical: Xiaomi Home sends both and they consume distinct
        // session cipher counters before the clock update and any user command. Each data frame
        // is duplicated by outboundFrames(), as observed on the application write characteristic.
        val plaintextMessages = listOf(
            MiotBleSpecV2Codec.startupHeader(requestIds.next()),
            MiotBleSpecV2Codec.getProperty(requestIds.next(), startupReadServiceId, startupReadPropertyId),
            MiotBleSpecV2Codec.getProperty(requestIds.next(), startupReadServiceId, startupReadPropertyId),
            MiotBleSpecV2Codec.setStartupUint32Property(requestIds.next(), phoneTimeSeconds),
        )
        pendingGroups = plaintextMessages.map { plaintext -> outboundFrames(cipher.sealOutbound(plaintext)) }
        groupIndex = 0
        state = MiotScooterInitializationState.WAITING_FLOW_ACK
        return listOf(pendingGroups.first().first())
    }

    /** Advances one observed startup exchange only after the scooter's matching channel receipt. */
    fun onApplicationFrame(frame: ByteArray): List<ByteArray> = try {
        val packet = ScooterChannelFraming.decode(frame)
        if (packet !is ScooterChannelPacket.Ack) {
            emptyList()
        } else when (state) {
            MiotScooterInitializationState.WAITING_FLOW_ACK -> {
                require(packet.status == flowAccepted) { "MiOT startup flow-control was rejected" }
                state = MiotScooterInitializationState.WAITING_DATA_ACK
                pendingGroups[groupIndex].drop(1)
            }
            MiotScooterInitializationState.WAITING_DATA_ACK -> {
                require(packet.status == dataAccepted) { "MiOT startup data was rejected" }
                groupIndex += 1
                if (groupIndex == pendingGroups.size) {
                    pendingGroups = emptyList()
                    state = MiotScooterInitializationState.COMPLETED
                    emptyList()
                } else {
                    state = MiotScooterInitializationState.WAITING_FLOW_ACK
                    listOf(pendingGroups[groupIndex].first())
                }
            }
            else -> emptyList()
        }
    } catch (_: Exception) {
        pendingGroups = emptyList()
        state = MiotScooterInitializationState.FAILED
        emptyList()
    }

    /** Mirrors the scooter's observed no-receipt startup path after the short flow window. */
    fun advanceWithoutFlowAcknowledgement(): List<ByteArray> {
        if (state != MiotScooterInitializationState.WAITING_FLOW_ACK) return emptyList()
        state = MiotScooterInitializationState.WAITING_DATA_ACK
        return pendingGroups[groupIndex].drop(1)
    }

    /** Advances the observed startup burst when this scooter omits its generic data receipt. */
    fun advanceWithoutDataAcknowledgement(): List<ByteArray> {
        if (state != MiotScooterInitializationState.WAITING_DATA_ACK) return emptyList()
        groupIndex += 1
        if (groupIndex == pendingGroups.size) {
            pendingGroups = emptyList()
            state = MiotScooterInitializationState.COMPLETED
            return emptyList()
        }
        state = MiotScooterInitializationState.WAITING_FLOW_ACK
        return listOf(pendingGroups[groupIndex].first())
    }

    private fun outboundFrames(sealed: ByteArray): List<ByteArray> {
        val dataFrames = sealed.asList().chunked(ScooterProtocol.defaultDataPayloadBytes)
            .mapIndexed { index, chunk -> ScooterChannelFraming.data(index + 1, chunk.toByteArray()) }
        // Xiaomi Home sends each application data fragment twice. The duplicate is part of the
        // device-specific reliability behavior, not a retry after an error.
        val duplicatedDataFrames = dataFrames.flatMap { frame -> listOf(frame, frame.copyOf()) }
        return listOf(ScooterChannelFraming.flowControl(packetType, dataFrames.size)) + duplicatedDataFrames
    }

    private companion object {
        const val packetType = 0
        const val flowAccepted = 1
        const val dataAccepted = 0
        const val startupReadServiceId = 2
        const val startupReadPropertyId = 10
    }
}

/** Opens incoming MiOT responses while retaining only the minimum typed field needed for telemetry. */
class MiotScooterInboundResponseConsumer(private val cipher: MiotBleApplicationCipher) {
    fun consume(payload: ByteArray): MiotInboundPayloadMetadata? = try {
        val plaintext = cipher.openInbound(payload)
        val serviceId = plaintext.getOrNull(6)?.toInt()?.and(0xff)
        val propertyId = plaintext.takeIf { it.size >= 9 }?.let { readLittleEndianShort(it, 7) }
        val metadata = MiotInboundPayloadMetadata(
            plaintextLength = plaintext.size,
            declaredLength = plaintext.takeIf { it.size >= 2 }?.let { readLittleEndianShort(it, 0) and 0x0fff },
            requestId = plaintext.takeIf { it.size >= 4 }?.let { readLittleEndianShort(it, 2) },
            operation = plaintext.getOrNull(4)?.toInt()?.and(0xff),
            propertyCount = plaintext.getOrNull(5)?.toInt()?.and(0xff),
            serviceId = serviceId,
            propertyId = propertyId,
            valueTypeAndLength = plaintext.takeIf { it.size >= 11 }?.let { readLittleEndianShort(it, 9) },
            powerModeValue = extractPowerModeValue(plaintext, serviceId, propertyId),
            batteryPercentValue = extractBatteryPercentage(plaintext, serviceId, propertyId),
        )
        plaintext.fill(0)
        metadata
    } catch (_: Exception) {
        null
    }
}

/**
 * Envelope metadata plus telemetry scalars extracted only for explicitly requested properties.
 * Callers must not log a scalar or expose it as a value for any unrelated property.
 */
data class MiotInboundPayloadMetadata(
    val plaintextLength: Int,
    val declaredLength: Int?,
    val requestId: Int?,
    val operation: Int?,
    val propertyCount: Int?,
    val serviceId: Int?,
    val propertyId: Int?,
    val valueTypeAndLength: Int?,
    val powerModeValue: Int? = null,
    val batteryPercentValue: Int? = null,
)

/**
 * A successful short MiOT property response carries a two-byte result/type prefix
 * before the value. `power-mode` is an INT8, so its value is the final byte.
 */
private fun extractPowerModeValue(plaintext: ByteArray, serviceId: Int?, propertyId: Int?): Int? {
    if (serviceId != MiotScooterPowerModeReader.workStateServiceId ||
        propertyId != MiotScooterPowerModeReader.powerModePropertyId ||
        plaintext.size != 14
    ) return null
    return plaintext.last().toInt().and(0xff)
}

/** Extracts only `cb` from Xiaomi Home's battery-information JSON without retaining its contents. */
private fun extractBatteryPercentage(plaintext: ByteArray, serviceId: Int?, propertyId: Int?): Int? {
    if (serviceId != MiotScooterBatteryReader.batteryServiceId ||
        propertyId != MiotScooterBatteryReader.batteryPropertyId
    ) return null
    val key = byteArrayOf('"'.code.toByte(), 'c'.code.toByte(), 'b'.code.toByte(), '"'.code.toByte())
    val valueStart = 11
    val lastIndex = plaintext.lastIndex
    for (index in valueStart..(lastIndex - key.size + 1)) {
        if (!key.indices.all { offset -> plaintext[index + offset] == key[offset] }) continue
        var cursor = index + key.size
        while (cursor <= lastIndex && plaintext[cursor].toInt().toChar().isWhitespace()) cursor += 1
        if (cursor > lastIndex || plaintext[cursor] != ':'.code.toByte()) continue
        cursor += 1
        while (cursor <= lastIndex && plaintext[cursor].toInt().toChar().isWhitespace()) cursor += 1
        var value = 0
        var digitCount = 0
        while (cursor <= lastIndex && plaintext[cursor] in '0'.code.toByte()..'9'.code.toByte()) {
            value = value * 10 + (plaintext[cursor].toInt() - '0'.code)
            digitCount += 1
            cursor += 1
            if (value > 100) return null
        }
        return value.takeIf { digitCount > 0 }
    }
    return null
}

private fun littleEndianShort(value: Int): ByteArray = byteArrayOf(
    (value and 0xff).toByte(),
    ((value ushr 8) and 0xff).toByte(),
)

private fun readLittleEndianShort(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

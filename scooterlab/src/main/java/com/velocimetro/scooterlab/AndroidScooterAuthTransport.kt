package com.velocimetro.scooterlab

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.UUID

/** Lifecycle of the auth-only GATT transport. No application command path is present here. */
enum class ScooterAuthTransportState {
    IDLE,
    CONNECTING,
    NEGOTIATING_MTU,
    DISCOVERING,
    SUBSCRIBING,
    READY,
    CLOSED,
    FAILED,
}

interface ScooterAuthTransportListener {
    fun onStateChanged(state: ScooterAuthTransportState)
    fun onAuthenticationFrame(frame: ByteArray)
    fun onSessionStatusFrame(frame: ByteArray)
    fun onApplicationFrame(frame: ByteArray) {}
    fun onApplicationResponseChannelReady() {}
    fun onTransportFailure(reason: String)
}

/**
 * Android adapter for the fixed authentication path. The caller supplies the device, starts the
 * connection explicitly, and decides which security-channel frames to send. It uses only the
 * capability, session-status, and authentication characteristics; frames are never logged.
 */
class AndroidScooterAuthTransport(
    private val context: Context,
    private val device: BluetoothDevice,
    private val listener: ScooterAuthTransportListener,
) {
    private var gatt: BluetoothGatt? = null
    private var capabilityCharacteristic: BluetoothGattCharacteristic? = null
    private var authCharacteristic: BluetoothGattCharacteristic? = null
    private var sessionStatusCharacteristic: BluetoothGattCharacteristic? = null
    private var applicationWriteCharacteristic: BluetoothGattCharacteristic? = null
    private var applicationResponseCharacteristic: BluetoothGattCharacteristic? = null
    private val pendingSubscriptions = ArrayDeque<BluetoothGattCharacteristic>()
    private var subscriptionInFlight: BluetoothGattCharacteristic? = null
    private var awaitingBootstrap = false
    private var bootstrapComplete = false
    private var bootstrapAcknowledgementPending = false
    private val queuedAuthenticationFrames = ArrayDeque<ByteArray>()
    private var authenticationWriteScheduled = false
    private val queuedApplicationFrames = ArrayDeque<QueuedApplicationFrame>()
    private var applicationWriteScheduled = false
    private var applicationResponsesEnabled = false
    private val pendingApplicationNotificationUuids = linkedSetOf<UUID>()
    private var state = ScooterAuthTransportState.IDLE

    @SuppressLint("MissingPermission")
    fun connect() {
        check(state == ScooterAuthTransportState.IDLE || state == ScooterAuthTransportState.CLOSED) {
            "Transport cannot connect from $state"
        }
        transition(ScooterAuthTransportState.CONNECTING)
        val connectedGatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        if (connectedGatt == null) {
            fail("Android did not create a GATT connection")
        } else {
            gatt = connectedGatt
        }
    }

    /** Writes an already-framed authentication packet with write-without-response semantics. */
    @SuppressLint("MissingPermission", "DEPRECATION")
    fun writeAuthenticationFrame(frame: ByteArray) {
        check(state == ScooterAuthTransportState.READY) { "Authentication channel is not ready" }
        queuedAuthenticationFrames += frame.copyOf()
        scheduleAuthenticationWrite()
    }

    /** Enables both halves of Xiaomi's Spec v2 application channel without sending a command. */
    @SuppressLint("MissingPermission")
    fun enableApplicationResponses(): Boolean {
        check(state == ScooterAuthTransportState.READY) { "GATT transport is not ready" }
        if (applicationResponsesEnabled) return true
        val activeGatt = gatt ?: return false
        val responseCharacteristic = applicationResponseCharacteristic ?: return false
        val dataCharacteristic = applicationWriteCharacteristic ?: return false
        pendingApplicationNotificationUuids += responseCharacteristic.uuid
        pendingApplicationNotificationUuids += dataCharacteristic.uuid
        pendingSubscriptions += responseCharacteristic
        if (dataCharacteristic.uuid != responseCharacteristic.uuid) pendingSubscriptions += dataCharacteristic
        subscribeNext(activeGatt)
        return true
    }

    /**
     * Writes pre-framed application data only when an explicit caller invokes it. It is not used
     * by the authentication flow or the diagnostic UI.
     */
    @SuppressLint("MissingPermission")
    fun writeApplicationFrames(
        frames: List<ByteArray>,
        onAccepted: (() -> Unit)? = null,
    ) {
        queueApplicationFrames(
            characteristic = requireNotNull(applicationWriteCharacteristic) { "Application characteristic is unavailable" },
            frames = frames,
            onAccepted = onAccepted,
        )
    }

    /** Sends a Spec v2 channel acknowledgement on 001B, as Xiaomi Home does. */
    @SuppressLint("MissingPermission")
    fun writeApplicationResponseFrames(
        frames: List<ByteArray>,
        onAccepted: (() -> Unit)? = null,
    ) {
        queueApplicationFrames(
            characteristic = requireNotNull(applicationResponseCharacteristic) { "Application response characteristic is unavailable" },
            frames = frames,
            onAccepted = onAccepted,
        )
    }

    private fun queueApplicationFrames(
        characteristic: BluetoothGattCharacteristic,
        frames: List<ByteArray>,
        onAccepted: (() -> Unit)? = null,
    ) {
        check(state == ScooterAuthTransportState.READY) { "GATT transport is not ready" }
        require(frames.isNotEmpty()) { "At least one application frame is required" }
        frames.forEachIndexed { index, frame ->
            queuedApplicationFrames += QueuedApplicationFrame(
                characteristic = characteristic,
                frame = frame.copyOf(),
                onAccepted = if (index == frames.lastIndex) onAccepted else null,
            )
        }
        scheduleApplicationWrite()
    }

    @SuppressLint("MissingPermission", "DEPRECATION")
    private fun writeAuthenticationFrameInternal(frame: ByteArray): Boolean =
        writeWithoutResponse(requireNotNull(authCharacteristic) { "Authentication characteristic is unavailable" }, frame)

    @SuppressLint("MissingPermission", "DEPRECATION")
    private fun writeSessionControl(frame: ByteArray): Boolean =
        writeWithoutResponse(requireNotNull(sessionStatusCharacteristic) { "Session-status characteristic is unavailable" }, frame)

    @SuppressLint("MissingPermission", "DEPRECATION")
    private fun writeApplicationFrameInternal(
        characteristic: BluetoothGattCharacteristic,
        frame: ByteArray,
    ): Boolean = writeWithoutResponse(characteristic, frame)

    @SuppressLint("MissingPermission", "DEPRECATION")
    private fun writeWithoutResponse(
        characteristic: BluetoothGattCharacteristic,
        frame: ByteArray,
    ): Boolean {
        val activeGatt = requireNotNull(gatt) { "GATT connection is unavailable" }
        val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activeGatt.writeCharacteristic(
                characteristic,
                frame.copyOf(),
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
            ) == android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            characteristic.value = frame.copyOf()
            activeGatt.writeCharacteristic(characteristic)
        }
        if (!accepted) fail("Android rejected an authentication setup frame before transmission")
        return accepted
    }

    @SuppressLint("MissingPermission")
    fun close() {
        val activeGatt = gatt
        gatt = null
        capabilityCharacteristic = null
        authCharacteristic = null
        sessionStatusCharacteristic = null
        applicationWriteCharacteristic = null
        applicationResponseCharacteristic = null
        pendingSubscriptions.clear()
        subscriptionInFlight = null
        awaitingBootstrap = false
        bootstrapComplete = false
        bootstrapAcknowledgementPending = false
        queuedAuthenticationFrames.clear()
        authenticationWriteScheduled = false
        queuedApplicationFrames.clear()
        applicationWriteScheduled = false
        applicationResponsesEnabled = false
        pendingApplicationNotificationUuids.clear()
        activeGatt?.disconnect()
        activeGatt?.close()
        if (state != ScooterAuthTransportState.FAILED) transition(ScooterAuthTransportState.CLOSED)
    }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("GATT connection failed with status $status")
                return
            }
            if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                transition(ScooterAuthTransportState.NEGOTIATING_MTU)
                if (!gatt.requestMtu(ScooterProtocol.requiredAttMtu)) {
                    fail("Android could not request the required authentication MTU")
                }
            } else if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED && state != ScooterAuthTransportState.CLOSED) {
                fail("GATT disconnected during authentication setup")
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("MTU negotiation failed with status $status")
                return
            }
            if (mtu < ScooterProtocol.requiredAttMtu) {
                fail("Scooter authentication requires MTU ${ScooterProtocol.requiredAttMtu}, got $mtu")
                return
            }
            transition(ScooterAuthTransportState.DISCOVERING)
            if (!gatt.discoverServices()) fail("Android could not start GATT service discovery")
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("GATT service discovery failed with status $status")
                return
            }
            val service = gatt.getService(ScooterProtocol.serviceUuid)
            val capability = service?.getCharacteristic(ScooterProtocol.capabilityUuid)
            val authentication = service?.getCharacteristic(ScooterProtocol.authenticationUuid)
            val sessionStatus = service?.getCharacteristic(ScooterProtocol.sessionStatusUuid)
            if (capability == null || authentication == null || sessionStatus == null) {
                fail("A required scooter authentication characteristic is unavailable")
                return
            }
            capabilityCharacteristic = capability
            authCharacteristic = authentication
            sessionStatusCharacteristic = sessionStatus
            applicationWriteCharacteristic = service.getCharacteristic(ScooterProtocol.applicationWriteUuid)
            applicationResponseCharacteristic = service.getCharacteristic(ScooterProtocol.applicationResponseUuid)
            transition(ScooterAuthTransportState.SUBSCRIBING)
            pendingSubscriptions += authentication
            subscribeNext(gatt)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val subscribed = subscriptionInFlight ?: return
            subscriptionInFlight = null
            if (descriptor.uuid != clientCharacteristicConfigurationUuid) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("Notification subscription failed with status $status")
                return
            }
            when (subscribed.uuid) {
                ScooterProtocol.authenticationUuid -> readCapability(gatt)
                ScooterProtocol.sessionStatusUuid -> {
                    if (writeSessionControl(ScooterProtocol.sessionStart)) {
                        scheduleReadyAfterSessionStart(gatt)
                    }
                }
                ScooterProtocol.applicationResponseUuid,
                ScooterProtocol.applicationWriteUuid -> {
                    pendingApplicationNotificationUuids.remove(subscribed.uuid)
                    if (pendingApplicationNotificationUuids.isEmpty()) {
                        applicationResponsesEnabled = true
                        listener.onApplicationResponseChannelReady()
                    } else {
                        subscribeNext(gatt)
                    }
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (characteristic.uuid != ScooterProtocol.capabilityUuid) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("Scooter capability read failed with status $status")
                return
            }
            awaitingBootstrap = true
            writeSessionControl(ScooterProtocol.bootstrapStart)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == ScooterProtocol.authenticationUuid) {
                handleAuthenticationFrame(gatt, characteristic.value?.copyOf() ?: return)
            } else if (characteristic.uuid == ScooterProtocol.sessionStatusUuid) {
                listener.onSessionStatusFrame(characteristic.value?.copyOf() ?: return)
            // Spec v2 is a directional pair. 001B is the peer-to-client return channel; 001A
            // also notifies after it is enabled, but those frames include the local outbound
            // stream and must not be fed back into the application's inbound state machine.
            } else if (characteristic.uuid == ScooterProtocol.applicationResponseUuid) {
                listener.onApplicationFrame(characteristic.value?.copyOf() ?: return)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            when (characteristic.uuid) {
                ScooterProtocol.authenticationUuid -> handleAuthenticationFrame(gatt, value.copyOf())
                ScooterProtocol.sessionStatusUuid -> listener.onSessionStatusFrame(value.copyOf())
                // Keep 001A notifications enabled for the complete Spec v2 setup, while only
                // routing the reciprocal 001B channel to the inbound protocol consumer.
                ScooterProtocol.applicationResponseUuid -> listener.onApplicationFrame(value.copyOf())
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun subscribeNext(gatt: BluetoothGatt) {
        val characteristic = pendingSubscriptions.removeFirstOrNull()
            ?: run {
                return
            }
        subscriptionInFlight = characteristic
        if (!gatt.setCharacteristicNotification(characteristic, true)) {
            fail("Android could not enable a required notification")
            return
        }
        val descriptor = characteristic.getDescriptor(clientCharacteristicConfigurationUuid)
        if (descriptor == null) {
            fail("A required notification descriptor is unavailable")
            return
        }
        val supportsNotifications =
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
        val supportsIndications =
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
        // Both Spec v2 characteristics use notifications. Xiaomi Home enables 001B first, then
        // 001A, and exchanges channel receipts across the pair.
        val configuration = when {
            (characteristic.uuid == ScooterProtocol.applicationResponseUuid ||
                characteristic.uuid == ScooterProtocol.applicationWriteUuid) && supportsNotifications ->
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            supportsIndications -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            supportsNotifications -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else -> {
                fail("A required response characteristic supports neither notifications nor indications")
                return
            }
        }
        if (!writeClientConfiguration(gatt, descriptor, configuration)) {
            subscriptionInFlight = null
            fail("Android rejected an authentication-channel subscription")
        }
    }

    private fun handleAuthenticationFrame(gatt: BluetoothGatt, frame: ByteArray) {
        val acknowledgement = ScooterBootstrap.acknowledgement(frame)
        if (acknowledgement == null || !awaitingBootstrap || bootstrapComplete) {
            listener.onAuthenticationFrame(frame)
            return
        }
        scheduleBootstrapAcknowledgement(gatt, acknowledgement, ScooterBootstrap.completesBootstrap(frame))
    }

    private fun scheduleBootstrapAcknowledgement(
        activeGatt: BluetoothGatt,
        acknowledgement: ByteArray,
        completesBootstrap: Boolean,
    ) {
        if (bootstrapAcknowledgementPending) return
        bootstrapAcknowledgementPending = true
        Handler(Looper.getMainLooper()).postDelayed({
            bootstrapAcknowledgementPending = false
            if (gatt !== activeGatt || state != ScooterAuthTransportState.SUBSCRIBING) return@postDelayed
            if (!writeAuthenticationFrameInternal(acknowledgement)) return@postDelayed
            if (completesBootstrap) {
                bootstrapComplete = true
                awaitingBootstrap = false
                scheduleSessionStatusSubscription(activeGatt)
            }
        }, gattOperationDelayMillis)
    }

    private fun scheduleAuthenticationWrite() {
        if (authenticationWriteScheduled) return
        authenticationWriteScheduled = true
        Handler(Looper.getMainLooper()).postDelayed({
            authenticationWriteScheduled = false
            if (state != ScooterAuthTransportState.READY || gatt == null) {
                queuedAuthenticationFrames.clear()
                return@postDelayed
            }
            val frame = queuedAuthenticationFrames.removeFirstOrNull() ?: return@postDelayed
            if (writeAuthenticationFrameInternal(frame) && queuedAuthenticationFrames.isNotEmpty()) {
                scheduleAuthenticationWrite()
            }
        }, gattOperationDelayMillis)
    }

    private fun scheduleApplicationWrite() {
        if (applicationWriteScheduled) return
        applicationWriteScheduled = true
        Handler(Looper.getMainLooper()).postDelayed({
            applicationWriteScheduled = false
            if (state != ScooterAuthTransportState.READY || gatt == null) {
                queuedApplicationFrames.clear()
                return@postDelayed
            }
            val queuedFrame = queuedApplicationFrames.removeFirstOrNull() ?: return@postDelayed
            if (writeApplicationFrameInternal(queuedFrame.characteristic, queuedFrame.frame)) {
                queuedFrame.onAccepted?.invoke()
            }
            if (queuedApplicationFrames.isNotEmpty()) {
                scheduleApplicationWrite()
            }
        }, applicationFrameIntervalMillis)
    }

    private data class QueuedApplicationFrame(
        val characteristic: BluetoothGattCharacteristic,
        val frame: ByteArray,
        val onAccepted: (() -> Unit)? = null,
    )

    private fun scheduleSessionStatusSubscription(activeGatt: BluetoothGatt) {
        Handler(Looper.getMainLooper()).postDelayed({
            if (gatt !== activeGatt || state != ScooterAuthTransportState.SUBSCRIBING) return@postDelayed
            pendingSubscriptions += requireNotNull(sessionStatusCharacteristic)
            subscribeNext(activeGatt)
        }, gattOperationDelayMillis)
    }

    private fun scheduleReadyAfterSessionStart(activeGatt: BluetoothGatt) {
        Handler(Looper.getMainLooper()).postDelayed({
            if (gatt === activeGatt && state == ScooterAuthTransportState.SUBSCRIBING) {
                transition(ScooterAuthTransportState.READY)
            }
        }, gattOperationDelayMillis)
    }

    @SuppressLint("MissingPermission")
    private fun readCapability(gatt: BluetoothGatt) {
        val capability = requireNotNull(capabilityCharacteristic)
        if (!gatt.readCharacteristic(capability)) {
            fail("Android could not read the scooter capability characteristic")
        }
    }

    @SuppressLint("MissingPermission", "DEPRECATION")
    private fun writeClientConfiguration(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        configuration: ByteArray,
    ): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, configuration) ==
                android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            descriptor.value = configuration
            gatt.writeDescriptor(descriptor)
        }

    private fun transition(next: ScooterAuthTransportState) {
        state = next
        listener.onStateChanged(next)
    }

    private fun fail(reason: String) {
        transition(ScooterAuthTransportState.FAILED)
        listener.onTransportFailure(reason)
    }

    private companion object {
        const val gattOperationDelayMillis = 100L
        // Xiaomi Home routes 001A fragments through writeBatchNoRsp. Android's public GATT API
        // has no equivalent batch call, so retain the order but use the observed short cadence
        // for application frames only. Authentication continues to use the conservative delay.
        const val applicationFrameIntervalMillis = 20L
        val clientCharacteristicConfigurationUuid: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}

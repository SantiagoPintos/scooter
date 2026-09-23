package com.velocimetro.scooterlabapp

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.velocimetro.scooterlab.AndroidScooterAuthTransport
import com.velocimetro.scooterlab.ScooterAuthTransportListener
import com.velocimetro.scooterlab.ScooterAuthTransportState
import com.velocimetro.scooterlab.ScooterAuthenticationController
import com.velocimetro.scooterlab.ScooterHandshakeState
import com.velocimetro.scooterlab.ScooterSecretProvider

/**
 * Owns one authenticated Bluetooth session and keeps Android GATT callbacks out of the Activity.
 *
 * It intentionally has no lock/unlock API. Physical actions remain in the presentation layer and
 * require an explicit user confirmation before their protocol transaction is started.
 */
internal class ScooterSessionConnection(
    context: Context,
    device: BluetoothDevice,
    secret: ByteArray,
    private val listener: Listener,
) {
    interface Listener {
        fun onConnecting(detail: String)
        fun onAuthenticated(sessionKey: ByteArray)
        fun onApplicationFrame(frame: ByteArray)
        fun onFailure(reason: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val localSecret = secret.copyOf()
    private var closed = false
    private var handshakeStarted = false
    private var authenticated = false
    private lateinit var transport: AndroidScooterAuthTransport
    private val controller: ScooterAuthenticationController

    init {
        lateinit var currentTransport: AndroidScooterAuthTransport
        controller = ScooterAuthenticationController(
            secretProvider = ScooterSecretProvider { localSecret.copyOf() },
            frameWriter = { frame -> currentTransport.writeAuthenticationFrame(frame) },
            observer = observer@ { state, reason ->
                if (state == ScooterHandshakeState.AUTHENTICATED) {
                    if (authenticated) return@observer
                    authenticated = true
                    val key = try {
                        controller.authenticatedSessionKey()
                    } catch (_: Exception) {
                        postFailure("Authenticated session material was unavailable")
                        return@observer
                    }
                    mainHandler.post {
                        if (!closed) listener.onAuthenticated(key) else key.fill(0)
                    }
                } else if (state == ScooterHandshakeState.FAILED) {
                    postFailure(reason ?: "Scooter authentication failed")
                } else {
                    mainHandler.post {
                        if (!closed) listener.onConnecting(state.name.lowercase().replace('_', ' '))
                    }
                }
            },
        )
        currentTransport = AndroidScooterAuthTransport(
            context = context.applicationContext,
            device = device,
            listener = object : ScooterAuthTransportListener {
                override fun onStateChanged(state: ScooterAuthTransportState) {
                    if (state != ScooterAuthTransportState.READY) {
                        mainHandler.post { if (!closed) listener.onConnecting(state.name.lowercase().replace('_', ' ')) }
                        return
                    }
                    if (!currentTransport.enableApplicationResponses()) {
                        postFailure("The scooter response channel could not be enabled")
                    }
                }

                override fun onAuthenticationFrame(frame: ByteArray) = controller.onAuthenticationFrame(frame)

                override fun onSessionStatusFrame(frame: ByteArray) {
                    try {
                        controller.onSessionStatusFrame(frame)
                    } catch (_: Exception) {
                        postFailure("The scooter sent an invalid session-status frame")
                    }
                }

                override fun onApplicationResponseChannelReady() {
                    if (!handshakeStarted && !closed) {
                        handshakeStarted = true
                        controller.start()
                    }
                }

                override fun onApplicationFrame(frame: ByteArray) {
                    mainHandler.post { if (!closed) listener.onApplicationFrame(frame) else frame.fill(0) }
                }

                override fun onTransportFailure(reason: String) = postFailure(reason)
            },
        )
        transport = currentTransport
    }

    fun connect() = transport.connect()

    fun writeApplicationFrames(frames: List<ByteArray>, onAccepted: (() -> Unit)? = null) {
        check(!closed) { "Bluetooth session is closed" }
        transport.writeApplicationFrames(frames, onAccepted)
    }

    fun writeApplicationResponseFrames(frames: List<ByteArray>, onAccepted: (() -> Unit)? = null) {
        check(!closed) { "Bluetooth session is closed" }
        transport.writeApplicationResponseFrames(frames, onAccepted)
    }

    fun close() {
        if (closed) return
        closed = true
        localSecret.fill(0)
        transport.close()
    }

    private fun postFailure(reason: String) {
        mainHandler.post { if (!closed) listener.onFailure(reason) }
    }
}

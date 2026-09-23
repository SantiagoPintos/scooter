package com.velocimetro.scooterlab

/**
 * Bridges the pure handshake to an Android transport. It emits only security-channel frames and
 * reports state transitions; it has no surface for application commands.
 */
class ScooterAuthenticationController(
    secretProvider: ScooterSecretProvider,
    private val frameWriter: (ByteArray) -> Unit,
    private val observer: (ScooterHandshakeState, String?) -> Unit,
) {
    private val handshake = ScooterHandshake(secretProvider)

    fun start() {
        emit(handshake.begin())
        report()
    }

    fun onAuthenticationFrame(frame: ByteArray) {
        try {
            emit(handshake.onPacket(ScooterChannelFraming.decode(frame)))
        } catch (error: Exception) {
            // A malformed or unrelated notification must not cause an application command.
        }
        report()
    }

    fun onSessionStatusFrame(frame: ByteArray) {
        handshake.onSessionStatus(frame)
        report()
    }

    /** Available only after the scooter's final session-status confirmation. */
    fun authenticatedSessionKey(): ByteArray = handshake.authenticatedSessionKey()

    private fun emit(frames: List<ByteArray>) {
        frames.forEach(frameWriter)
    }

    private fun report() = observer(handshake.state, handshake.failure)
}

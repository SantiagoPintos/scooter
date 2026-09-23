# Scooter Lab Protocol

Standalone Kotlin module for the scooter BLE session: P-256/ECDH, HKDF-SHA256,
AES-CCM, channel framing, the authentication state machine, and synthetic unit tests.

It contains no scooter-specific secret material. A transport adapter supplies authentication and
application notifications, while a local secret provider supplies the effective LTMK outside the
repository.

`AndroidScooterAuthTransport` is the Android 11+ GATT adapter. It connects only after an explicit
`connect()` call, discovers `FE95`, subscribes to `0016`, `0010`, and the Spec v2 application
pair, then writes application frames only when the caller requests them.

# Scooter Control

Android dashboard for a Xiaomi Scooter 6 Max. It stores the chosen scooter locally, attempts an
automatic BLE connection at launch, and shows lock controls only after the authenticated MiOT
channel is ready.

Every physical action requires explicit user confirmation. The app validates the matching
authenticated MiOT response before reporting the action as confirmed.

## Setup and use

1. Open **Settings** and choose the scooter once.
2. Return to **Dashboard**; future launches reconnect automatically when Bluetooth and the local
   credential are available.
3. Use the lock control only after the dashboard reports **Connected**.

The selected Bluetooth address is held in private app preferences. The local credential lives at
`files/lab_credential.bin`, enables later connections without Xiaomi Home, is never shown in the
UI, and is excluded from Git.

## Development note

The persistence model targets one test phone. Any distributed release must migrate credentials to
Android Keystore and add rotation, revocation, and production-grade error handling. The optional
local backup is `scooterlab/local/lab_credential.bin`; that entire directory is Git-ignored and
must never be shared.

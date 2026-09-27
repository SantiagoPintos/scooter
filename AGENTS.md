# Scooter Lab — Working Context

## Purpose

This repository contains a local Android client for a Xiaomi Scooter 6 Max. The current flow
discovers the scooter, opens an authenticated BLE session, initializes MiOT, and performs
user-confirmed lock and unlock actions.

## Architecture

- `scooterlab/`: Kotlin protocol library. Keep cryptography, counters, and framing independent
  from UI and Android I/O whenever possible.
- `scooterlabapp/`: Android application, dashboard UI, and GATT transport adapter.
- `firmware/`: XIAO nRF52840 Zephyr application and portable protocol modules. Do not add
  scooter actions before transport, crypto, and confirmation gates are verified.
- `docs/`: research notes that contain no secret material. Update them only when a conclusion is
  confirmed.
- `scooterlab/tools/`: local instrumentation utilities. It is Git-ignored because tools can
  require a test phone, Frida, or private captures.
- `scooterlab/local/`: local credential backup. It is Git-ignored.
- `firmware/local/`: local Zephyr toolchain and future provisioning material. It is Git-ignored.

## Confirmed State

- Security-chip authentication and the MiOT application channel work with the test scooter.
- The application validates the authenticated MiOT response for lock and unlock operations; this
  response, not only a generic channel receipt, confirms the action.
- Spec v2 uses `001A` for outbound traffic and `001B` for the return traffic processed by the
  client. Both notifications can be enabled for setup, but their frames must not be merged into
  one inbound protocol consumer.
- When local setup is available, the Android app reconnects automatically at launch. Scooter
  selection belongs to Settings after the first setup.
- Charge limit is MiOT `4.21`, with permitted values 80–100% in steps of 5. The authenticated
  read was observed on the test scooter and matched Xiaomi Home at 80%. The write path is
  implemented and unit-tested but has not yet had a supervised physical test from Scooter Lab;
  see `docs/CHARGE_LIMIT.md` before treating it as verified.

## Safety and Quality Rules

- Never log, print, version, or expose credentials, keys, session material, raw BLE payloads,
  personal addresses, or decrypted content.
- Traces may retain only the structural metadata needed for investigation, such as direction,
  characteristic, packet category, and length.
- Never make a physical scooter action automatic. Every lock or unlock action must remain behind
  a clear user intent and confirmation.
- Add unit tests when changing codecs, counters, framing, or confirmation rules.
- Before handing off Android changes, run `:scooterlab:testDebugUnitTest` and assemble
  `:scooterlabapp:assembleDebug`.
- Build and install debug APKs with the same signing identity. Android accepts an in-place
  update only when the APK has the same signing certificate as the installed package. Do not
  delete or regenerate the debug keystore, and avoid using an alternate environment for an
  APK intended to update the test phone.
- If Android reports `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, stop before uninstalling. First locate
  the signing key used by the installed build. If a reinstall is necessary, verify that
  `scooterlab/local/lab_credential.bin` exists and is 32 bytes before removing the app; restoring
  it is documented in `scooterlabapp/README.md`. The selected scooter is separate app data and
  must be selected again after an uninstall.
- Never restore a credential using shell output redirection into app-private storage. Use
  `adb shell run-as <package> cp ...` so the file is created with the application's UID, then
  verify its size only. Do not print or inspect its contents.

## Next Phase

Port the proven BLE protocol to the XIAO nRF52840 in the gates described by
`docs/REMOTE_IMPLEMENTATION_PLAN.md`. The temporary Android clock-omission probe confirmed
authenticated MiOT initialization and a battery read without the clock update; it did not
test a physical write. The Android client remains the verified behavior reference, not firmware
source to copy wholesale. Firmware protocol modules must use synthetic test data and keep
credentials and scooter commands out of bring-up images until the relevant gates pass.

For further Xiaomi Home features, establish semantics from code and observed behavior, implement
the smallest protocol path, add tests, then verify with a supervised manual scooter test. The
dashboard may reserve space for unimplemented data but must never claim it is real telemetry.

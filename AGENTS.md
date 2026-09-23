# Scooter Lab — Working Context

## Purpose

This repository contains a local Android client for a Xiaomi Scooter 6 Max. The current flow
discovers the scooter, opens an authenticated BLE session, initializes MiOT, and performs
user-confirmed lock and unlock actions.

## Architecture

- `scooterlab/`: Kotlin protocol library. Keep cryptography, counters, and framing independent
  from UI and Android I/O whenever possible.
- `scooterlabapp/`: Android application, dashboard UI, and GATT transport adapter.
- `docs/`: research notes that contain no secret material. Update them only when a conclusion is
  confirmed.
- `scooterlab/tools/`: local instrumentation utilities. It is Git-ignored because tools can
  require a test phone, Frida, or private captures.
- `scooterlab/local/`: local credential backup. It is Git-ignored.

## Confirmed State

- Security-chip authentication and the MiOT application channel work with the test scooter.
- The application validates the authenticated MiOT response for lock and unlock operations; this
  response, not only a generic channel receipt, confirms the action.
- Spec v2 uses `001A` for outbound traffic and `001B` for the return traffic processed by the
  client. Both notifications can be enabled for setup, but their frames must not be merged into
  one inbound protocol consumer.
- When local setup is available, the Android app reconnects automatically at launch. Scooter
  selection belongs to Settings after the first setup.

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
- Build and install debug APKs from the same Windows user profile. Android accepts an in-place
  update only when the APK has the same signing certificate as the installed package. Do not
  delete or regenerate `%USERPROFILE%\.android\debug.keystore`, and do not use a sandboxed or
  alternate home directory for a release intended to update the test phone.
- If Android reports `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, stop before uninstalling. First locate
  the signing key used by the installed build. If a reinstall is necessary, verify that
  `scooterlab/local/lab_credential.bin` exists and is 32 bytes before removing the app; restoring
  it is documented in `scooterlabapp/README.md`. The selected scooter is separate app data and
  must be selected again after an uninstall.
- Never restore a credential using shell output redirection into app-private storage. Use
  `adb shell run-as <package> cp ...` so the file is created with the application's UID, then
  verify its size only. Do not print or inspect its contents.

## Next Phase

Replicate additional Xiaomi Home features one at a time: establish the semantic operation from
code and observed behavior, implement the smallest protocol path, add tests, then verify it with
a supervised manual scooter test. The dashboard may reserve visual space for unimplemented data,
but it must never claim that unavailable telemetry is real.

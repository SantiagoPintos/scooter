# Xiaomi Scooter 6 Max — Research Plan

## Goal

Build and validate a standalone BLE client for the scooter, beginning with authenticated access
and safe telemetry. Physical scooter actions must always be initiated and confirmed by the user.
Keys, raw captures, and credentials never belong in the repository.

## Completed Work

### 1. Baseline and static analysis

- Verified the Android test environment, ADB access, and local instrumentation workflow.
- Located Xiaomi Home's security-chip path and the scooter plugin.
- Documented ECDH P-256, HKDF-SHA256, AES-CCM, session setup, and the relevant GATT services
  without retaining secret values.

### 2. Dynamic protocol confirmation

- Confirmed that the test scooter follows the security-chip route and uses fresh ephemeral
  material for every session.
- Confirmed the authentication flow over `0016`, the application Spec v2 pair, and the MiOT
  encryption direction/counter rules.
- Confirmed that raw replay is not a viable approach.

### 3. Standalone client

- Implemented the Kotlin protocol library, synthetic unit tests, Android GATT transport, and a
  locally provisioned test credential.
- Implemented the Xiaomi Home-equivalent application startup exchange.
- Confirmed the directional application pairing: `001A` is outbound and `001B` is the inbound
  return path consumed by the client.

### 4. Physical lock control

- Identified lock state as MiOT property `siid=4`, `piid=6`.
- Implemented and verified lock and unlock from the standalone Android app.
- Added validation for the authenticated MiOT response that confirms the matching operation.

## Current Product State

- The app remembers the selected scooter and its local credential.
- When setup already exists, the app starts an automatic connection attempt at launch.
- The dashboard shows battery, power mode, a practical range estimate, and a lock toggle.
- Battery telemetry is read over MiOT. The lock toggle reads the authenticated boolean state
  from property `4.6` before enabling the opposite action; the first supervised physical test
  of that read path is still required.
- Range is an estimate based on approximately 25 km at full charge from observed use, not
  scooter-reported range telemetry.
- Firmware-image and direct-flashing evidence is summarized in
  [Firmware research](FIRMWARE_RESEARCH.md). No 6 Max OTA image or verified controller dump has
  been established, so encryption, signing, MCU/debug compatibility, and a safe restore path
  remain unknown.

## Next Features

Investigate and add one capability per iteration:

1. Supervise the new current lock-state read on the physical scooter and confirm both states.
2. Identify safe dashboard telemetry such as speed or trip.
3. Add auxiliary scooter functions only after their MiOT semantics and response behavior are
  confirmed.
4. Evaluate porting the proven protocol to a dedicated hardware remote only after Android
   functionality is stable.

## Research Rules

- Use source-code analysis and observed behavior together; do not infer packet content from a
  single physical result.
- Keep tooling and captures local, metadata-only where possible, and Git-ignored.
- Add synthetic tests for any protocol, cipher, or response-parser change.
- Do not send a new physical command without a user-visible confirmation step.

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
- The dashboard shows a compact connection state, a future-ready battery/range area, a lock
  control, and Settings for the one-time device selection.
- Battery, range, and an authoritative current lock state remain unimplemented. They must stay
  visibly unavailable until their respective MiOT reads are understood and validated.

## Next Features

Investigate and add one capability per iteration:

1. Read battery percentage, estimated range, and current lock state.
2. Add a true lock toggle backed by the validated current scooter state.
3. Identify safe dashboard telemetry such as speed, trip, or riding mode.
4. Add auxiliary scooter functions only after their MiOT semantics and response behavior are
   confirmed.
5. Evaluate porting the proven protocol to a dedicated hardware remote only after Android
   functionality is stable.

## Research Rules

- Use source-code analysis and observed behavior together; do not infer packet content from a
  single physical result.
- Keep tooling and captures local, metadata-only where possible, and Git-ignored.
- Add synthetic tests for any protocol, cipher, or response-parser change.
- Do not send a new physical command without a user-visible confirmation step.

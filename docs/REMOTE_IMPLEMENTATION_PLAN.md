# XIAO nRF52840 scooter remote — implementation plan

## Goal and boundaries

Build a standalone, battery-powered BLE central that controls **only the already selected
Xiaomi Scooter 6 Max**. The first complete outcome is an explicitly confirmed lock or unlock
operation with an authenticated MiOT result, without a phone or Xiaomi Home at run time.
No automatic action on boot, discovery, proximity, or reconnection. Range, display, OTA,
charge-limit settings, and other scooter features are outside the first remote prototype.

The Android implementation is the behavioral reference, not code that can run on the MCU.
Keep `:scooterlab` and its synthetic tests intact. The firmware must implement an equivalent
small state machine and compare against synthetic cross-platform test vectors. Never put the
effective 32-byte test credential, a real target identity, captures, or session material in Git,
firmware source, CI artifacts, or logs.

## Hardware and software baseline

- Board: Seeed Studio XIAO nRF52840 (non-Sense). Its nRF52840 has 1 MiB internal flash and
  256 KiB RAM; the board also has USB-C, BLE, and a built-in single-cell battery charger.
  See the [Seeed board guide](https://wiki.seeedstudio.com/XIAO_BLE/) and
  [Zephyr board page](https://docs.zephyrproject.org/latest/boards/seeed/xiao_ble/doc/index.html).
- Power: one compatible 1-cell 200 mAh Li-ion/LiPo battery, after checking its polarity,
  protection, and allowed charge current against the exact board and battery datasheets.
  Seeed documents approximately 50 mA or 100 mA charging, selected with P0.13; start with
  the default/low-current setting and verify on the actual board. Use the XIAO's USB-C for
  charging; do **not** connect the separately purchased USB-C charger in parallel.
- Inputs: two dedicated momentary buttons, one for lock and one for unlock. Start with the
  onboard LED for feedback. Add the buzzer only after identifying whether it is active or
  passive and its voltage/current requirements; use a driver if a GPIO cannot safely power it.
  Assign pins after inspecting the exact board and wiring, not from a guessed pinout.
- Firmware baseline: Zephyr on board target `xiao_ble/nrf52840`, using its BLE central/GATT
  client and PSA crypto backed by Mbed TLS. Pin a tested Zephyr release rather than tracking
  `latest`. Prove P-256 ECDH, HKDF-SHA256, and AES-CCM with 12-byte nonces and 4-byte tags
  on the selected release before committing to this stack. The
  [central sample](https://docs.zephyrproject.org/latest/samples/bluetooth/central/README.html),
  [MTU sample](https://docs.zephyrproject.org/latest/samples/bluetooth/mtu_update/README.html),
  and [PSA crypto guide](https://docs.zephyrproject.org/latest/services/crypto/psa_crypto.html)
  are starting points, not proof of scooter compatibility.

## Milestones and acceptance gates

### 0. Resolve the standalone clock dependency

The Android client sends a phone-derived local timestamp in the fourth MiOT startup message
(`MiotScooterApplicationInitializer.begin`). The remote has no trusted wall clock after a
flat battery. First test, in a controlled Android session, whether the clock update can be
omitted while initialization and a read-only MiOT query still work. Do not assume the clock
is optional or deliberately set the scooter's clock to a wrong value. If current time is
required, design a reliable USB time-provisioning and RTC-retention path before promising a
fully standalone remote. Document the observed result without retaining payloads or secrets.

**Gate:** a tested startup-time rule and an offline time strategy, or an explicit blocker.

### 1. Bring up board, power, and input

Build and flash a minimal Zephyr image via the board's UF2 bootloader. Verify both buttons,
onboard LED, USB recovery, and battery operation on the bench. Measure sleep and wake current
with the actual 200 mAh cell; do not estimate runtime from the board's marketing standby
number. Keep the buzzer disconnected at this stage.

**Gate:** button wake, unambiguous status feedback, repeatable flashing, and measured current.

### 2. BLE transport without scooter control

Scan for the provisioned target (never select by name alone), connect as a BLE central,
discover FE95, negotiate an ATT MTU of at least 247, read capability `0004`, and subscribe
to `0016`, `0010`, and the `001A`/`001B` application characteristics in their proven roles.
Match Android's order of operations and disconnect cleanly on every failure or timeout.
No physical MiOT command is permitted in this milestone.

**Gate:** repeated connect/disconnect sessions with only structural diagnostic metadata.

### 3. Port authentication and application initialization

Port A4/A5 bootstrap, channel framing/ACK handling, fresh P-256 ECDH, HKDF-SHA256, AES-CCM,
directional nonce/counter rules, security-chip confirmation, and the observed four-message
MiOT startup sequence. Provision the **effective** test credential and target identity over
USB into device-local storage; neither is compiled into a binary. For this lab, a simple
device-local storage implementation is acceptable, but clearly mark it non-production.
Compare the firmware's cryptography and framing to Android using synthetic vectors. Do not
log private bytes. Zephyr's [settings storage](https://docs.zephyrproject.org/latest/services/storage/settings/index.html)
is a possible local backend, not a claim of protected/secret storage.

**Gate:** authenticated session, application channel ready, and a matching read-only MiOT
battery query, repeatedly, without Xiaomi Home or the phone.

### 4. Explicit lock/unlock control

Use distinct lock/unlock buttons to avoid guessing the scooter's current state. A first press
selects the action and starts connection; after readiness, a long press of the same button
confirms it within a short window. Timeout, wrong button, disconnect, or power loss cancels.
Send the established MiOT `4.6` BOOL write only after this confirmation. Report success only
when the authenticated response matches request ID, property, and result; a GATT write or
generic channel ACK is not success. Disconnect after the result. Buzzer patterns, once added,
must distinguish confirmed success from failure without suggesting success prematurely.

**Gate:** supervised physical lock and unlock, plus wrong-target, timeout, duplicate-press,
and disconnect tests. Android/Xiaomi Home remain available as recovery controls.

### 5. Key-fob hardening

Add buzzer and enclosure only after the bench prototype works. Measure total energy per
attempt and idle current, then estimate battery life from those measurements. Check behavior
with the scooter absent, USB disconnected, low battery, interrupted operation, and firmware
reflash. Provide a credential wipe/reprovision path and document that anyone holding this
lab remote can operate the scooter. Revisit secure storage and update signing before any
release beyond this private experiment.

**Gate:** repeatable daily use on battery with no false-success indication or unintended
physical action.

## Architectural cut

```text
buttons / LED / buzzer
        → intent + confirmation state machine
        → session coordinator (timeouts, cleanup, single operation)
        → protocol core (bootstrap, crypto, framing, MiOT matching)
        → Zephyr BLE central / GATT adapter
```

Start with one scooter and one in-flight operation. Keep credentials in a separate provisioning
module and keep protocol functions testable without a radio. Stop at Milestone 4 for the first
usable remote; do not import the Android dashboard or unrelated settings into the firmware.

## First action when the board arrives

Confirm the exact board variant and battery specifications, connect the board by USB-C, and
verify its UF2 bootloader. The first firmware artifact should only exercise the LED and
buttons; do not provision the credential or touch the scooter until the transport and crypto
gates have passed.

# XIAO nRF52840 remote firmware

The Zephyr application targets `xiao_ble/nrf52840`. It initializes Bluetooth and exercises
the onboard LED, but does not scan or connect automatically. The protocol modules contain no
scooter identity, credential, or physical command. The LED loop is a bring-up aid, not a
battery-life design.

## Build

Use Zephyr `v4.4.0` and its SDK following the
[Zephyr getting-started guide](https://docs.zephyrproject.org/latest/develop/getting_started/).
Build from a Zephyr workspace with `west` available:

```text
west build -p always -b xiao_ble/nrf52840 -s <repository>/firmware -d <repository>/firmware/build
```

The build produces `build/zephyr/zephyr.uf2`, which is Git-ignored. Use the UF2 procedure in the
[board documentation](https://docs.zephyrproject.org/latest/boards/seeed/xiao_ble/doc/index.html)
to flash it. Confirm LED operation and repeatable USB recovery before connecting a battery.
Battery polarity and charging limits must be checked against the exact cell and board; do not
connect a separate charger in parallel with the XIAO's built-in charger.

## Protocol code

`src/scooter_channel.c` ports the framing and bootstrap behavior from `:scooterlab` without
Android I/O. `tests/scooter_channel_test.c` uses synthetic frames to check encoding, decoding,
validation, and buffer boundaries. The C tests are intended to run with any standard C11
compiler; they do not require BLE or scooter access.

`src/scooter_transport.c` begins the BLE central port. An application thread can explicitly
call `scooter_transport_open()` with a provisioned BLE address. It scans only for that address,
connects, negotiates an ATT MTU of at least 247, discovers FE95 and the five required
characteristics, subscribes to authentication characteristic `0016`, and reads capability
characteristic `0004` without retaining its value. `scooter_transport_close()` disconnects.
Every phase has a timeout, and no address or payload is logged. This path is compile-verified
but still needs a hardware connection test; no target provisioning caller exists yet.

The transport does not yet send the A4/A5 bootstrap or initialize the authenticated MiOT
session. The remaining acceptance gates are in the
[remote implementation plan](../docs/REMOTE_IMPLEMENTATION_PLAN.md).

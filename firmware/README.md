# XIAO nRF52840 remote firmware

The Zephyr application targets `xiao_ble/nrf52840`. It currently exercises the onboard LED
and compiles portable authentication-channel framing and A4/A5 bootstrap code. The protocol
modules do not contain a scooter identity, credential, BLE transport, or physical command.
The LED loop is a bring-up aid, not a battery-life design.

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

No authenticated session or scooter operation is implemented yet. The remaining acceptance
gates are in the [remote implementation plan](../docs/REMOTE_IMPLEMENTATION_PLAN.md).

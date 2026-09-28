# Scooter firmware image and flashing assessment

## Confirmed observations

- Xiaomi Home reports the scooter firmware as `2,1,1_0106.2031`. The interface inspected so
  far exposes a numeric version and serial number, but no separate `va` or `vfv` values.
- The scooter's identification label states a maximum speed of 25 km/h. The exact serial number
  is intentionally not recorded here.
- Xiaomi Home's 6 Max plugin selects the Drive-mode choices from SKU/device metadata and writes
  the selected value through MiOT `4.34`. On the test scooter, a 32 km/h setting was acknowledged
  and read back by both apps, while a supervised ride still remained limited to 25 km/h.
- Changing the Xiaomi Home account region to Brazil did not remove that observed 25 km/h riding
  ceiling. No second BLE property or confirmed command for changing the effective ceiling has
  been identified. See [Drive-mode speed limit](DRIVE_SPEED_LIMIT.md).

These observations show that the configurable/displayed Drive limit is distinct from the
effective riding limit on this unit. They do not identify which controller or firmware layer
enforces the latter. Xiaomi's general support information says scooter maximum speeds vary by
market and that OTA firmware has been used to align older models with local limits; it does not
document a 6 Max-specific procedure for changing this scooter's certified maximum. The product
label and model-specific specifications are stronger evidence for this unit's 25 km/h rating.

## Encryption and OTA status

No original 6 Max OTA image, verified controller flash dump, or board-level firmware image has
been established as an input to this repository's analysis. Consequently, it is **not known**
whether this model's firmware image is encrypted, digitally signed, both, or neither. These are
different protections: encryption hides image contents, while a signature or authenticated
integrity check can reject modified images even when their contents are readable.

The authenticated BLE session used by Xiaomi Home protects live application traffic. That fact
does not by itself establish how an OTA image is packaged, protected, authenticated, or installed.
Likewise, generic firmware/DFU code present in an app or dependency would not prove that the 6 Max
plugin uses that path. No OTA signing key or firmware decryption key has been confirmed in the
app, the observed BLE logs, or the material documented here.

## What an ST-LINK could and could not establish

ST-LINK is a programmer/debug probe for supported STM8/STM32 devices, typically using SWD or
JTAG. The scooter's controller MCU and debug interface have not been identified, so compatibility
is currently unknown. The scooter label, BLE model name, numeric firmware version, and the XIAO
nRF52840 remote do not identify the scooter controller's MCU. The nRF52840 is the remote board,
not evidence about the scooter's controller.

If the scooter controller is a compatible STM32 and its debug interface is accessible, an
ST-LINK might permit identification and, depending on device configuration, reading or
programming flash. It cannot decrypt an OTA file by itself, and it cannot bypass a cryptographic
signature enforced by the controller's boot chain. Readout protection may prevent a dump; on
some STM32 families, changing protection settings to regain access erases flash, while stronger
levels may be irreversible. Do not change protection bits or attempt a write before the exact MCU
and consequences are known.

Direct firmware modification or flashing is not currently a justified next step: there is no
known-good dump to restore, no verified image format or signing process, and no evidence that
changing the UI setting can safely or legally change the certified 25 km/h limit. An interrupted
or incompatible write could permanently disable the controller or affect braking and other
safety-critical behavior.

## Evidence needed before any device-level write

1. Obtain an official OTA package through the supported update flow, if one is available, and
   preserve its provenance and cryptographic hash. Do not put the package itself in Git.
2. Identify the exact controller board and MCU from its markings and authoritative board
   documentation. Do not infer the MCU from the scooter model or dashboard.
3. Establish whether the package is encrypted, signed, or merely compressed by analyzing a copy
   offline; keep those conclusions separate and do not treat an opaque byte stream as proof of
   encryption.
4. If a supported read-only debug path exists, first understand its protection state and verify
   a complete recoverable backup before considering any programming operation. Stop if doing so
   would require disabling protection with an erase, defeating access controls, or guessing pinout.
5. Keep any eventual investigation on a properly supported, stationary bench setup and preserve
   the original 25 km/h configuration. Do not test a modified speed limit in public riding.

Until these conditions are met, firmware analysis remains offline and observational. The
confirmed path forward for Scooter Lab remains documenting the separate MiOT setting and
supervising ordinary supported behavior, not flashing a modified speed controller.

## References

- [Xiaomi support: scooter maximum speed varies by market; OTA limits on older models](https://www.mi.com/pk/support/faq/details/KA-569759/)
- [Xiaomi Scooter 6 Max specifications](https://www.mi.com/uk/product/xiaomi-electric-scooter-6-max/specs/)
- [ST: ST-LINK interfaces and supported MCU families](https://wiki.st.com/stm32mpu/wiki/ST-LINK)
- [ST: STM32 readout protection overview](https://wiki.st.com/stm32mcu/wiki/Security:RDP)

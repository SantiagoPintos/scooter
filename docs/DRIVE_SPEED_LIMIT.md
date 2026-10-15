# Drive-mode speed limit (Xiaomi Scooter 6 Max)

Xiaomi Home's device plugin writes the Drive-mode limit through the `control.dSpeedLimit`
property: MiOT service `4`, property `34`, type `UINT8`. Its UI offers 15 km/h plus a
SKU-dependent higher option (20, 25, or 32 km/h). The previous Scooter Lab experiment used
service `5`, property `34`, so it did not address the property used by Xiaomi Home.

Xiaomi Home obtains the displayed current value from the `dg` field in the device-information
response (`2.10`). A live read from the test scooter reported 15 km/h. Scooter Lab now reads
that same scalar after connecting and after an explicitly confirmed write, and reports a
mismatch rather than assuming that an acknowledged write changed the setting.

The Settings control offers 15, 20, 25, and 32 km/h for controlled testing because SKU
enforcement by the scooter firmware is not yet established. A successful build and protocol
unit tests do not prove the scooter will accept or retain any particular value. The scooter
must be stationary and out of Boost mode, and a supervised physical test and readback are
required before claiming the write path works on this scooter. A beep is not used as a
confirmation signal.

In a supervised test, setting the Drive limit to 32 km/h produced a scooter acknowledgement;
afterward both apps displayed 32 km/h, while a ride still topped out at 25 km/h. This confirms
that the configured/displayed Drive limit and the scooter's effective riding-speed ceiling can
differ. The observation is consistent with another firmware or regional constraint, but does
not yet identify which component enforces the 25 km/h ceiling.

Further investigation found no second speed-setting property in the Xiaomi Home 6 Max plugin.
The plugin derives the SKU from the scooter serial and uses it to populate the Drive-limit choices;
the write callback still sends only `control.dSpeedLimit` (4.34). Xiaomi's official support FAQ
says the effective maximum varies by country/region and can be adjusted by OTA firmware, while
the 6 Max product specification lists a whole-scooter maximum of 25 km/h. This makes a separate
firmware/regional cap the leading explanation for the 25 km/h result, although its exact source
inside this scooter has not been identified. References: [Xiaomi speed-limit FAQ](https://www.mi.com/global/support/faq/details/KA-569759/),
[Xiaomi 6 Max specifications](https://www.mi.com/uk/product/xiaomi-electric-scooter-6-max/specs/).

No alternate BLE command or supported way to change that effective cap has been confirmed. The
plugin's firmware fields (`va` and `vfv`) gate which Drive-limit UI variants Xiaomi Home displays,
but that is not evidence that changing the UI selection changes the scooter's regional maximum.
Record those displayed firmware versions before considering any firmware-level investigation.
See [Firmware image and flashing assessment](FIRMWARE_RESEARCH.md) for the separate questions of
OTA image protection, controller MCU identification, and ST-LINK applicability.

## Firmware artifact analysis

The OTA artifacts themselves have now been analyzed offline: the `.mcu` controller package is an
encrypted + ECDSA-signed Mijia TFOTA container whose AES key could not be recovered from the
available material (likely eFuse-resident), and the dashboard `.upd` package contains plaintext
dashboard firmware exposing the BLE↔UART topic table (`CountryCode.set/get.vcu.comm`,
`d_gear_limit`, `ble.debug.cli` command console). Full details, including the key-search results
and the UART command surface, are in
[Firmware encryption research](FIRMWARE_ENCRYPTION_RESEARCH.md). The most feasible next step
coming out of that analysis is a read-only region-code probe over BLE/UART, not firmware
modification.

A plaintext VCU image from the same product family (Scooter 6, not 6 Max) has since been
analyzed statically: the controller-side limit is a config byte initialized to 25 and validated
against a hardcoded constant, next to a region table (EU1/BU1/EU2) and a UART message
(`{cmd 11, 50, 25, 0}`) that carries the limit. This is consistent with a regional profile and
identifies the command shape to look for on the 6 Max dashboard side. Details in
[VCU firmware research](VCU_FIRMWARE_RESEARCH.md).

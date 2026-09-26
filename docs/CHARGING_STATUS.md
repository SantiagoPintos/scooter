# Charging status (Xiaomi Scooter 6 Max)

The test scooter uses the Xiaomi Home plugin model `xiaomi.scooter.cross`. Xiaomi's public MiOT
instance is:

https://miot-spec.org/miot-spec-v2/instance?type=urn%3Amiot-spec-v2%3Adevice%3Ascooter%3A0000A077%3Axiaomi-cross%3A1

Its battery service `3`, property `2` is `charging-status`, a `uint8` with GATT `read` and
`notify` access. The published codes are `0 NotCharged`, `1 Charged`, `2 NotChargingTime`,
`3 QuickCharge`, and `4 QuickNotChargeTime`.

The app reads `3.2` after the initial dashboard reads, accepts authenticated property
notifications, and polls every 10 seconds while the session remains ready. The dashboard shows
`Cargando` for the spec's charging codes `1` and `3`, and no charging label for `0`, `2`, `4`,
or unknown/stale data. This code-to-label interpretation follows the published enum; the
individual code/state pairs have not been separately recorded on the test scooter. The app
drops the displayed value after repeated missed updates. The parser accepts only a
matching single-property get response or a correctly shaped single-property notification;
tests cover matching, unrelated responses, valid codes, and malformed values.

On 2026-09-26, the user confirmed that live charging detection works correctly on the test
scooter. The numeric codes observed in each physical charging state were not recorded.
Recording code/state pairs with the charger disconnected, actively charging, and charging
complete or paused would further verify the dashboard's charging-label interpretation.

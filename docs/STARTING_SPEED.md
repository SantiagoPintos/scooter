# Starting speed (Xiaomi Scooter 6 Max)

The locally observed Xiaomi Home plugin model is `xiaomi.scooter.cross`. Xiaomi's public MiOT
instance for that model is:

https://miot-spec.org/miot-spec-v2/instance?type=urn%3Amiot-spec-v2%3Adevice%3Ascooter%3A0000A077%3Axiaomi-cross%3A1

Service `4`, property `12` is `starting-speed`, a `uint8` with GATT read/write access. Its
published range is **3–5 km/h**, step 1. The Xiaomi Scooter 6 Max manual names the same three
settings. The app's writer accepts only 3, 4, or 5; it has no path to send an out-of-range
starting speed. The authenticated read also recognizes an unexpected zero so the UI can warn
if one is ever reported, but it cannot offer zero as a setting.

Settings reads this property after the battery, riding mode, and charge-limit reads. A change
requires a separate user confirmation. The protected MiOT write must receive a matching
authenticated response, and the app reads the property again before reporting the result. The
codec and response-matching paths have synthetic unit tests. A write from Scooter Lab has not
yet had a supervised physical test, so the implementation must not be described as physically
verified until the user confirms a 3–5 km/h change and read-back on the scooter.

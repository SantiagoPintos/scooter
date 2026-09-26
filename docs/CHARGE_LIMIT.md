# Charge limit (Xiaomi Scooter 6 Max)

## Confirmed MiOT property

The scooter's maximum charging percentage is MiOT service `4`, property `21`. Xiaomi Home
offers `80`, `85`, `90`, `95`, and `100` percent. A supervised change from 85% to 80% in
Xiaomi Home produced a Spec v2 set-property request (`opcode 0`) with one `UINT8` entry
(`type/length 0x1001`) for `4.21`. The 12-byte plaintext request has the same layout as the
existing lock-property write: flagged length, request ID, opcode, property count, service ID,
property ID, type/length, and one value byte. This is structural evidence from the local
test session; no packet bytes, keys, or credentials belong in this document.

## Read path

After the authenticated MiOT channel is ready, the Android app reads battery level, riding
mode, then charge limit. The charge-limit request uses the existing get-property opcode (`2`)
for `4.21`, the shared session cipher and request counter, and the existing Spec v2 flow/data
framing. The scooter can omit the generic flow acknowledgement, so the reader uses the same
short fallback window as the other read-only requests.

The observed reply is an authenticated MiOT get-property response (`opcode 3`) of 14 plaintext
bytes for `4.21`. Its two-byte result field at offset 9 is zero; the final byte is accepted
only when it is one of the five supported percentages. The two bytes between that result field
and the final value have not been assigned a confirmed semantic meaning. The reader also
requires the matching request ID, opcode, property count, service, and property. Unrelated or
invalid replies never populate Settings.

On 2026-09-26 the app connected to the test scooter, completed this read, and displayed 80%.
The user checked Xiaomi Home independently and confirmed it also displayed 80%.

## Write path and UI

Settings shows the last authenticated read and enables the selector only after that read
succeeds. The selector offers exactly the five values above. Choosing a different value opens
an explicit confirmation dialog. Only confirmation calls the command composer, which encrypts
the set-property request and sends it through the established application channel.

The generic channel receipt advances transport delivery but does not prove that the property
changed. The composer requires an authenticated MiOT response with the same request ID and
property `4.21`. After that response, the app reads `4.21` again. It displays the value as
applied only if the read-back matches the requested percentage. A timeout, invalid response,
or mismatch is shown as unverified or not applied.

The write codec, response matching, and read-back flow compile and have synthetic unit tests.
A charge-limit write initiated from Scooter Lab has **not yet had a supervised physical test**.
The current test is read-only; do not record the write path as physically verified until a
user-confirmed change is observed in Scooter Lab and checked in Xiaomi Home.

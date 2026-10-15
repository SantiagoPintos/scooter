# VCU firmware research — Scooter 6 plaintext image

This document records the offline analysis of the plaintext VCU firmware image
`7e6e02cafdf7d70171d0e9ed01705a85_mcu_xiaomi.scooter.6.bin` (Scooter 6, not 6 Max). It is a
companion to [Firmware encryption research](FIRMWARE_ENCRYPTION_RESEARCH.md), which covers the
encrypted 6 Max artifacts. All analysis was performed on local file copies; nothing here
involves connecting to or modifying a scooter.

## Why this file matters

The 6 Max VCU package (`.mcu`) is AES-ECB encrypted and ECDSA-signed, and its key could not be
recovered from available material (see the encryption research document). This Scooter 6 image
is a **plaintext** VCU firmware from the same product family. It exposes the actual speed-limit
logic that is hidden in the 6 Max package, which narrows what the 6 Max VCU can plausibly do and
identifies the exact protocol structures to look for (and the UART command shapes to test) on
the 6 Max dashboard side.

## File profile

| Property | Value |
| --- | --- |
| Size | 148,108 bytes (`0x2428C`) |
| Container | Raw image — no TFOTA trailer, no encryption, no signature observed |
| Code region | ARM Thumb-2, clear from `0xB000` to end (NOP `00BF`, `pop {pc}` epilogues, literal pools) |
| Data region | `0x0000..0xA000` — packed data / tables (entropy 6.3–7.1) |
| Tail | u16 duty-cycle table (`0x74DB, 0x7504, 0x752D…` step +41) |

No Cortex-M vector table was found in the file, so the load base is not yet established. One
candidate pointer (`0x1BBED829` at `0x550C`, low halfword matching the message-builder at
`0xD829`) suggested base `0x1BBE0000`, but the surrounding bytes look like packed data rather
than a pointer table, so it is treated as unconfirmed. The analysis below uses file-relative
offsets only.

## Region table (file header)

The header contains a table of three region entries with a 14-byte stride:

```
0x0000: 03 00 01 01 05 02 00 01 01 02     <- header (first byte 03 = entry count)
0x000A: "EU1\0"  00 a8 00 59 41 03 00 00 00 03
0x0018: "BU1\0"  01 2e 80 4e 4a 02 00 01 01 02
0x0026: "EU2\0"  00 6b 8c bf 1e 00 00 00 00 00
0x0034: zeros to 0x003F
```

Region strings also appear elsewhere: a second `BU1` at `0x1D677` inside a data blob.

The 10-byte parameter blocks do not decode cleanly as u16/u32 scalars and may contain hashes or
packed per-region payloads. They are recorded verbatim above for future cross-referencing.

## Speed-limit logic (config struct, stride 15)

A configuration struct with a 15-byte stride (`rsb rX, rY, rY, lsl #4`) holds a speed-limit
byte at **offset `0xB`**:

| Offset | Role | Evidence |
| --- | --- | --- |
| `0x00BB30` | `movs r0, #0x19` then `strb r0, [config, #0xB]` | default value **25** written at init |
| `0x00BD98` | `ldrb r0, [config, #0xB]`; `cmp r0, #0x19` | validation loop: the byte must equal 25 |
| `0x00C394` | `cmp r0, #0x19`; `beq` accept-path | second validation of the same byte |

The validation at `0xBD98` is inside a consistency loop that ANDs per-field flags (`ubfx … #4`
/ `#3` of `[config+4]`) and clears a global "config valid" flag (`mov.w sb, #0`) when the speed
byte differs from 25. This means the firmware actively **reverts or rejects** any stored value
other than 25 — a stored 32 would fail this check.

Related writes to struct offset `0xB` (from a full `strb [rX, #0xB]` scan) also occur at
`0x92B8`, `0xEC94`, `0x11EBA`, `0x1C028`, `0x1CABA` — likely other structs with the same field
position; they were not traced further.

## UART message {cmd 11, 50, 25, 0}

Function at `0xD828` builds and sends two messages:

1. `{2, 3, 0x10, 0x15}` via the sender at `0x14CE8` — setup/mode message.
2. `{0x0B, 0x32, 0x19, 0x00}` via the sender at `0x149A4` — a 4-byte command whose payload is
   `{50, 25, 0}`.

Interpretation (to be confirmed on the 6 Max dashboard side): command `0x0B` (11) carries the
speed limits, with `0x32` (50) as the hardware ceiling and `0x19` (25) as the active limit. The
`{2,3,0x10,0x15}` prefix suggests a two-phase configuration exchange.

The builder at `0xD828` has **no direct BL callers** — it is invoked through a function-pointer
table (dispatch table), so its invocation site was not statically resolved.

## The 32,000,000 constant

Exactly one occurrence of `0x01E84800` (32,000,000) exists in the file, at `0x1D67E`, inside the
second region entry: `…"BU1\0" 01 00 04 00 | 00 48 e8 01 | 00 00 00…`. In internal units this is
consistent with **32 km/h × 10⁶** (e.g. mm/hour) — i.e. a region-specific speed threshold. It is
the only 32-million-class constant in the image, which makes it a strong candidate for the
regional ceiling value, tied to region `BU1`.

## Consequences for the 6 Max work

1. The VCU-side limit is enforced by an ESC/VCU config value validated against a constant. A
   dashboard-side property write cannot change it while the VCU validation rejects non-default
   values.
2. The observed regional structure (EU1/BU1/EU2 + a 32-million threshold for BU1) supports the
   hypothesis that the ceiling is selected by region code rather than by the Drive-mode property
   (`4.34`), matching the live observation that setting 32 km/h in the app does not raise the
   riding ceiling.
3. The next testable path on the 6 Max is the read-only region-code probe already implemented in
   Scooter Lab (`MiotScooterCountryCodeReader`, provisional MiOT `2.11`) — knowing the reported
   region narrows whether the unit is on a 25 km/h or 32 km/h regional profile. A write path must
   not be attempted before the read semantics are verified against live traffic.
4. If a 6 Max-region profile maps to 32 km/h, the same confirmation flow (authenticated MiOT
   response, read-back) applies. Writes that could alter the certified limit follow the same
   safety framing as [FIRMWARE_RESEARCH.md](FIRMWARE_RESEARCH.md): supervised, reversible tests
   only, stationary, no public-road riding.

## Open questions

- Which region string does the test 6 Max report (`CountryCode.get`)? This is the immediate
  blocked-on-live-traffic question.
- What do the 10-byte region parameter blocks encode (checksums? packed config?); decoding them
  would show whether a region entry carries a speed value directly.
- The dispatch table that calls the `{cmd 11, 50, 25, 0}` builder was not resolved; finding the
  RX-side handler for command `0x0B` in this image would confirm the message semantics.
- Whether the 6 Max VCU uses the same 15-byte config stride and `0x19` constant (blocked on
  decryption of the 6 Max package, which remains infeasible with available material).

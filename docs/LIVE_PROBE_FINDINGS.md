# Live probe findings (Xiaomi Scooter 6 Max, 2026-10-15)

Results of the read-only diagnostic surface implemented in Scooter Lab and exercised against
the test scooter over an authenticated MiOT session. Companion to
[Drive-mode speed limit](DRIVE_SPEED_LIMIT.md) and [VCU firmware research](VCU_FIRMWARE_RESEARCH.md).
No speed-related write was sent; every probe below is read-only except the diagnostic flags
noted explicitly, which are reversible product-spec debugger settings.

## Diagnostic surface (dashboard firmware, service 6)

The MiOT spec's `debugger` service is fully reachable from the authenticated channel:

| Property | Type | Behavior observed |
| --- | --- | --- |
| `6.1` com-log-debug | bool, read+write | Write `true` ACKed (solicitud 18, op 1). Enables the internal log buffer. |
| `6.2` com-log | string, read+notify | GET returns a short marker (~16 bytes); the real content streams as **operation-4 notifications of 990 bytes each** (~9 KB in a few seconds once verbose logging is on). |
| `6.3` com-log-point / CLI | string, write | Writing the string `logon` (type nibble `0xA`, as seen on live 6.3 notifications) executes the dashboard's `property_set_cli` — equivalent of the `ble.debug.cli` console found in the firmware. |

With `6.1=true` + CLI `logon`, the dashboard streams its internal log. The stream includes
ride-state updates (`[update_ride_info_in_diff_mode] mode/gear/speed/...`), UART frame
parsing (`[parse]header 2009, tid N, opcode 2`), and the full device-information JSON when
it is rebuilt.

A burst of large notifications dropped the GATT link once (status 8); reconnects recover.

## Device-information JSON (2.10) — full schema

Key names captured from live responses (50 keys):

```
cs, dsn, fv, va, did, pm, ls, ers, bll, boost, tcs, abs, tls, ahss, mt, vat, du, aloss, cts,
af, sct, ect, cl, afms, afb, ab, ss, bcss, ast, blcs, kpus, vfv, vhv, vs, bfv, bhv, bs, td,
ps, prd, pp, pst, sds, vcu, dg, fmn, gp, rm, cb, oob
```

Test scooter values (identifying fields masked): `cs=0`, `ss=3`, `dg=15`, `pm=3`, `mt=20`,
`sct=240`, `ect=540`, `cl=80`, `ast=2`, `vfv="2031"`, `va="2.7.1_0106"`, `fv="0106"`,
`vhv="0006"`, `bfv="2110"`, `bhv="0100"`, `cb=36`, `rm≈16.x`, `td≈242.x`, `pp=30`.

Interpretations (provisional): `dg` = Drive-mode limit (15 km/h, matches Xiaomi Home);
`ss` = speed standard reported to the dashboard's `Device max speed standard: %d` log;
`cs` = country setting; `cl` = charge limit; `mt` = motor temperature; `cb` = battery.

## The `WF6` mystery — resolved

The after-sales ECU SN property (`7.2`, ecu-sn) answers GETs reliably (33-byte response).
Its 20-byte value is the ECU serial with structure:

```
NNNNN/AAAAAA0A000000   (5 digits, '/', 6 letters, 1 digit, 1 letter, 6 digits)
```

The first region-shaped token inside that string was `WF6` — **part of the serial, not a
region code**. The streamed dashboard log confirmed it: the `dsn` field contains the same
alpha block (`…/ENAPWF6P…`), so `WF` = letters 5–6 and `6` = the separator digit of the
serial's alpha block. No separate region field exists in the `7.2` reply.

## Where the regional ceiling likely lives

The dashboard does not expose a country/region write property. The remaining candidates for
the 25 km/h ceiling are:

1. **VCU-side state** (`ss=3`/`cs=0` are reported through `info.get.vcu.comm`), enforced in
   the encrypted VCU image. Changing it would go through `CountryCode.set.vcu.comm`, whose
   20-byte payload equals whatever is written to `7.2` — i.e. the after-sales SN write path.
   This is the highest-risk path (it can rewrite the ECU serial) and must not be attempted
   outside a supervised, reversible bench setup, if at all.
2. **A SN-derived SKU/standard**: the dashboard has `property_get_sku`, and Xiaomi Home
   derives the SKU from the serial. If `ss`/`cs` are derived from the serial rather than
   stored, only a serial rewrite would change them — same after-sales path, same risk.

## Dashboard-side limit computation (`handle_run_mode`)

Static analysis of the dashboard firmware (sec2_MCUAPP, function `handle_run_mode` at
VA `0x20BDB14`) established how the effective limit is produced:

- The function owns the **"Device max speed standard" global**. When the KV read of the
  standard fails, it logs `Failed to get device speed standard, using default value` and
  **stores the default `0x19` = 25** into that global (verified instruction:
  `movs r3, #0x19; str r3, [global]`).
- A `movs r3, #0x19` default plus the `Device max speed standard: %d` log (which prints that
  same global) confirm the global holds a km/h ceiling value, not an index.
- The function contains an 18-case jump table over the run-mode state byte (`cmp #0x11; bhi`
  + `tbh`), dispatching key/lock events per mode; each path ends in publishing
  `d_gear.limit.rec` and logging `Update gear D max speed limit : %d in RUN_MODE`.
- Pool strings around the switch: `Handling KEY_MODE_EVENT_PRESSED/RELEASED in RUN_MODE`,
  `Next status is LOCK_MODE`, `Publish TOPIC_GEAR_VEHICLE_HMI`, `d_gear.limit.rec`,
  `Gear D max speed limit: %d`.

Consequence: the effective riding ceiling is produced as
`published limit ≤ Device max speed standard (default 25)`, and the VCU additionally enforces
its own config limit (see VCU firmware research). The standard reaches the dashboard from the
VCU as `ss` in the device-information JSON (`ss=3` live). The open question is where the
dashboard's standard KV is written from (dev-info `ss` parse, `CountryCode.set`, or
SN-derived SKU) — that write path is the next analysis target.

## VCU UART protocol (Scooter 6 plaintext) — RX side mapped

The VCU's UART receive path was traced to ground the CountryCode-write risk assessment:

- **Frame processor** (`0xE8BC`): tries parser A first; on failure dispatches raw frames by
  byte[1] ∈ {`0x5A`, `0x6E`, `0x90`} through a handler table held in RAM (`0x20000030+`).
- **Parser A** (`0x12DE4`): framing `5A 13 <cmd> <len> <payload> <crc16>` (tolerant — header
  mismatches set flag bits instead of rejecting). The **command dispatcher** (`0x12D18`)
  handles exactly three commands, all OTA/DFU:
  - `cmd=2` → `0x13F8C`: start transfer — announced size must be ≤ `0x1A800` (108 KB),
    then erases the target flash region (delays + `0x13C58`).
  - `cmd=3` → `0x13E5C`: data chunks — per-chunk crc16, byte offset accumulated against the
    announced total.
  - `cmd=4` → `0x13EE4`: verify — total-length check plus **two 16-byte buffer compares**
    (hash/signature of the received image) before accepting.
- **Channel `0x5A`** (the app/config protocol) is handled by functions in the config
  subsystem: `0x0800B6D5 / 0x0800B459 / 0x0800B889` — the config bitfield packers
  (bytes 6/7/8: hardware settings), not a bulk receiver.
- The VCU has **no AES** (no S-box in the image): the UART protocol is plaintext, unlike the
  dashboard↔cloud/BLE layer.
- The config lives in the VCU's internal flash (`0x08014346`/`0x080143FD` pools) with a RAM
  mirror at `0x20003020`; a flash-write engine (unlock keys at `0xFB58`, async write/verify
  state machine at `0xFB9C`) commits changes.

Risk assessment update for a hypothetical `CountryCode.set` write (20 bytes via MiOT `7.2`):
the VCU-side receiver is still not pinned to a specific function, but the mapped structure
shows an async flash-write design with verification plus a main-loop consistency check that
reverts the speed byte to 25. That lowers the outright-corruption estimate; the open risks
remain serial-consistency (binding) and unknown validation behavior for non-serial payloads.
The remaining unmapped area is the config function cluster at `0xC000-0xCC00` (8 functions
touching the RAM mirror), which is the most likely location of a bulk-config receive path.

## Safety note

The diagnostic flags (`6.1`, CLI `logon`) were left enabled on the test scooter during this
session. They are reversible (`6.1=false`, CLI `logoff`) and do not affect riding behavior,
but they should be reset before returning the scooter to normal use.

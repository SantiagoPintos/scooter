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

## Safety note

The diagnostic flags (`6.1`, CLI `logon`) were left enabled on the test scooter during this
session. They are reversible (`6.1=false`, CLI `logoff`) and do not affect riding behavior,
but they should be reset before returning the scooter to normal use.

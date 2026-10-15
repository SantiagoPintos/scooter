# Firmware encryption research (Xiaomi Scooter 6 Max)

This document consolidates the offline analysis of the two OTA artifacts obtained through the
supported update flow, the AES key search against them, and the plaintext dashboard firmware
findings that define the remaining feasible paths. All analysis was performed on local copies
of the files listed below; nothing here involves connecting to or modifying the scooter.

## Sources

| File | Size | Origin |
| --- | --- | --- |
| `signed_4499b03783a350ca8fd4ffffb3b48ae9_upd_xiaomi.scooter.6max.bin` | 579,471 B | `.upd` dashboard/BLE package |
| `signed_774d45e813e51745579779a820920641_mcu_xiaomi.scooter.6max.bin` | 146,745 B | `.mcu` controller package |
| `/tmp/6max_upd/sec1_OTA.bin` … `sec7_BOOTPATCH2.bin` | 1,024 – 1,183,452 B | sections extracted from the `.upd` file |

## Container structure

### `.mcu` file (controller / VCU package)

Verified layout (see `bw-patcher/scripts/analyze_scooter6max_mcu.py` for the reproducible
structural check):

- `0x0000..0x0800` — header. First u32s (little-endian): sub-image count `2`, sub-image 1 size
  `0x18800`, sub-image 2 size `0xA800`, then CRC32s of both sub-images (verified). Rest `0xFF`.
- `0x0800..0x19000` — sub-image 1: entirely AES ciphertext, including a run of 216 identical
  16-byte blocks (`b17e9e18a58a62a08bdb27b25a7873c8`) covering `0x18270..0x19000`. A single
  anomalous block sits inside the padding run at `0x18C00` (all other padding blocks are
  identical). Real data ends at `0x18270`.
- `0x19000..0x23800` — sub-image 2: ciphertext up to `0x23260`, then **plaintext** `0xFF`
  padding (`0x5A0` bytes) to the end of the sub-image. This asymmetry (encrypted padding in
  sub-image 1, plaintext padding in sub-image 2) shows the two sub-images were encrypted by
  different tools/settings and strengthens the conclusion that the cipher is block-independent
  (ECB-like), not CBC/CTR, for sub-image 1.
- `0x23800..EOF` — trailer: `TFOTA` marker at `0x23804`, product string `xiaomi.scooter.6max`,
  two PEM certificates (a Mijia root CA-signed "Mijia Open" intermediate and a "cert0" leaf,
  valid 2020-09-07 → 2120-08-14), and a DER ECDSA P-256 signature over the image. The trailer
  is intact and not encrypted.

Consequence: any modified sub-image must be re-signed with the matching private key to pass the
controller's signature check. The image is both encrypted and signed; the signature alone would
already reject patched images even if the cipher were broken.

### `.upd` file (dashboard / BLE package)

Realtek RTL87x3E flash image ("RTL8773EWP" string at offset `0xAB`). Section table starts at
`0x48` with (address, size, flag) triples: `0x2055000/0x39C`, `0x2098000/0x79560`,
`0x220B000/0x4C8`, `0x2004000/0x2B14`, `0x207E000/0x85D4`, `0x2055400/0x458C`,
`0x2007000/0x2B14`. These are the seven `sec*.bin` sections in `/tmp/6max_upd/`.

`sec2_MCUAPP.bin` (1,183,452 B, base address `0x02098000`) is **plaintext** ARM Thumb-2
firmware: Mijia BLE framework code, strings, property tables, and a tail of `0xFF`/`0x00`
padding. Its header is `16 × 0xFF` followed by a 16-byte value. This is the dashboard chip's
application, not the VCU firmware, and it is fully readable.

## AES key search (negative result)

The repeated padding block gives a known-plaintext oracle: `E_k(0xFF×16) ==
b17e9e18a58a62a08bdb27b25a7873c8` for the sub-image-1 key. Two brute-force passes were run
(`/tmp/6max_upd/aes_key_hunt.py`, `aes_key_hunt2.py`) using macOS CommonCrypto:

- every 16-byte window, 16-byte aligned, across all 9 files (126,217 candidates), then
  stride-1 over all files (~2.1 M candidates);
- header-region windows at multiple alignments;
- derived keys: MD5/SHA-1/SHA-256 of `xiaomi.scooter.6max`, `mijia`, `MIJIA`, `RTL8773EWP`,
  etc., plus XOR combinations of the sub-image headers and the `0x18C00` anomaly block.

**Result: no candidate satisfies the oracle.** The key is therefore not stored as a raw
16-byte value anywhere in the available files and is not a simple hash of the product string.
For RTL87x3E encrypted images (16-byte `0xFF` + 16-byte value header matches that format), the
key typically lives in chip eFuse/OTP or per-device storage, which cannot be recovered from
these files. Key recovery would require a controller flash/eFuse dump through a supported debug
path, which the current repo policy rules out as a next step. Search for the AES key should be
considered exhausted against the available material.

## Dashboard firmware findings (`sec2_MCUAPP.bin`)

The dashboard firmware is plaintext and contains the entire BLE↔UART relay layer. Base address
`0x02098000`, Thumb-2.

### UART topic table (`0xDB400..0xDC000`)

Topic entries: 16/24-byte name, zero padding, flag u32 (`2` = VCU, `4` = BMS, …), handler VA.
Verified examples:

| Topic | Flag | Handler VA |
| --- | --- | --- |
| `OtaRequest.set.vcu.comm` | 2 | `0x020DD489` |
| `Sn.get.vcu.comm` | 2 | `0x020DE0B1` |
| `CountryCode.set.vcu.comm` | 2 | `0x020DD631` |
| `CountryCode.get.vcu.comm` | 2 | `0x020DDA55` |
| `StartSpeed.set/get.vcu.comm` | 2 | table entries present |
| `passwd.set.vcu.comm` | 2 | `0x020DF431` |
| `Bind.set.vcu.comm` | 2 | `0x020DE9D9` |
| `Unbind.set.vcu.comm` | 2 | `0x020DE21D` |
| `Heartbeat.get.vcu.comm`, `SingleMileage.get.vcu.comm`, `TotalMileage.get.vcu.comm` | 2 | present |
| `timestamp.get/set.bms.comm`, `sequence.get.bms.comm`, `CellVoltageAck.getack.bms.comm`, … | 4 | present |
| ACK topics: `CountryCodeAck.setack.vcu.comm`, `CountryCode.getack.vcu.comm`, `StartSpeedAck.*`, `SnAck.*`, `PasswdAck.*`, `BindAck.*`, `HeartbeatAck.*`, `MileageReset.setack.bms.comm` | — | strings at `0xB4BCB..0xB4DA8` |

### CountryCode.set handler (`0x020DD631`)

- Copies 16 bytes from a global state struct plus 16 bytes from the BLE property value
  (`[r6+0x18]`) into a 20-byte UART payload.
- Publishes with length `0x14` (20) via the UART publisher, registers ACK topic
  `CountryCodeAck.setack.vcu.comm`, and waits `0x12C` ms (300 ms) for the reply.
- On timeout logs `Set Country Code is failed`; the reply path logs `set VCU CountryCode ret:%d`
  and `set VCU CountryCode timeout`.
- The after-sales SN setter (`property_set_after_sales_vcu_sn`, VA `0x020C4984`) also
  references the same `CountryCode.set.vcu.comm` topic string — the 20-byte payload of that UART
  command can carry SN-adjacent data, so the country code is not the only field that flows
  through this message.

### BLE debug CLI (hidden console)

The dashboard registers a `ble.debug.cli` topic
(`[D] MIBLE_SPEC: Received ble debug cli %s`) and dispatches these string commands (strings at
`0xA9D40..0xA9E48`): `beep_stop`, `muteon`, `muteoff`, `logon`, `logoff`, `boost`, `unactive`,
`fst.act.time.rec`, `setvcuerr`, `clearvcuerr`, `setbmserr`, `clearbmserr`, `debugon`,
`debug.switch`, `debugoff`, `aulighton`, `aulightoff`, `aulighttime`, `ovspdtime`. A custom app
that can send arbitrary per-character BLE writes can reach this console, which is a feasible
diagnostic path without touching firmware.

### UART frame and speed-limit plumbing

- The periodic dashboard→VCU frame is formatted by
  `senddata:Gear:%d,Acc:%d,LBrake:%d,RBrake:%d,turnleft:%d,turnright:%d,SpeedLimit:%d`
  (string at `0xB6416`): `SpeedLimit` is a per-frame field forwarded from the dashboard to the
  VCU, read back via `read speedlimit:%d` (`0xB6205`).
- The dashboard-side property plumbing for the drive limit:
  `property_get_d_gear_limit` / `property_set_d_gear_limit` (strings `0xCA1FA`/`0xCA214`, table
  entry `d_gear_limit` at `0xDB0A0`), plus `Gear D max speed limit: %d` (`0xA7DC8`) and
  `Update gear D max speed limit : %d in RUN_MODE` (`0xA8515`).
- A separate "speed standard" concept exists: `Failed to get device speed standard, using
  default value` (`0xA5CCB`) and `Device max speed standard: %d` (`0xA5D13`) — consistent with
  the SKU/regional whole-scooter ceiling being distinct from the drive-mode limit.
- UART comm security strings: `Exchange Secret Key is failed`, `rebuild commm session key
  finished!`, `storage secret pubkey/prikey success/failed` (`0xB5E70..`) — the dashboard↔VCU
  UART channel derives a session key (ECDH-style) before carrying the topics above.

### MiOT `4.34` (`control.dSpeedLimit`) path

Xiaomi Home writes service 4 / property 34 (UINT8). On the dashboard this lands on the
`d_gear_limit` property, is forwarded to the VCU over the UART topic table above, and is ACKed.
A supervised test acknowledged 32 km/h while riding stayed capped at 25 km/h
(see `DRIVE_SPEED_LIMIT.md`), and the dashboard firmware offers no other speed-cap property.
The remaining cap candidates are (a) a regional value set through the `CountryCode` UART
command, or (b) logic inside the encrypted VCU image. The dashboard plaintext supports (a) as
the next testable path: `CountryCode.set.vcu.comm` is a first-class UART command reachable from
the dashboard's property handlers.

## Encryption key flags and candidates tested (summary)

- Encryption: AES-class, block-independent (ECB-like) for sub-image 1; sub-image 2 ciphertext
  has no repeated blocks in its data region, so its mode is not settled.
- Key candidates tested and rejected: 2.1 M+ file-window candidates (see above), product-string
  hashes, header-derived values. No key recovered.
- Signature: ECDSA P-256 over the whole image with a Mijia cert chain in the trailer; private
  key not available; signature is a hard barrier to patching the `.mcu` image even without the
  cipher key.

## Next steps

1. **BLE console in Scooter Lab** — per-character command/response UI against the dashboard's
   `ble.debug.cli` command set, with the derived command list from this document. This is
   diagnostics-only and does not change limits.
2. **`get region code` implementation** — add a reader that requests
   `CountryCode.get.vcu.comm` (handler `0x020DDA55`) and parses the reply
   (`CountryCode.getack.vcu.comm`), exposing the region the VCU reports. Knowing the region narrows
   whether the 25 km/h ceiling is regional.
3. **Known miio/UART commands** — implement the read-only set first (`Sn.get`,
   `CountryCode.get`, `SingleMileage.get`, `TotalMileage.get`, `Heartbeat.get`,
   `timestamp.get`), then evaluate whether any write path (including `CountryCode.set`) is
   worth a supervised, reversible test on a bench setup. Writes that could alter the certified
   25 km/h rating are not to be attempted casually; the same safety framing as
   `FIRMWARE_RESEARCH.md` applies.
4. VCU firmware decryption remains blocked: the AES key is not recoverable from available
   material (eFuse-resident), and the image is additionally signed. Do not invest further in
   key guessing against these files.

## Related documents

- [Drive-mode speed limit](DRIVE_SPEED_LIMIT.md) — the MiOT 4.34 setting and the observed
  25 km/h riding ceiling.
- [Firmware image and flashing assessment](FIRMWARE_RESEARCH.md) — earlier, more conservative
  assessment written before the OTA artifacts were obtained; its structural claims about
  encryption status are superseded by this document, its safety framing still stands.
- [VCU firmware research](VCU_FIRMWARE_RESEARCH.md) — analysis of a plaintext VCU image from the
  same product family, exposing the controller-side speed-limit logic that the encrypted 6 Max
  packages hide.
- `bw-patcher/scripts/analyze_scooter6max_mcu.py` — reproducible structural/ECB analysis.

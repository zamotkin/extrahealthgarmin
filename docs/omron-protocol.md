# Omron M7 Intelli IT (HEM-7361T-ALRU) — Verified BLE/EEPROM Protocol Specification v1.0

> Status: **v1.0 verified — omblepy-derived baseline confirmed against a real-device btsnoop
> capture (2026-10-24); remaining unknowns are listed in §10.**
> Every constant below carries a citation. Markers:
> `[SRC]` — derived from the omblepy reference implementation (pinned SHA, see Source Baseline).
> `[CAPTURE]` — verified on-device, session 2026-10-24: HEM-7361T-ALRU + Pixel 8 Pro / Android 17
> QPR3 Beta 2 (DP11.261002.005), client app com.zamotkin.extrahealthgarmin 2.2.0-omron-dev,
> btsnoop via bugreport `FS/data/misc/bluetooth/logs/btsnoop_hci.log`.
> `[UNVERIFIED-on-device]` — v0.9 marker for points the capture had to settle; every such point
> is now settled (see §7) and the marker is retired — open questions live in §10 Known Gaps.

## Source Baseline

| Source | Pinned reference | Access date |
|---|---|---|
| omblepy reference implementation | https://github.com/userx14/omblepy @ commit `59e6a5994a47dac77826ccb460aa0de5db869545` (main HEAD; re-verified 2026-10-25, v0.9 pin force-pushed away upstream) | 2026-10-15 |
| — protocol core | `omblepy.py` — symbols: `bluetoothTxRxHandler`, `LEGACY_*_UUID` constants, `startTransmission`, `endTransmission`, `_readBlockEeprom`, `_writeBlockEeprom`, `writeNewUnlockKey`, `unlockWithUnlockKey` | 2026-10-15 |
| — shared device driver | `sharedDriver.py` — symbols: `sharedDeviceDriverCode`, `getRecords`, `resetUnreadRecordsCounter`, `calcRingBufferRecordReadLocations`, `_getReadCommands_OnlyNewRecords` | 2026-10-15 |
| — device config | `deviceSpecific/hem-7361t.py` — class `deviceSpecificDriver` (M7 Intelli IT / HEM-7361T) | 2026-10-15 |
| BLE reversing methodology | https://reverse-engineering-ble-devices.readthedocs.io/en/latest/protocol_reveng/00_protocol_reveng.html (docs/omron.md ref 1) — HCI snoop + ATT framing background | 2026-10-15 |

How to re-verify: clone omblepy, `git checkout 59e6a5994a47dac77826ccb460aa0de5db869545`,
check the cited symbols. If a symbol moved/renamed on main, record the rename here.

## 1. GATT Map

### 1.1 Standard services (docs/omron.md §GATT + `[CAPTURE]` additions)

| Service | UUID | Notes |
|---|---|---|
| Generic Access | `0x1800` | device name, appearance |
| Generic Attribute | `0x1801` | |
| Device Information | `0x180A` | firmware/serial |
| Battery | `0x180F` | |
| Current Time | `0x1805` | `2A2B` @0x0511, notify+indicate `[CAPTURE]` |
| Blood Pressure | `0x1810` | root medical service; `2A35` @0x0811 indicate, `2A49` @0x0821 read `[CAPTURE]` |
| User Data | `0x181C` | `2A99`/`2A9A`/`2A9F`/`2A85` — not in omblepy `[CAPTURE]` |
| Unknown | `5df5e817-…` | 0x0600–0x0622: `2A52` write, `c195da8a-…` indicate — purpose unknown (§10) `[CAPTURE]` |

### 1.2 Proprietary Omron data channel `[SRC]`

| Role | UUID |
|---|---|
| Parent service | `ecbe3980-c9a2-11e1-b1bd-0002a5d5c51b` |
| Unlock characteristic | `b305b680-aee7-11e1-a730-0002a5d5c51b` (write + notify) |
| RX channels (device-to-phone) | `49123040-aee8-11e1-a74d-0002a5d5c51b`, `4d0bf320-aee8-11e1-a0d9-0002a5d5c51b`, `5128ce60-aee8-11e1-b84b-0002a5d5c51b`, `560f1420-aee8-11e1-8184-0002a5d5c51b` |
| TX channels (phone-to-device) | `db5b55e0-aee7-11e1-965e-0002a5d5c51b`, `e0b8a060-aee7-11e1-92f4-0002a5d5c51b`, `0ae12b00-aee8-11e1-a192-0002a5d5c51b`, `10e1ba60-aee8-11e1-89e5-0002a5d5c51b` |

Citation: `omblepy.py` — `LEGACY_PARENT_SERVICE_UUID`, `LEGACY_DEVICE_RX_CHANNEL_UUIDS`,
`LEGACY_DEVICE_TX_CHANNEL_UUIDS`, `LEGACY_DEVICE_UNLOCK_UUID`.

`[CAPTURE]`: the ALRU instantiates **all four TX and all four RX channels** — TX
`db5b55e0`/`e0b8a060`/`0ae12b00`/`10e1ba60` at handles `0x0321`/`0x0331`/`0x0341`/`0x0351`
(props `0x0C`); RX `49123040`/`4d0bf320`/`5128ce60`/`560f1420` at handles
`0x0361`/`0x0371`/`0x0381`/`0x0391` (props `0x12`); unlock at handle `0x0311` (props `0x1E`).
The Kotlin client must still enumerate actual characteristics at runtime (Decision Log #4),
but the assumption now matches the wire. Additional characteristic not present in omblepy or
in v0.9: `8858eb40-…` @`0x03a1`, props `0x10` (notify), inside the proprietary service —
purpose unknown (§10).

### 1.3 Advertising & standard-profile observations `[CAPTURE]`

- The device advertises ONLY when woken (transfer button held or a measurement taken); there is
  no idle advertising. Advertised name: `BLESmart_<8-hex model prefix><12-hex serial>` — the
  12-hex serial segment IS the device's public BD address, so the concrete name is redacted
  here per the §9.5 rule (scan the device to learn it; the BLE client should match on the
  `0x1810` service UUID, not on the name).
- Primary ADV payload: flags `0x06`, TxPower `0 dBm`, complete 16-bit service list `0x1810`,
  manufacturer data `0bff0e020101f000 64720264`; interleaved with an Apple-frame beacon ADV
  (`1aff4c000215` + proximity UUID `7a516657-1203-422c-bbe1-14f650a6c13d`).
- Live measurements ARE delivered over the STANDARD profile: `0x2A35` INDICATIONS (observed in
  the third-party MEDM Health session). Empirical 19-byte layout, flags = `0x1E`: flags(1),
  systolic SFLOAT(2), diastolic SFLOAT(2), MAP SFLOAT(2), timestamp(7 — sits AFTER MAP,
  non-standard), pulse SFLOAT(2), userId(1), status(2). The device sets flag bits 1, 2, 3, 4 —
  bit 4 is RFU per the Blood Pressure Profile but behaves as timestamp-present here.
- On CCCD subscribe the device pushes the LAST STORED record; on reconnect after a fresh
  measurement it pushes the FRESH record. Standard time sync via a `2A2B` write also works.
- Implication: live readings can use the standard profile; the proprietary EEPROM path (§3–§6)
  is needed for history bulk-readout and unread tracking.

## 2. SMP Bonding

- Model `[CAPTURE]`: **LEGACY pairing — Just Works, NO LE Secure Connections, NO ECDH.** The
  capture contains no SMP Public Key opcode anywhere. PairingReq (phone): io=4, auth=0x05;
  PairingResp (device): io=3, auth=0x01 → Just Works (the NoInputNoOutput responder forces an
  unauthenticated association); Confirm x2 + Random x2; EncInfo/MasterId/IdInfo/IdAddr
  distributed in BOTH directions. (v0.9 said "Just Works with ECDH key exchange" — the ECDH
  half was wrong.)
- `[SRC]` (indirect, unchanged): `omblepy.py` `writeNewUnlockKey` comment — enabling RX-channel
  notification *triggers the device to send an SMP Security Request*, which kicks off pairing.
- `[CAPTURE]`: with a pre-existing OS bond, later sessions run with ZERO SMP — the bond is
  reused silently, with no pairing prompt. This resolves the v0.9 "bonding trigger order"
  question: our client connected over the existing bond with no re-pairing, so the trigger
  order never manifests for a bonded host.
- Access to medical characteristics is gated: reads/writes without bonding/app-level unlock
  fail with `GATT_INSUFFICIENT_AUTHENTICATION` / `GATT_ERROR 133`-class failures
  (docs/omron.md §Pairing, §State machine; uncontested background).
- OS bonding alone is NOT sufficient for omblepy's flow — the application-level key (§3) is
  additionally required on every session. `[SRC]` — `unlockWithUnlockKey` is called
  unconditionally in `getRecords`. `[CAPTURE]` caveat: the ALRU accepted an arbitrary key while
  unregistered (§3.3), so the strength of this gate is mode/variant-dependent.

## 3. Application-Level Pairing Key (registration & unlock)

### 3.1 Key properties `[SRC]`

- Length: **16 bytes** (32 hex chars). `omblepy.py` `pairingKey` default
  `deadbeaf12341234deadbeaf12341234`, `writeNewUnlockKey` raises unless `len == 16`.
- Value: arbitrary; generated once per host, then constant. Not derived from any secret. The 2026-10-26 research appendix (§12) re-verified this against the community sources: no serial→key derivation exists in any Omron implementation.
- Storage: inside the **device** (its location is opaque to the client — written via the
  unlock characteristic, not via EEPROM block writes).

### 3.2 Registration flow (first pairing, device showing "-P-") `[SRC]` — `writeNewUnlockKey`

1. Enable notify on RX channel 0 — device sends SMP Security Request, OS pairing runs.
2. Enable notify on the **unlock characteristic**.
3. Retry loop (up to 10 attempts, 1 s apart): write `0x02 || (0x00 x 16)` (17 bytes, with
   response) to the unlock characteristic. Success = response starts with `8200`.
4. Write `0x00 || key(16 bytes)` (17 bytes, with response). Success = response starts with `8000`.
5. Device displays `[OK]`.
6. Immediately after first registration, run a start/end transmission cycle
   (`startTransmission` + `endTransmission`) — omblepy comment: "seems to be necessary when
   the device has not been paired to any device".

`[CAPTURE]`: none of these registration writes (opcodes `02` / `00`) were observed on the ALRU —
in every capture the device accepted plain unlock (§3.3) without ever being registered. The
flow above remains the only sourced registration procedure; keep it for variants that require
it (Decision Log #7).

### 3.3 Unlock flow (every session) `[SRC]` — `unlockWithUnlockKey`

1. Enable notify on the unlock characteristic.
2. Write `0x01 || key(16 bytes)` (17 bytes, with response).
3. Success = response starts with `8100`; omblepy treats anything else as key mismatch /
   not registered (but see the `[CAPTURE]` note below).

`[CAPTURE]`: the ALRU answered the unlock write `01 || key(16)` (17 bytes) with a notification
whose first two bytes are `81 00` — a 17-byte response whose trailing 15 bytes are unknown —
even though NO app-level registration was ever performed (no client wrote opcodes `02` or `00`
to the unlock characteristic in any capture). Unlock with an arbitrary 16-byte key succeeds on
an unregistered device, at least in `-P-` transfer mode; whether this holds outside `-P-` mode
is untested (§10).

### 3.4 Discrepancy vs docs/omron.md

docs/omron.md §Phase 3 claims a "38-byte registration block" written to "EEPROM starting at
address 0x0010". Per omblepy: the key is 16 bytes and goes through the unlock characteristic;
address 0x0010 is the **settings read block** (§5), which is read-not-written during unlock.
**No source evidence for 38 bytes exists.** `[CAPTURE]` — settled: the 16-byte key format is
the operative one on the wire (unlock `01 || key(16)` accepted with `8100`, §3.3). The 38-byte
claim retains zero evidence. Note: the registration write `00 || key` itself was never observed
on-device — it is the single unexercised constant (§10).

## 4. Session Protocol (EEPROM readout)

### 4.1 Session lifecycle `[SRC]` — `startTransmission` / `endTransmission`

| Step | Frame (hex) | Meaning |
|---|---|---|
| Session start | `08 00 00 00 00 10 00 18` | FIXED frame: type `0000`, address `0x0000`, count field = magic `0x10`, XOR-CRC `0x18` |
| Session end | `08 0f 00 00 00 00 00 07` | type `0f00`, address `0x0000`, count `0x00`, XOR-CRC `0x07` |

Per-frame variants (Task 3 amendment, 2026-10-15): the session start is a fixed literal whose
count field carries magic `0x10` (the parser must NOT treat it as a data length); a read
command puts the read size in the count field and has no data bytes (`08 01 00 <addr:2> <size> 00 <crc>`); only write commands carry data (`N = 6 + M + 2`).

Expected responses `[SRC]`: `8000` for start; `8f00` for end. `[CAPTURE]` — frame layouts
confirmed byte-exact on the wire: start `08 00 00 00 00 10 00 18` → ack `8000`; read
`08 01 00 <addr:2> <size> 00 <crc>` → `8100` with the address echoed and data = response bytes
`[6 : 6+M]`; end `08 0f 00 00 00 00 00 07` → `8f00` carrying **count = 0, NO status byte**.
omblepy reads frame byte 6 of the `8f00` response as a device "status" — on the wire that byte
is the `0x00` CRC padding, not a status code (v0.9 followed omblepy here; corrected).

**Discrepancy vs docs/omron.md resolved**: "команда 0x08" is not a command — `0x08` is the
*frame-size byte* of an 8-byte type-`0000` frame. Commands are the 16-bit **types**
`0000` / `0100` / `01c0` / `0f00` (docs/omron.md's read/write/end types were correct).

### 4.2 Frame format `[SRC]` — `_readBlockEeprom`, `_writeBlockEeprom`, `_callbackForRxChannels`

```
byte 0      : total frame size N (header 6 + data + crc 2)
bytes 1-2   : type (big-endian): request 0000/0100/01c0/0f00, response 8000/8100/81c0/8f00/8200
bytes 3-4   : EEPROM address (big-endian)
byte  5     : number of data bytes M
bytes 6..   : M data bytes
bytes N-2..N: CRC area — last byte = XOR of all preceding bytes; the byte before it is 0x00
              padding. Invariant: XOR over the WHOLE frame == 0.
```

CRC algorithm: XOR of all bytes before the final CRC byte; the final CRC byte makes the
total XOR equal 0. Verified in three places: the `_readBlockEeprom` and `_writeBlockEeprom`
frame builders, and the RX-side validation in `_callbackForRxChannels`
(accumulate `xorCrc ^= byte`, fail if non-zero after the whole frame).

### 4.3 Read block `[SRC]` — `_readBlockEeprom`

Request: `08 01 00 <addrHi> <addrLo> <size> 00 <xor-crc>`. Constraints: `size` is 1 byte
(single read at most 255 bytes; sharedDriver refuses settings sections >= 54 bytes).
Block transfer size for records: `0x10` bytes per read
(`transmissionBlockSize = 0x10`, hem-7361t.py). Response type `8100`, address echoed;
data = response bytes `[6 : 6+M]`. If a frame reports more bytes than it carries,
omblepy substitutes `0xFF` padding (corruption-tolerant path, `_callbackForRxChannels`).

### 4.4 Write block `[SRC]` — `_writeBlockEeprom`

Frame: size byte = `M + 8`, type `01c0`, address, `M`, data, `0x00`, XOR-CRC. Default
sub-block size for continuous writes: `0x08` (`writeContinuousEepromData` default
`btBlockSize = 0x08`). Response type `81c0` with the written address echoed.

### 4.5 Multi-channel chunking `[SRC]` — `_waitForRxOrRetry`

A command frame is split into 16-byte chunks; chunk number i goes to TX channel i.
Responses arrive per RX channel in 16-byte units; channels are concatenated. Truncation: to the size byte. Retry: 5 attempts, 1 s RX timeout.
`[CAPTURE]` — confirmed, with one clarification: response units are PADDED to 16 bytes per RX
channel. Correct reassembly = per-channel buffers concatenated in CHANNEL INDEX order, then cut
at the size byte — a naive arrival-order byte stream desyncs on the padding zeros. Command
chunks split across TX channels in index order: confirmed.

## 5. EEPROM Memory Map (HEM-7361T) `[SRC]` — `deviceSpecific/hem-7361t.py`

| Region | Value | Notes |
|---|---|---|
| USER1 records start | `0x0098` | ring buffer, 100 slots |
| USER2 records start | `0x06D8` | ring buffer, 100 slots |
| Records per user | 100 | |
| Record size | `0x10` (16) bytes | |
| Settings read address | `0x0010` | settings block is READ here |
| Settings write address | `0x0054` | and WRITTEN BACK here (different address) |
| Unread-records section | bytes `[0x00, 0x10)` of settings block | 8 bytes |
| Time-sync section | bytes `[0x2C, 0x3C)` of settings block | 16 bytes |

Settings block size = `0x54 - 0x10 = 0x44` (68 bytes): cached in RAM by the client, mutated (unread reset / time sync), then written back to `settingsWriteAddress + section offset` (`sharedDriver.getRecords`).

`[CAPTURE]`: confirmed on the wire — the unread-section read targets `0x0010` with a 16-byte
read (frame `08 01 00 00 10 10 00 09`); record reads covered the full USER1 ring
`0x0098`–`0x06D7` in 16-byte blocks (100 slots); the 2026-10-25 control run confirms the USER2 ring end too — the newest record sat at `0x0D08` (slot 99 of the 0x06D8–0x0D17 ring); unread reset writes went to `0x0054 + 0x00`.

### 5.1 Unread-records counter (8 bytes at settings[0x00:0x10]) `[SRC]` — `_getReadCommands_OnlyNewRecords`

Per user *i* (omblepy bit numbering — same convention as §6): `lastWrittenSlot_i` = the FIRST byte of the 16-bit little-endian field `[2i : 2i+2]`; `unreadRecords_i` = the FIRST byte of `[2i+4 : 2i+6]`. `[CAPTURE]` the 2026-10-25 session settles this byte order on the real device: after one new USER2 measurement the unread field read back `01 00` (counter 1 in the first byte), and after the 0x8000 reset it read `00 80` — the 0x80 sits in the SECOND byte and must be ignored (reading it as the count yields the bogus 128 and full-ring mega-reads). Ring-buffer geometry: `calcRingBufferRecordReadLocations` handles the wrap-around case (`lastWrittenSlot < unreadRecords` = two reads).

Reset after successful readout `[SRC]` — `resetUnreadRecordsCounter`: write `0x8000` ("no new records" code) into both 16-bit fields per user, then write the section back.

### 5.2 Time sync (16 bytes at settings[0x2C:0x3C]) `[SRC]` — `deviceSpecific_syncWithSystemTime`

bytes `[0:8]` copied from EEPROM unchanged; `[8:14]` = `year-2000, month, day, hour, minute, second`; `[14]` = sum of all previous bytes AND `0xFF`; `[15]` = `0x00`.

## 6. Record Layout (16 bytes) `[SRC]` — `deviceSpecific_ParseRecordFormat`; numbering corrected `[CAPTURE]`

Bit numbering — CORRECTION (v0.9 was mis-transcribed). omblepy's `_bytearrayBitsToInt` numbers
bits from the MSB of the 128-bit little-endian record integer: omblepy "bit k" = integer bit
(127 - k), and byte 0 is that integer's least-significant byte. The v0.9 transcription
("bit 0 = LSB of byte 0") therefore placed every field in the wrong byte. The table below lists
each field's true position, derived from the formula (127 - k) — the formula is authoritative
wherever prose and table could disagree. `deviceEndianess = "little"` still holds; it is the
bit INDEXING that starts at the MSB.

| omblepy bits | Field | Byte/bit position (bit 0 = LSB of each byte) | Transform |
|---|---|---|---|
| 68-73 | minute | byte 6 bits 6-7 + byte 7 bits 0-3 | as-is |
| 74-79 | second | byte 6 bits 0-5 | `min(second, 59)` — device may report up to 63 |
| 80 | mov (movement flag) | byte 5 bit 7 | as-is |
| 81 | ihb (irregular heartbeat flag) | byte 5 bit 6 | as-is |
| 82-85 | month | byte 5 bits 2-5 | as-is |
| 86-90 | day | byte 4 bits 5-7 (low 3) + byte 5 bits 0-1 (high 2) | as-is |
| 91-95 | hour | byte 4 bits 0-4 | as-is |
| 96-97 | unused | byte 3 bits 6-7 | — |
| 98-103 | year | byte 3 bits 0-5 | `+ 2000` |
| 104-111 | pulse (bpm) | byte 2 (all 8 bits) | as-is |
| 112-119 | diastolic | byte 1 (all 8 bits) | as-is |
| 120-127 | systolic | byte 0 (all 8 bits) | **+ 25** |

Worked examples `[CAPTURE]` — both simultaneously confirmed by the M7 display and by the
standard `2A35` indication of the same measurements:

- `70423c1a162bb5120000f10000000100` → 2026-10-24 22:10:53, sys=137 (raw `0x70` = 112, +25),
  dia=66, pulse=60, mov=0, ihb=0.
- `6f58451a102b1a1b0001f00000008700` → 2026-10-24 16:44:26, sys=136 (raw `0x6f` = 111, +25),
  dia=88, pulse=69.

50 of 50 captured records decode plausibly with the corrected numbering.

Empty slot marker: all bytes `0xFF` = skip (`sharedDriver.getRecords`). Undecodable records are skipped with a warning, not fatal.

**Discrepancy vs docs/omron.md resolved**: measurements are **plain bit-packed integers, NOT IEEE 11073 SFLOAT**. SFLOAT applies only to the standard `0x2A35` Blood Pressure Measurement characteristic (docs/omron.md §Decoding mixed sources). The EEPROM path never uses SFLOAT.

## 7. Discrepancy Table (docs/omron.md vs verified findings)

| # | docs/omron.md claim | Verified finding | Status |
|---|---|---|---|
| 1 | 38-byte registration key written to EEPROM @0x0010 | 16-byte key via unlock characteristic; 0x0010 is settings read block | **RESOLVED `[CAPTURE]`** — 16-byte key format is the operative one on the wire (unlock `01 \|\| key16` accepted, `8100`, on a never-registered device); the 38-byte claim has zero evidence; the registration write `00 \|\| key` itself was not observed on-device — the single unexercised constant (§10) |
| 2 | Session start command `0x08` | `0x08` is the frame-size byte; command type is `0000` | **RESOLVED** (omblepy.py `startTransmission`) — `[CAPTURE]` byte-exact on the wire |
| 3 | Measurements are SFLOAT (IEEE 11073-20601) | EEPROM records: plain bit-packed ints (sys = byte 0 + 25) | **RESOLVED** (hem-7361t.py `deviceSpecific_ParseRecordFormat`) — `[CAPTURE]` worked examples decode (§6) |
| 4 | Read/write/end commands `0x0100 / 0x01c0 / 0x0f00` | Confirmed as frame types | **RESOLVED** — `[CAPTURE]` frames observed byte-exact |
| 5 | Proprietary channel UUIDs | Confirmed (parent/unlock/RX x4/TX x4) | **RESOLVED** (omblepy.py `LEGACY_*`) — `[CAPTURE]` all 8 channels + handles observed (§1.2) |
| 6 | Two users USER1/USER2 | Confirmed: 2 slots x 100 records | **RESOLVED** (hem-7361t.py) — `[CAPTURE]` USER1 ring 0x0098–0x06D7 read in full (§5) |
| 7 | Settings "read and sent back to EEPROM starting at 0x0010" | Read @0x0010, **written @0x0054** | **RESOLVED** — write address differs, critical for implementation; `[CAPTURE]` unread reset writes went to 0x0054+0x00 (§5) |
| 8 | — (new, v1.0) | `8f00` end-of-session response carries count=0, no status byte; omblepy's "status" read is the CRC padding byte | **NEW `[CAPTURE]`** — §4.1 |
| 9 | — (new, v1.0) | Unlock succeeds with an arbitrary 16-byte key on a never-registered device (`81 00` response) | **NEW `[CAPTURE]`** — §3.3 |
| 10 | — (new, v1.0) | SMP is LEGACY Just Works — no LE Secure Connections / ECDH | **NEW `[CAPTURE]`** — §2 (corrects v0.9 §2) |
| 11 | — (new, v1.0) | §6 bit numbering was mis-transcribed in v0.9 ("bit 0 = LSB of byte 0"); corrected numbering verified against ground truth | **NEW `[CAPTURE]`** — §6, binding for the decoder (Decision Log #2) |
| 12 | — (new, v1.0) | A standard `2A35` live path exists (indications on subscribe / fresh record on reconnect) | **NEW `[CAPTURE]`** — §1.3 |

## 8. Decision Log (binding for epic tefal2garmin-xtg)

1. Implement the **16-byte key** flow (§3) as the primary path — **CONFIRMED operative `[CAPTURE]`** (unlock `01 || key(16)` accepted on the wire, §3.3). The 38-byte variant has zero evidence; the registration write `00 || key` remains the single unexercised constant (§10).
2. Decode EEPROM records with the **plain-integer bit layout** (§6) using the **corrected bit numbering** — omblepy "bit k" = integer bit (127 - k); the §6 field→byte/bit table is binding for the decoder `[CAPTURE]`. SFLOAT decoder allowed only as a tested, unused-by-default utility for `0x2A35`.
3. GATT operations strictly callback-serialized; per-command retry 5x with 1 s timeout (mirrors `_waitForRxOrRetry`).
4. Enumerate actual RX/TX characteristics at runtime; do not assume all 4 channels exist.
5. Settings cache / read-modify-write / reset flow per §5 exactly (read @0x0010, write @0x0054).
6. Skip all-`0xFF` slots; skip (log) undecodable records; clamp second to 59.
7. Registration flow (§3.2) is OPTIONAL on the ALRU — the device accepts plain unlock while
   unregistered (§3.3) `[CAPTURE]`; keep the registration code path for other Omron variants.
8. A future epic may add the standard `2A35` live path (§1.3) for real-time readings; the
   proprietary EEPROM path remains required for history bulk-readout and unread tracking.

## 9. Capture Runbook (task 2 — on-device verification)

Target reader: device owner, no developer background. Do NOT commit the captured log or any key material. Redact BD address and key bytes before sharing.

**Executed 2026-10-24 `[CAPTURE]`** — two capture clients were used: MEDM Health (third-party;
standard-profile session: pairing, GATT discovery, `2A2B` time sync, `2A35` indications) and our
own app com.zamotkin.extrahealthgarmin 2.2.0-omron-dev (proprietary flow: unlock, session
frames, EEPROM readout, unread reset). Android 17 specifics observed on the Pixel 8 Pro: the
Developer-Options toggle sets device_config flags (`INIT_gd_hal_snoop_logger_filtering` /
`..._socket`), NOT `persist.bluetooth.btsnooplogmode`; retrieval was via `adb bugreport` →
`FS/data/misc/bluetooth/logs/btsnoop_hci.log` (path #5 below). Findings are folded into this
spec as `[CAPTURE]` citations; raw logs stay out of the repo (§9.5, §11).

### 9.1 Enable the HCI snoop log

1. Settings — About phone — tap "Build number" 7 times — Developer Options unlocked.
2. Developer Options — enable **"Enable Bluetooth HCI snoop log"** (name is identical on all major OEMs; on Samsung, dialling *#9900# and enabling "Bluetooth event logging" may also be required).
3. Note your phone model + Android version here: ______________

### 9.2 Locate the log file (varies by OEM — try in order)

| # | Path | Applies to |
|---|---|---|
| 1 | `[internal storage]/btsnoop_hci.log` | older / near-stock builds |
| 2 | `[internal storage]/Android/data/btsnoop_hci.log` | some Android 10-11 builds |
| 3 | `/data/misc/bluetooth/logs/btsnoop_hci.log` | stock/Pixel; requires `adb root` or bugreport |
| 4 | `[internal storage]/MIUI/debug_log/common/btsnoop_hci.log` | Xiaomi MIUI |
| 5 | extract from `adb bugreport` zip: `FS/data/misc/bluetooth/logs/btsnoop_hci.log` | universal fallback (works on Samsung One UI and any non-rooted phone) |

Notes: the file is often only flushed after toggling Bluetooth off/on or rebooting — if a path exists but looks stale or empty, toggle BT and repeat the capture.

### 9.3 Capture a pairing + readout

4. In Android Bluetooth settings, **unpair/unforget** the M7 (it can serve only one app at a time; docs/omron.md §Phase 4).
5. Open Omron Connect, start pairing: hold the M7's Bluetooth button 3-5 s until "-P-" blinks on the display; finish pairing in the app until "[OK]" shows.
6. Take **one blood pressure measurement** (so there is a fresh record), then sync in Omron Connect so the record transfers.
7. Toggle Bluetooth off/on (flushes the log), then retrieve the log per §9.2.

### 9.4 Analyze in Wireshark

8. Open the log in Wireshark. Filter by your device: `bluetooth.addr == <MAC>` (find the MAC in Android — Bluetooth — paired devices).
9. Find and screenshot each frame below (screenshots become [CAPTURE-1..N] evidence):

| # | What to find | Filter hint |
|---|---|---|
| 1 [CAPTURE-1] | SMP pairing exchange (Just Works) | `btsmp` |
| 2 [CAPTURE-2] | Unlock char: key programming 02 00..00, response 82 00 | btatt |
| 3 [CAPTURE-3] | Key write `00 <16 bytes>`, response `80 00` | `btatt` |
| 4 [CAPTURE-4] | Session start `08 00 00 00 00 10 00 18`, response `8000` | `btatt` |
| 5 [CAPTURE-5] | Settings read `08 01 00 00 10 ..` (read @0x0010) | `btatt` |
| 6 [CAPTURE-6] | Record reads (USER1 `0x0098` / USER2 `0x06D8`) | `btatt` |
| 7 [CAPTURE-7] | Session end `08 0f 00 00 00 00 00 07`, response `8f 00` + status | `btatt` |
| 8 [CAPTURE-8] | Any `2A35` indications (expected absent or one-shot) | `btatt` |

Observed `[CAPTURE]` (2026-10-24): CAPTURE-1 = legacy Just Works SMP, no ECDH (§2);
CAPTURE-2/3 absent — no registration writes occurred (§3.2 note); CAPTURE-4/5/6 byte-exact as
specified (§4.1, §5); CAPTURE-7 = `8f00` with count=0, NO status byte (§4.1); CAPTURE-8 flipped
— `2A35` indications DO occur during live measurements (§1.3).

10. Checklist against this spec:
    - [ ] Frame layouts match section 4.2 (size byte, type, address, count, XOR-CRC)
    - [ ] Key write payload length: 16 bytes? (settles discrepancy #1)
    - [ ] Settings write-back address is `0x0054`, not `0x0010`
    - [ ] A real record decodes with section 6 layout and matches the M7 display
    - [ ] `[OK]` appeared after key programming

### 9.5 Redaction

11. Before committing or sharing anything: remove the log from the working tree; keep only screenshots with the BD address and key bytes blurred or cropped.

## 10. Known Gaps

- Registration flow (§3.2) unexercised on-device: no client ever wrote opcodes `02` or `00` to
  the unlock characteristic; the `00 || key` write is the single unexercised constant.
- Unlock response (§3.3): a 17-byte notification starting `81 00` — the trailing 15 bytes are
  unknown.
- `8858eb40-…` characteristic (§1.2): present in the proprietary service, props `0x10` (notify);
  purpose unknown.
- `5df5e817-…` service (§1.1): handles 0x0600–0x0622 (`2A52` write, `c195da8a-…` indicate);
  purpose unknown.
- Whether unlock-without-registration (§3.3) holds outside `-P-` transfer mode is untested.
- Rx response padding semantics (§4.5): 16-byte padded units are documented and the
  channel-index reassembly rule is verified, but the byte-level padding origin is unverified.

## 11. Capture Amendments (2026-10-24)

Session summary: one HEM-7361T-ALRU, two clients — MEDM Health (standard profile: pairing,
GATT discovery, `2A2B` time sync, `2A35` indications) and com.zamotkin.extrahealthgarmin
2.2.0-omron-dev (proprietary flow: unlock, session frames, EEPROM readout, unread reset). Every
`[CAPTURE]` marker in this spec traces to that session. Local evidence, kept out of the repo:
`~/omron-capture/logs/{baseline,b1,b2}` (raw btsnoop logs) and
`~/omron-capture/evidence/{b1-findings.md,medm-session-full.txt}` (redacted findings). Raw logs
stay out of the repository per the redaction rule (§9.5): they contain BD addresses and key
material, and this document cites neither — the advertising-name suffix (§1.3) is the citable
device identifier.

## 12. Pairing-Key Origin Research (task tefal2garmin-app-redesign-v3-xlg.17, 2026-10-26)

Question investigated: does a community-documented derivation exist that maps the device serial
number (DIS `0x180A` / Serial Number String `0x2A25`) onto the 16-byte application pairing key
(§3) — i.e. can the key be computed instead of invented? Investigated against the omblepy
lineage, the ESPHome Omron external components, and the Home Assistant community write-ups.

**Conclusion: no serial→key derivation exists in any examined implementation. The pairing key is
invented by the client, not derived from the serial. The research contradicts the derivation
hypothesis; the app therefore implements the evidenced path — a once-generated random 16-byte key
per §3.1/§3.3 — and manual entry stays as an advanced fallback.** Evidence:

| # | Source | What it shows |
|---|---|---|
| 1 | omblepy `omblepy.py` (pinned SHA, see Source Baseline) — global `pairingKey` | `deadbeaf12341234deadbeaf12341234  #arbitrary choice`; set only via CLI `-k/--key` ("Pairing key as a 32-character hex-string"). No code path reads the serial number, DIS, or `0x2A25`; grep for "serial"/"2A25"/"Device Information" over the file is empty. |
| 2 | [dzikus/esphome-omron](https://github.com/dzikus/esphome-omron) README (ESPHome external component, 248 model names / 37 profiles) | Explicit: "The key is yours to invent. It is not issued by Omron and there is nothing to look up: pick 32 hex characters" — the user-chosen `bindkey` is written to the cuff during first pairing; also "cuffs drop the bond of hosts that pair without registering". The component reads Device Information (model/firmware/serial entities) for metadata and profile auto-detection only — never for the key. |
| 3 | [userx14/omblepy](https://github.com/userx14/omblepy) README — "Pairing for Universal Blood Pressure Manager (UBPM)" section | First pairing is the `-p` write procedure (§3.2); the README treats the key value as a black box the tool writes. No derivation; success just binds the device to the host's MAC ("your omron device is now bound to the mac address of your pc and UBPM should work without mac address spoofing"). |
| 4 | [Home Assistant community — Omron BLE BPM (HEM-7142T2) custom integration](https://community.home-assistant.io/t/omron-ble-bpm-hem-7142t2-custom-integration/1003286) | Newer cuffs (HEM-7142T2) use plain SMP bonding with no application key at all; the separate BP5465/HEM-7386T1 discussion there describes an application-level registration write (38-byte @0x0058 + 16-byte @0x0088) — a registration payload, still not serial-derived. |
| 5 | This spec §3.3 `[CAPTURE]` (2026-10-24, this exact HEM-7361T-ALRU) | Unlock `01 || key(16)` answered `8100` with an arbitrary, never-registered key — an unregistered M7 Intelli IT accepts any 16-byte key, so a client-invented key is sufficient in the unregistered state. |
| 6 | This spec §1.3 `[CAPTURE]` | The advertised name already ends in the 12-hex serial (== public BD address) and the standard-profile session exposes it via DIS; community clients (row 2) use it for identification, none for keying. |

Applicability to the M7 Intelli IT (HEM-7361T): rows 1–3 cover this device family directly
(omblepy's `hem-7361t.py` driver is this spec's source baseline); rows 4–6 corroborate that the
key-in-the-client model, not a derivation, is the family's design. An "OpenOmron" project under
that literal name was searched for and does not exist (GitHub 404); the lineage behind the name is
omblepy (row 1) and its ESPHome descendant (row 2).

Implemented behavior (task xlg.17, this repo): the add-source flow asks for NO key. On save, the
app generates one random 16-byte key per install via the existing `OmronKeyStore.getOrCreate()`
(§3.1 semantics; unit-tested in `OmronKeyStoreTest`) and unlocks with it — valid per row 5 for an
unregistered monitor. If the monitor was previously REGISTERED by another client (e.g. paired
through UBPM/Omron Connect with their key, §3.2), unlock with our generated key would fail; that
case keeps manual key entry as a collapsed ADVANCED fallback in the pairing step
(`submitPairingKey` path unchanged). The DIS serial read needs no protocol change: with no
derivation existing, a serial read would produce no key material, so none was added (the DIS map
in §1.1 stands as-is). Real-hardware confirmation of the generated-key unlock rides with the
epic's on-device verification task (.10), per R11.

*Medical disclaimer: this document supports software integration only; it is not medical advice.*

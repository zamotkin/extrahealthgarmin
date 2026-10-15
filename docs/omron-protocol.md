# Omron M7 Intelli IT (HEM-7361T-ALRU) — Verified BLE/EEPROM Protocol Specification v0.9

> Status: **v0.9 — derived from reference source, pending on-device capture verification.**
> Every constant below carries a citation. Markers:
> `[SRC]` — derived from the omblepy reference implementation (pinned SHA, see Source Baseline).
> `[UNVERIFIED-on-device]` — not derivable from source; must be confirmed against a real
> btsnoop capture before epic `tefal2garmin-xtg` (BLE core) relies on it.

## Source Baseline

| Source | Pinned reference | Access date |
|---|---|---|
| omblepy reference implementation | https://github.com/userx14/omblepy @ commit `f323d5ae58693cc737c81c1a1e7ff6ac0679a4664` (main HEAD) | 2026-10-15 |
| — protocol core | `omblepy.py` — symbols: `bluetoothTxRxHandler`, `LEGACY_*_UUID` constants, `startTransmission`, `endTransmission`, `_readBlockEeprom`, `_writeBlockEeprom`, `writeNewUnlockKey`, `unlockWithUnlockKey` | 2026-10-15 |
| — shared device driver | `sharedDriver.py` — symbols: `sharedDeviceDriverCode`, `getRecords`, `resetUnreadRecordsCounter`, `calcRingBufferRecordReadLocations`, `_getReadCommands_OnlyNewRecords` | 2026-10-15 |
| — device config | `deviceSpecific/hem-7361t.py` — class `deviceSpecificDriver` (M7 Intelli IT / HEM-7361T) | 2026-10-15 |
| BLE reversing methodology | https://reverse-engineering-ble-devices.readthedocs.io/en/latest/protocol_reveng/00_protocol_reveng.html (docs/omron.md ref 1) — HCI snoop + ATT framing background | 2026-10-15 |

How to re-verify: clone omblepy, `git checkout f323d5ae58693cc737c81c1a1e7ff6ac0679a4664`,
check the cited symbols. If a symbol moved/renamed on main, record the rename here.

## 1. GATT Map

### 1.1 Standard services (from docs/omron.md §GATT, uncontested)

| Service | UUID | Notes |
|---|---|---|
| Generic Access | `0x1800` | device name, appearance |
| Generic Attribute | `0x1801` | |
| Device Information | `0x180A` | firmware/serial |
| Battery | `0x180F` | |
| Current Time | `0x1805` | |
| Blood Pressure | `0x1810` | root medical service |

### 1.2 Proprietary Omron data channel `[SRC]`

| Role | UUID |
|---|---|
| Parent service | `ecbe3980-c9a2-11e1-b1bd-0002a5d5c51b` |
| Unlock characteristic | `b305b680-aee7-11e1-a730-0002a5d5c51b` (write + notify) |
| RX channels (device-to-phone) | `49123040-aee8-11e1-a74d-0002a5d5c51b`, `4d0bf320-aee8-11e1-a0d9-0002a5d5c51b`, `5128ce60-aee8-11e1-b84b-0002a5d5c51b`, `560f1420-aee8-11e1-8184-0002a5d5c51b` |
| TX channels (phone-to-device) | `db5b55e0-aee7-11e1-965e-0002a5d5c51b`, `e0b8a060-aee7-11e1-92f4-0002a5d5c51b`, `0ae12b00-aee8-11e1-a192-0002a5d5c51b`, `10e1ba60-aee8-11e1-89e5-0002a5d5c51b` |

Citation: `omblepy.py` — `LEGACY_PARENT_SERVICE_UUID`, `LEGACY_DEVICE_RX_CHANNEL_UUIDS`,
`LEGACY_DEVICE_TX_CHANNEL_UUIDS`, `LEGACY_DEVICE_UNLOCK_UUID`.

`[UNVERIFIED-on-device]` which of the 4 RX/TX channel pairs the HEM-7361T-ALRU actually
instantiates — omblepy probes by handle mapping (`_buildRxHandleMap`). The Kotlin client must
enumerate actual characteristics rather than assume all 4.

## 2. SMP Bonding

- Model: BLE "Just Works" passkey-less pairing with ECDH key exchange — standard OS-level
  bonding. `[SRC]` indirect: `omblepy.py` `writeNewUnlockKey` comment — enabling RX-channel
  notification *triggers the device to send an SMP Security Request*, which kicks off pairing.
- Access to medical characteristics is gated: reads/writes without bonding/app-level unlock
  fail with `GATT_INSUFFICIENT_AUTHENTICATION` / `GATT_ERROR 133`-class failures
  (docs/omron.md §Pairing, §State machine; uncontested background).
- OS bonding alone is NOT sufficient — the application-level key (§3) is additionally required
  on every session. `[SRC]` — `unlockWithUnlockKey` is called unconditionally in `getRecords`.

`[UNVERIFIED-on-device]`: exact bonding trigger order on Android (whether the pairing dialog
appears on first RX-notify enable, or on first authenticated read attempt).

## 3. Application-Level Pairing Key (registration & unlock)

### 3.1 Key properties `[SRC]`

- Length: **16 bytes** (32 hex chars). `omblepy.py` `pairingKey` default
  `deadbeaf12341234deadbeaf12341234`, `writeNewUnlockKey` raises unless `len == 16`.
- Value: arbitrary; generated once per host, then constant. Not derived from any secret.
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

### 3.3 Unlock flow (every session) `[SRC]` — `unlockWithUnlockKey`

1. Enable notify on the unlock characteristic.
2. Write `0x01 || key(16 bytes)` (17 bytes, with response).
3. Success = response starts with `8100`; anything else = key mismatch / not registered.

### 3.4 Discrepancy vs docs/omron.md

docs/omron.md §Phase 3 claims a "38-byte registration block" written to "EEPROM starting at
address 0x0010". Per omblepy: the key is 16 bytes and goes through the unlock characteristic;
address 0x0010 is the **settings read block** (§5), which is read-not-written during unlock.
**No source evidence for 38 bytes exists.** `[UNVERIFIED-on-device]` — the capture must settle
whether the ALRU variant differs; until then the Kotlin client implements the 16-byte flow.

## 4. Session Protocol (EEPROM readout)

### 4.1 Session lifecycle `[SRC]` — `startTransmission` / `endTransmission`

| Step | Frame (hex) | Meaning |
|---|---|---|
| Session start | `08 00 00 00 00 10 00 18` | FIXED frame: type `0000`, address `0x0000`, count field = magic `0x10`, XOR-CRC `0x18` |
| Session end | `08 0f 00 00 00 00 00 07` | type `0f00`, address `0x0000`, count `0x00`, XOR-CRC `0x07` |

Per-frame variants (Task 3 amendment, 2026-10-15): the session start is a fixed literal whose
count field carries magic `0x10` (the parser must NOT treat it as a data length); a read
command puts the read size in the count field and has no data bytes (`08 01 00 <addr:2> <size> 00 <crc>`); only write commands carry data (`N = 6 + M + 2`).

Expected responses: `8000` for start; `8f00` for end (its first data byte is a device status
code — must be 0; omblepy special-cases `8f00` to expose this status, `_callbackForRxChannels`).

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

### 5.1 Unread-records counter (8 bytes at settings[0x00:0x10]) `[SRC]` — `_getReadCommands_OnlyNewRecords`

Per user *i* (little-endian device): `lastWrittenSlot_i` = high byte of the 16-bit field at bytes `[2i : 2i+2]`; `unreadRecords_i` = high byte of the field at `[2i+4 : 2i+6]`. Ring-buffer geometry: `calcRingBufferRecordReadLocations` handles the wrap-around case (`lastWrittenSlot < unreadRecords` = two reads).

Reset after successful readout `[SRC]` — `resetUnreadRecordsCounter`: write `0x8000` ("no new records" code) into both 16-bit fields per user, then write the section back.

### 5.2 Time sync (16 bytes at settings[0x2C:0x3C]) `[SRC]` — `deviceSpecific_syncWithSystemTime`

bytes `[0:8]` copied from EEPROM unchanged; `[8:14]` = `year-2000, month, day, hour, minute, second`; `[14]` = sum of all previous bytes AND `0xFF`; `[15]` = `0x00`.

## 6. Record Layout (16 bytes, little-endian bit numbering) `[SRC]` — `deviceSpecific_ParseRecordFormat`

Bit 0 = LSB of byte 0 (`deviceEndianess = "little"`, `_bytearrayBitsToInt`).

| Bits | Field | Transform |
|---|---|---|
| 68-73 | minute | as-is |
| 74-79 | second | `min(second, 59)` — device may report up to 63 |
| 80 | mov (movement flag) | as-is |
| 81 | ihb (irregular heartbeat flag) | as-is |
| 82-85 | month | as-is |
| 86-90 | day | as-is |
| 91-95 | hour | as-is |
| 96-97 | unused | — |
| 98-103 | year | `+ 2000` |
| 104-111 | pulse (bpm) | as-is |
| 112-119 | diastolic | as-is |
| 120-127 | systolic | **+ 25** |

Empty slot marker: all bytes `0xFF` = skip (`sharedDriver.getRecords`). Undecodable records are skipped with a warning, not fatal.

**Discrepancy vs docs/omron.md resolved**: measurements are **plain bit-packed integers, NOT IEEE 11073 SFLOAT**. SFLOAT applies only to the standard `0x2A35` Blood Pressure Measurement characteristic (docs/omron.md §Decoding mixed sources). The EEPROM path never uses SFLOAT.

## 7. Discrepancy Table (docs/omron.md vs verified findings)

| # | docs/omron.md claim | Verified finding | Status |
|---|---|---|---|
| 1 | 38-byte registration key written to EEPROM @0x0010 | 16-byte key via unlock characteristic; 0x0010 is settings read block | **OPEN** — no source evidence for 38B; capture must confirm ALRU behavior |
| 2 | Session start command `0x08` | `0x08` is the frame-size byte; command type is `0000` | **RESOLVED** (omblepy.py `startTransmission`) |
| 3 | Measurements are SFLOAT (IEEE 11073-20601) | EEPROM records: plain bit-packed ints (sys = byte15 + 25) | **RESOLVED** (hem-7361t.py `deviceSpecific_ParseRecordFormat`) |
| 4 | Read/write/end commands `0x0100 / 0x01c0 / 0x0f00` | Confirmed as frame types | **RESOLVED** |
| 5 | Proprietary channel UUIDs | Confirmed (parent/unlock/RX x4/TX x4) | **RESOLVED** (omblepy.py `LEGACY_*`) |
| 6 | Two users USER1/USER2 | Confirmed: 2 slots x 100 records | **RESOLVED** (hem-7361t.py) |
| 7 | Settings "read and sent back to EEPROM starting at 0x0010" | Read @0x0010, **written @0x0054** | **RESOLVED** — write address differs, critical for implementation |

## 8. Decision Log (binding for epic tefal2garmin-xtg)

1. Implement the **16-byte key** flow (§3) as the primary path. Rationale: only sourced implementation; the 38-byte variant has zero source evidence. Revisit only if the task-2 capture proves ALRU diverges.
2. Decode EEPROM records with the **plain-integer bit layout** (§6). SFLOAT decoder allowed only as a tested, unused-by-default utility for `0x2A35`.
3. GATT operations strictly callback-serialized; per-command retry 5x with 1 s timeout (mirrors `_waitForRxOrRetry`).
4. Enumerate actual RX/TX characteristics at runtime; do not assume all 4 channels exist.
5. Settings cache / read-modify-write / reset flow per §5 exactly (read @0x0010, write @0x0054).
6. Skip all-`0xFF` slots; skip (log) undecodable records; clamp second to 59.

## 9. Capture Runbook (task 2 — on-device verification)

Target reader: device owner, no developer background. Do NOT commit the captured log or any key material. Redact BD address and key bytes before sharing.

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

10. Checklist against this spec:
    - [ ] Frame layouts match section 4.2 (size byte, type, address, count, XOR-CRC)
    - [ ] Key write payload length: 16 bytes? (settles discrepancy #1)
    - [ ] Settings write-back address is `0x0054`, not `0x0010`
    - [ ] A real record decodes with section 6 layout and matches the M7 display
    - [ ] `[OK]` appeared after key programming

### 9.5 Redaction

11. Before committing or sharing anything: remove the log from the working tree; keep only screenshots with the BD address and key bytes blurred or cropped.

## 10. Known Gaps

- Discrepancy #1 (38-byte key claim): no source evidence either way for ALRU; the capture decides.
- Which RX/TX channel pair the ALRU instantiates (section 1.2).
- Whether the ALRU RU-variant EEPROM layout equals hem-7361t.py exactly (region risk).
- Whether bonding on Android triggers at RX-notify enable (as omblepy expects) or earlier.
- CRC detail of the settings write-back: time-sync byte 14 uses sum AND 0xFF (hem-7361t.py); the unread-reset write relies on frame XOR only — confirm in capture.

*Medical disclaimer: this document supports software integration only; it is not medical advice.*

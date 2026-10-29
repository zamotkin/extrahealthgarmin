# Bluetooth SIG Blood Pressure Service (0x1810) — Generic BLE Driver Specification v0.2 (draft)

> Status: **v0.2 draft — SIG-verified layout, community-verified behavior, driver v1 implemented.** Every UUID, byte and
> bit below is verified against the official Bluetooth SIG documents (Blood Pressure Service 1.1.1,
> Blood Pressure Profile 1.1.1, GATT Specification Supplement, Assigned Numbers; all fetched
> 2026-10-25). Device behavior is NOT capture-verified by us — the quirks matrix (§4.2) is built
> from pinned community reports only (§6.2). Markers:
> `[SIG]` — verified against the SIG specification documents (§6.1).
> `[COMMUNITY]` — verified against pinned community sources (§6.2); no on-device capture of ours.
> `[UNVERIFIED]` — plausible claim with NO pinned source; treat as assertion, not fact.
> `[CAPTURE]` — cross-reference to the one on-device capture we own: docs/omron-protocol.md §1.3.
>
> v0.1 (2026-10-25): SIG-verified spec draft. v0.2 (2026-10-25, task
> tefal2garmin-app-redesign-v3-xlg.9): the layout tables re-verified against the pinned GSS
> build by direct text extraction (Tables 2.1, 3.52-3.55, 3.144 confirmed byte-for-byte; no
> corrections), and section 7 added - the implementation notes of the shipped driver
> (devices/ble1810/).
>
> This document is the spec of the **generic standard-GATT driver** for consumer
> blood-pressure monitors. The vendor driver for the Omron M7 Intelli IT proprietary protocol is
> specified in docs/omron-protocol.md; the two are complementary (§1.2, §5).

## Source Baseline

| Source | Pinned reference | Access date |
|---|---|---|
| Blood Pressure Service 1.1.1 (BLS) | https://www.bluetooth.com/specifications/specs/blood-pressure-service-1-1-1/ — HTML: https://www.bluetooth.com/specifications/specs/html/?src=BLS_v1.1.1/out/en/index-en.html (Revision v1.1.1, 2022-01-18; Errata Correction 23224 mandatory) | 2026-10-25 |
| Blood Pressure Profile 1.1.1 (BLP) | https://www.bluetooth.com/specifications/specs/blood-pressure-profile-1-1-1/ — HTML: https://www.bluetooth.com/specifications/specs/html/?src=BLP_v1.1.1/out/en/index-en.html (Revision v1.1.1, 2022-01-18) | 2026-10-25 |
| GATT Specification Supplement (GSS) | https://www.bluetooth.com/specifications/gss/ — PDF: https://btprodspecificationrefs.blob.core.windows.net/gatt-specification-supplement/GATT_Specification_Supplement.pdf (build 2026-09-09; §2.1.1, §3.33, §3.34, §3.35, §3.80, §3.140, §3.200) | 2026-10-25 |
| Assigned Numbers | https://www.bluetooth.com/specifications/assigned-numbers/ — HTML: https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/Assigned_Numbers/out/en/index-en.html (service/characteristic/descriptor UUIDs) | 2026-10-25 |
| derdilla/blood-pressure-monitor-fl | https://github.com/nobodyfornothing/blood-pressure-monitor-fl (redirects to /derdilla/) — docs/bluetooth.md (community device table) | 2026-10-25 |
| — issue #80 | https://github.com/derdilla/blood-pressure-monitor-fl/issues/80 ("Support Bluetooth LE blood pressure monitor devices"; closed 2024-06-23, merged via PR #330) | 2026-10-25 |
| — A&D UA-651BLE GATT dump | https://github.com/HEADS-project/ThingML-BLE/blob/master/Documentation/AND_Medical_UA651BLE.md | 2026-10-25 |

How to re-verify: the SIG HTML links open without login; the GSS PDF section numbers cited above
are stable in the 2026-09-09 build. If the SIG renumbers sections, re-check §2–§3 of this document
against the new build and update this table.

## 1. Overview

### 1.1 What the service is

- The Blood Pressure Service (BLS) is the SIG-adopted GATT service that "exposes blood pressure and
  other data from a blood pressure monitor for use in consumer and professional healthcare
  applications" `[SIG]` — BLS summary.
- Roles `[SIG]` — BLP 1.1.1: the **Blood Pressure Sensor** (the monitor; "shall be a GATT Server",
  "shall use the GAP Peripheral role") and the **Collector** (the phone app; "shall be a GATT
  Client", "shall use the GAP Central role").
- Transport: "This service shall operate over LE transport only" `[SIG]` — BLS §2; BR/EDR/HS use
  the legacy Health Device Profile instead.
- Profile constraints `[SIG]` — BLP 1.1.1: the Sensor "shall instantiate one and only one Blood
  Pressure Service" (Primary) and "shall have one instance of the Device Information Service"; the
  profile depends on the Device Time Profile (Device Time Service optional on the Sensor).
- Security: BLS Table 3.1 lists "Security Permissions: None" for every characteristic — the
  service imposes NO bonding or encryption requirement; pairing/bonding is device policy, not
  service policy `[SIG]`.

### 1.2 Relation to the Omron M7 vendor driver

The app's existing driver (docs/omron-protocol.md) speaks Omron's proprietary service for the M7
Intelli IT. The same M-series hardware ALSO delivers live readings over the standard profile —
`0x2A35` indications with exactly the §3 layout `[CAPTURE]`. The X-series consumer line
(X7 Smart / X4 Smart / X2 Smart+ / Bronze BP5150) is the opposite case: standard-GATT only, no
proprietary service, and it is the target population of THIS driver `[COMMUNITY]`.

### 1.3 Device families

Verified-implementing `0x1810` (community-reported; per-device quirks in §4.2) `[COMMUNITY]`:

| Family / model | Advertised name | Notes |
|---|---|---|
| A&D UA-651BLE / UA-651CNBLE | — | full GATT dump + worked `0x2A35` example (§3.7) |
| Beurer BM59 / BM85 | `BM 59` / `Beurer BM85` | "Reads multiple" |
| Omron X7 Smart | — | issue #80 user confirmation |
| Omron X4 Smart | `X4 Smart` | DELETES stored records after transfer |
| Omron X2 Smart+ | `X2 Smart+` | — |
| Omron Bronze BP5150 | `BLESmart_<id>` | same `BLESmart_` naming as the M-series |
| boso medicus (CE6674) | `boso medicus CE6674` | `0x1810` + `0x2A49` + `0x2A08`; quirks §4.2 |
| Silvercrest / HealthForYou SBM 69 | `SBM69` | "Reads multiple" |
| Medisana BU 572 | `BU 572` | standard GATT, "legacy only" |

NOT usable over `0x1810`:

| Family | Why not |
|---|---|
| Withings BPM family | Vendor-locked: syncs only through the Withings app/cloud; no public GATT documentation and no third-party driver exists. **`[UNVERIFIED]`** — no pinned community source found (2026-10-25); treat as assumption and re-test on hardware before relying on it |
| Microlife BP3GY1-2N | Microlife protocol, not standard GATT `[COMMUNITY]` |
| Yonker YK-IBPA1 / YK-BPW5, Yongrow YK-IBPA1, METIKO MT-YK-BPA1 | Yonker protocol, not standard GATT `[COMMUNITY]` |

## 2. Service Table

Service `[SIG]` — Assigned Numbers + BLS §2 + BLP 1.1.1:

| Item | Value |
|---|---|
| Service UUID | `0x1810` — "Blood Pressure Service" |
| Declaration | "recommended to be instantiated as a «Primary Service»" |
| Transport | LE only |
| Instances per Sensor | one and only one |

Characteristics `[SIG]` — BLS 1.1.1 Table 3.1; UUIDs — Assigned Numbers:

| Characteristic | UUID | Requirement | Mandatory properties | Notes |
|---|---|---|---|---|
| Blood Pressure Measurement | `0x2A35` | M | Indicate | measurement payload — §3 |
| — CCCD | `0x2902` | M | Read, Write | enables the indications |
| Intermediate Cuff Pressure | `0x2A36` | O | Notify | live cuff pressure during a measurement (§2.2) |
| — CCCD | `0x2902` | C.1 | Read, Write | "Mandatory if Intermediate Cuff Pressure characteristic is supported, otherwise excluded for this service" |
| Blood Pressure Feature | `0x2A49` | M | Read | feature bitmap (§2.1); Indicate is C.5 |
| RACP | `0x2A52` | C.2 | Indicate, Write | history readout (§2.3); absent on consumer units in practice `[COMMUNITY]` |
| Blood Pressure Record | `0x2B36` | C.3 | Notify | "shall be supported when the RACP characteristic is supported" |
| Enhanced Blood Pressure Measurement | `0x2B34` | C.2, C.4 | Indicate | out of scope for this driver |
| Enhanced Intermediate Cuff Pressure | `0x2B35` | O | Notify | out of scope for this driver |

C-conditions, verbatim `[SIG]` — BLS 1.1.1:

- **C.1** — "Mandatory if Intermediate Cuff Pressure characteristic is supported, otherwise
  excluded for this service."
- **C.2** — "At least one of the following items shall be supported by an Enhanced Blood Pressure
  Service instance": Enhanced Blood Pressure Measurement / User Data Service / RACP / User Facing
  Time. (An "Enhanced Blood Pressure Service instance" is a BPS instance supporting at least one
  of those enhanced feature elements.)
- **C.3** — "The Blood Pressure Record characteristic shall be supported when the RACP
  characteristic is supported."
- **C.4** — "The Enhanced BP Measurement characteristic shall be supported when User Facing Time
  is supported, otherwise optional."
- **C.5** — "The Indicate property shall be supported for the Blood Pressure Feature
  characteristic if the device supports bonding" and "if the value of the Blood Pressure Feature
  characteristic can change over the lifetime of the device, otherwise excluded for this Service."
- Otherwise: "only one instance of each characteristic is permitted within each instance of this
  service"; enabling indications on two characteristics at once can fail with the
  Client Characteristic Configuration Descriptor Improperly Configured error.

### 2.1 Blood Pressure Feature bits (0x2A49) `[SIG]` — GSS §3.33, Table 3.51

2 octets, `boolean[16]`:

| Bit | Feature |
|---|---|
| 0 | Body Movement Detection Support |
| 1 | Cuff Fit Detection Support |
| 2 | Irregular Pulse Detection Support |
| 3 | Pulse Rate Range Detection Support |
| 4 | Measurement Position Detection Support |
| 5 | Multiple Bond Support |
| 6 | E2E-CRC Support |
| 7 | User Data Service Support |
| 8 | User Facing Time Support |
| 9–15 | Reserved for Future Use |

A Measurement Status bit (§3.5) may be set only when the matching Support bit here is 1; the
Feature value is "static during a connection", and a bonded Collector shall enable its
indications or re-read it on every reconnection (changed features are indicated to bonded
Collectors) `[SIG]` — BLS §3.3, BLP §4.6.

### 2.2 Intermediate Cuff Pressure (0x2A36) `[SIG]` — BLS §3.2, GSS §3.140

- "used to send Current Cuff Pressure values to a device for display" while a measurement is in
  progress; "typical update intervals range from 0.25 seconds to 2 seconds".
- Same format as `0x2A35`, but the Systolic slot carries the current cuff pressure and the
  Diastolic and MAP slots are unused and "set to the special value NaN" (`0x07FF`, §3.2).
- "the Time Stamp and Pulse Rate fields should not be used" here; on a late connection "only the
  last cached value should be sent once the link is established"; once the measurement is stable,
  ICP notifications stop and `0x2A35` is indicated.
- Out of scope for this driver's data path (no UI use); enumerated for completeness.

### 2.3 RACP (0x2A52) summary `[SIG]` — BLS §3.6, GSS §3.200

Op Codes: `0x01` Report Stored Records (M), `0x02` Delete Stored Records (O), `0x03` Abort
Operation (O), `0x04` Report Number of Stored Records (M), `0x05` Number of Stored Records
Response, `0x06` Response Code (M), `0x07`/`0x08` Combined Report (+Response). Operators: `0x00`
Null, `0x01` All Records, `0x02` <=, `0x03` >= (Sequence Number operand, M), `0x04` within
range, `0x05` First (oldest), `0x06` Last. Response Codes `0x01` Success through `0x06` No
Records Found.

Operational rules: the sensor "should be able to store at least 100 data measurements"; stored
data is transmitted "in FIFO order" (oldest first); an ATT transaction times out after 30 seconds
and a timed-out or interrupted procedure fails without resuming. A RACP-backed backfill therefore
exists on paper but consumer monitors rarely implement RACP at all - see §5 `[COMMUNITY]`.

### 2.4 Related services

| Service / characteristic | UUID | Status |
|---|---|---|
| Device Information Service | `0x180A` | Mandatory on the Sensor per BLP 1.1.1 ("shall have one instance"); firmware/model reads for diagnostics `[SIG]` |
| Date Time characteristic | `0x2A08` | The timestamp FORMAT source (§3.4); observed on UA-651BLE (read+notify) and in the boso GATT dump `[COMMUNITY]`; the time-SETTING path per BLP 1.1.1 is the optional Device Time Service — its UUID not verified here, not needed by this driver |
| Current Time Service | `0x1805` | Observed in the wild (the M7 exposes `0x2A2B` under it `[CAPTURE]` — omron-protocol.md §1.1) — not required by BLP 1.1.1 `[SIG]` |
| User Data Service | — | Optional (Feature bit 7); absent on all community-verified consumer monitors `[COMMUNITY]` — basis of the §5 multi-user limitation |

## 3. Measurement Payload Layout (0x2A35)

### 3.1 Field order `[SIG]` — GSS §3.34, Table 3.52

| # | Field | Type | Size (octets) | Present when |
|---|---|---|---|---|
| 1 | Flags | boolean[8] | 1 | always |
| 2 | Systolic | medfloat16 (SFLOAT) | 2 | always |
| 3 | Diastolic | medfloat16 | 2 | always |
| 4 | Mean Arterial Pressure (MAP) | medfloat16 | 2 | always |
| 5 | Time Stamp | struct (Date Time, §3.4) | 7 | Flags bit 1 = 1 |
| 6 | Pulse Rate | medfloat16 | 2 | Flags bit 2 = 1 |
| 7 | User ID | uint8 | 1 | Flags bit 3 = 1 |
| 8 | Measurement Status | boolean[16] | 2 | Flags bit 4 = 1 |

- All multi-byte fields are little-endian `[SIG]`; confirmed byte-level by the §3.7 example.
- Units switch together on Flags bit 0: `0` = mmHg (Systolic/Diastolic/MAP), `1` = kPa (base unit
  pascal, M=1, d=3, b=0 — the raw integer is kPa) `[SIG]`.
- Longest possible value = 1+6+7+2+1+2 = 19 octets — inside the default ATT bearer value limit
  (20), so a Collector never needs MTU negotiation `[SIG]`-derived.
- medfloat16 == the "SFLOAT" of IEEE 11073-20601 `[SIG]` — GSS §2.1. NOT the Omron EEPROM record
  format, which is plain bit-packed integers (omron-protocol.md §6); SFLOAT applies only to this
  standard characteristic.

### 3.2 SFLOAT (medfloat16) encoding `[SIG]` — GSS §2.1.1

16-bit value = 4-bit two's-complement exponent (bits 12–15) + 12-bit two's-complement mantissa
(bits 0–11); value = mantissa x 10^exponent (per IEEE 11073-20601, referenced by the GSS; the
special values below pin the split — each sets only the high nibble or only the low 12 bits).

Special values (GSS Table 2.1, verbatim):

| Raw | Meaning |
|---|---|
| `0x07FF` | Not a Number (NaN) |
| `0x07FE` | Positive Infinity (+inf) |
| `0x0802` | Negative Infinity (-inf) |
| `0x0800` | Not at this Resolution (NRes) |
| `0x0801` | Reserved for Future Use |

`0x07FF` (decimal 2047) is the NaN sentinel — the "value unavailable" encoding the community
quirks in §4.2 hinge on.

### 3.3 Flags byte `[SIG]` — GSS §3.34.1, Table 3.53

| Bit | Definition |
|---|---|
| 0 | Blood Pressure Units — 0 = mmHg, 1 = kPa |
| 1 | Time Stamp present |
| 2 | Pulse Rate present |
| 3 | User ID present |
| 4 | Measurement Status present |
| 5–7 | Reserved for Future Use |

### 3.4 Time Stamp `[SIG]` — GSS §3.80, Table 3.144

7 bytes, in order: Year uint16 (0 = unknown; valid 1582–9999), Month uint8 (0 = unknown; 1–12),
Day uint8 (0 = unknown; 1–31), Hours (0–23), Minutes (0–59), Seconds (0–59). For the Blood
Pressure Service specifically, month/day = 0 "shall not be used"; year = 0 stays legal `[SIG]` —
BLS §3.1. Devices without a clock simply clear Flags bit 1 (§4.2).

### 3.5 Measurement Status `[SIG]` — GSS §3.34.3, Table 3.55

| Bit | Definition |
|---|---|
| 0 | Body Movement Detection — 1 = "body movement detected during measurement" |
| 1 | Cuff Fit Detection — 1 = "cuff too loose" |
| 2 | Irregular Pulse Detection — 1 = "irregular pulse detected" |
| 3–4 | Pulse Rate Range Detection — 0b00 within range, 0b01 exceeds upper limit, 0b10 below lower limit, 0b11 Reserved |
| 5 | Measurement Position Detection — 1 = "improper measurement position" |
| 6–15 | Reserved for Future Use |

A bit may be set only when the matching Support bit in Blood Pressure Feature (§2.1) is 1
`[SIG]` — BLS §3.3.1. Caveat: at least one device transmits sentinel failures with NO status bits
set (§4.2) — absent flags are not proof of a good measurement.

### 3.6 User ID `[SIG]` — GSS §3.34.2, Table 3.54

| Value | Meaning |
|---|---|
| 0x00–0xFE | "Defined by the service specification" — with the User Data Service present, the UDS user index |
| 0xFF | Unknown User |

Consumer reality: single-profile devices make 0x00 "the one profile"; most units either omit the
field or send 0xFF `[COMMUNITY]` — the boso session log shows `userID: null`.

### 3.7 Worked example (A&D UA-651BLE) `[COMMUNITY]`

Indicated value (18 bytes): `16 7D 00 50 00 66 00 E1 07 01 10 0C 27 36 40 00 00 00`

| Offset | Bytes | Field | Decode |
|---|---|---|---|
| 0 | `16` | Flags | 0b00010110 — mmHg; Time Stamp + Pulse Rate + Measurement Status present; no User ID |
| 1–2 | `7D 00` | Systolic | 0x007D = 125 mmHg |
| 3–4 | `50 00` | Diastolic | 0x0050 = 80 mmHg |
| 5–6 | `66 00` | MAP | 0x0066 = 102 mmHg |
| 7–13 | `E1 07 01 10 0C 27 36` | Time Stamp | 2017-01-16 12:39:54 |
| 14–15 | `40 00` | Pulse Rate | 0x0040 = 64 bpm |
| 16–17 | `00 00` | Measurement Status | all clear |

The M7 Intelli IT live indication (flags `0x1E`, 19 bytes) decodes with this exact table too
`[CAPTURE]` — omron-protocol.md §1.3; that document's "non-standard field order" aside is
superseded — the order it observed IS the standard GSS Table 3.52 order.

## 4. Android Read Session

### 4.1 Session flow

1. **Scan** with a service-UUID filter:
   `ScanFilter.Builder().setServiceUuid(ParcelUuid.fromUuid(UUID.fromString("00001810-0000-1000-8000-00805f9b34fb")))`.
   Permissions: `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT` on API 31+; `ACCESS_FINE_LOCATION` for
   scanning on API 30- (the app spans minSdk 26 -> targetSdk 34, so both regimes apply). Match on
   the service UUID, never on the advertised name (cf. omron-protocol.md §1.3).
2. **Connect** direct (`autoConnect=false`). Bonding: `0x1810` imposes none (§1.1) `[SIG]`, but
   the verified boso flow ran through OS pairing; call `createBond()` opportunistically and treat
   `GATT_INSUFFICIENT_AUTHENTICATION`-class failures as bond-and-retry (cf. omron-protocol.md §2).
3. **Discover**: `discoverServices()`, locate `0x1810` -> `0x2A35` (property Indicate) and its
   `0x2902`; read `0x2A49` once and log the feature bitmap (§2.1) for diagnostics.
4. **Enable indications**: write `0x02 0x00` (little-endian `0x0002` = indications) to the CCCD
   with response; proceed only after `onDescriptorWrite` confirms. Writing `0x01 0x00` by mistake
   subscribes to notifications, which `0x2A35` does not carry `[SIG]`; `[COMMUNITY]` — issue #80
   wrote exactly `[2, 0]`.
5. **Receive the burst**: each `onCharacteristicChanged` is one record; parse per §3, drop
   sentinels (§4.2). Multiple indications per session are profile-legal — "the Collector shall be
   able to receive multiple indications of the Blood Pressure Measurement characteristic ... for
   the case where the Blood Pressure Sensor has stored measurements to send" `[SIG]` — BLP.
6. **Session end**: the device usually disconnects itself right after the burst — status 19
   (0x13, remote user terminated connection) is the NORMAL end, not an error, and an immediate
   reconnect attempt can fail for a while (§4.2). Otherwise end on an idle timeout (no indication
   within the configured window). Close the GATT object on every exit path (the
   `OmronGattConnection` rule, omron-protocol.md adjacent code).
7. **Hand off**: map each record onto the blood-pressure pipeline (Room entity ->
   `uploadBloodPressure` -> journal) unchanged; dedup by fingerprint (§4.2); journal only after a
   confirmed upload — records that fail to upload stay retriable, and delete-after-transfer
   devices make a lost record unrecoverable (§5).

### 4.2 Quirks matrix (community-verified)

Maintained from community reports only (§6.2) — extend per device as reports arrive; nothing here
is `[CAPTURE]`-verified yet.

| Quirk | Observed on | Driver handling |
|---|---|---|
| Self-disconnect right after the transfer burst; the immediate auto-reconnect then fails repeatedly (status 255, GATT_ERROR / UNKNOWN_HCI_ERROR) | boso medicus CE6674 `[COMMUNITY]` — issue #80 | Treat status 19 as the normal session end; never loop reconnects; end cleanly |
| SFLOAT `0x07FF` (NaN, §3.2) in Systolic/Diastolic/MAP/Pulse | UA-651BLE streams such indications during the measurement, before the final record `[COMMUNITY]`; boso sends them for display errors ("Err", "Err CUF") — with NO Measurement Status bits set `[COMMUNITY]` — issue #80 | Skip any record whose primary fields are NaN — never store; never infer validity from status bits alone |
| Transfer semantics differ per device: latest-only vs all-stored vs delete-after-transfer | SBM69 / BM59 / BM85 "Reads multiple"; X4 Smart "Deletes after transfer"; BU 572 "legacy only" `[COMMUNITY]` — docs/bluetooth.md | No re-read assumptions: every record goes through fingerprint + journal dedup (same discipline as `UploadJournal`/`BpUploadJournal`) |
| Timestamps missing or drifting by minutes | Many units clear Flags bit 1; the boso clock runs "some minutes off" and syncs only via `0x2A08` `[COMMUNITY]` — issue #80 | Fall back to receive time; dedup on (sys, dia, MAP, pulse, status) fingerprints, never on device timestamp alone |
| Duplicate delivery of the same record | UA-651BLE sent one byte-identical record twice `[COMMUNITY]` | Absorbed by fingerprint dedup; the session also drops a byte-identical re-delivery of the immediately previous indication (7.3) |
| User ID absent or `0xFF` | boso log: `userID: null` `[COMMUNITY]` | Ignore for record identity; store as unknown (§3.6) |
| Clock drift (even when a timestamp is sent) | boso, minutes per drift report `[COMMUNITY]` | Treat device timestamps as advisory; user-visible ordering follows the app's own journal |

## 5. Limitations vs the Omron M7 Vendor Protocol

| Capability | Omron M7 vendor driver (omron-protocol.md) | 0x1810 generic driver (this spec) |
|---|---|---|
| History backfill | Full 2x100-record EEPROM ring readout (§4–§5 there) | Not reliably available: RACP is spec'd (§2.3) `[SIG]` but consumer monitors rarely implement it `[COMMUNITY]`; the driver receives only what the device pushes after the CCCD write |
| Read-integrity control | Unread counter + explicit reset after verified readout (§5.1 there) | None: delete-after-transfer devices (X4 Smart) lose data for everyone else once they push; RACP Delete is optional and absent in practice |
| Multi-user | USER1/USER2 ring buffers | User ID byte only (§3.6), meaningful only with UDS — which consumer units lack; no real multi-user support |
| Time source | Proprietary settings write-back time sync (§5.2 there) | Optional Date Time `0x2A08` / Device Time only; drift observed (§4.2) |
| Authentication | SMP Just Works + 16-byte app-level unlock key (§2–§3 there) | None at the service level (Security Permissions "None"); bonding is optional device policy |
| Evidence grade | v1.0 — btsnoop capture-verified | v0.1 — layout SIG-verified; behavior community-reported only |

The practical consequence: a delivered-but-unstored record may never be delivered again. The
driver must dedup via fingerprints, journal only after confirmed upload, and treat every session
as best-effort. The quirk matrix is maintained from community reports only — each new device
class extends §4.2.

## 6. Sources

### 6.1 SIG specifications (all accessed 2026-10-25)

| Document | URL | Sections used |
|---|---|---|
| Blood Pressure Service 1.1.1 | https://www.bluetooth.com/specifications/specs/blood-pressure-service-1-1-1/ (HTML: https://www.bluetooth.com/specifications/specs/html/?src=BLS_v1.1.1/out/en/index-en.html) | §2, §3.1–3.3, §3.6, Table 3.1, C.1–C.5 |
| Blood Pressure Profile 1.1.1 | https://www.bluetooth.com/specifications/specs/blood-pressure-profile-1-1-1/ (HTML: https://www.bluetooth.com/specifications/specs/html/?src=BLP_v1.1.1/out/en/index-en.html) | roles, Sensor/Collector requirements, §4.5, §4.6 |
| GATT Specification Supplement | https://www.bluetooth.com/specifications/gss/ (PDF: https://btprodspecificationrefs.blob.core.windows.net/gatt-specification-supplement/GATT_Specification_Supplement.pdf, build 2026-09-09) | §2.1.1 (Table 2.1), §3.33 (Table 3.51), §3.34 (Tables 3.52–3.55), §3.80 (Table 3.144), §3.140, §3.200 |
| Assigned Numbers | https://www.bluetooth.com/specifications/assigned-numbers/ (HTML: https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/Assigned_Numbers/out/en/index-en.html) | UUIDs: `0x1810`, `0x2A35`, `0x2A36`, `0x2A49`, `0x2A52`, `0x2B34`, `0x2B35`, `0x2B36`, `0x2902` |

### 6.2 Community device sources (all accessed 2026-10-25)

| Source | URL | Content |
|---|---|---|
| blood-pressure-monitor-fl (derdilla) | https://github.com/nobodyfornothing/blood-pressure-monitor-fl | the community app whose standard-GATT driver this spec mirrors |
| — docs/bluetooth.md | https://github.com/nobodyfornothing/blood-pressure-monitor-fl/blob/main/docs/bluetooth.md | the community device table (§1.3) and the transfer-semantics taxonomy (§4.2) |
| — issue #80 | https://github.com/derdilla/blood-pressure-monitor-fl/issues/80 | boso medicus + Omron X7 Smart verification; disconnect-19/255, `0x07FF` sentinel, clock drift, `userID: null` |
| — A&D UA-651BLE GATT dump | https://github.com/HEADS-project/ThingML-BLE/blob/master/Documentation/AND_Medical_UA651BLE.md | UA-651BLE service/characteristic handles and the §3.7 worked example |

## 7. Driver Implementation Notes (v1, task xlg.9, 2026-10-25)

The shipped driver lives in `app/src/main/java/com/zamotkin/extrahealthgarmin/devices/ble1810/`
and mirrors the omron/ architecture: pure, fake-able logic behind seams; `android.bluetooth`
confined to ONE glue file. Real 0x1810 hardware is exercised only in the final verification
task (epic .10); everything below is verified by unit tests over fakes.

| File | Role |
|---|---|
| `Ble1810Protocol.kt` | every constant: UUIDs, CCCD value `0x0002`, the 0x07FF sentinel word, kPa factor, the two session timeouts |
| `Ble1810MeasurementParser.kt` | pure section-3 parser: flags, SFLOAT specials, units, Date Time, User ID, Measurement Status; never throws |
| `Ble1810Session.kt` | the `Ble1810Transport` seam + the read-session state machine (burst collection, quirk absorption, arrival stamping) |
| `Ble1810FingerprintJournal.kt` | per-source dedup journal for timestamp-less records (7.2) |
| `Ble1810Client.kt` | result type + exception hierarchy + the readout facade folding failures; permission helpers |
| `Ble1810Scanner.kt` | `Ble1810ScanTransport` seam + the pure discovery scanner (bonded-first, UUID-filtered, once per address) |
| `Ble1810GattConnection.kt` | THE ONLY file importing `android.bluetooth`: GATT glue, the Android scan transport, and the best-effort bond removal |
| `Ble1810Source.kt` | `BpSource` adapter + `Ble1810BpSourceType` ("ble-standard-1810") |

### 7.1 Parser decisions

- **Units**: Flags bit 0 = kPa is normalized at parse time - mmHg only ever leaves the driver
  (kPa x 7.50062, rounded). A wire value of 16.0 kPa becomes 120 mmHg.
- **SFLOAT specials** (GSS Table 2.1): `0x07FF` (NaN) in Systolic OR Diastolic = the device's
  own failed-reading marker -> the record is skipped and counted, never stored (4.2). `0x07FF`
  in MAP or Pulse degrades only that field (null). Any other special value (+Inf/-Inf/NRes/RFU)
  in a primary field is Malformed; a negative pressure is Malformed.
- **Timestamp**: present (Flags bit 1) -> the device wall-clock digits, read as UTC (the Omron
  record-decoder convention - digits stay stable). Unusable content (year 0, February 31,
  out-of-range fields) degrades to the timestamp-ABSENT path below - the measurement values
  stay real; a broken clock never costs a record.
- **User ID**: kept informationally (0xFF = Unknown User); attribution is always the app owner
  (epic R4) - `BpReading.APP_OWNER_SLOT`.
- **Layout strictness**: the frame length must equal exactly what the flags announce; reserved
  flag bits (5-7, "shall be set to 0") make the frame Malformed.

### 7.2 Fingerprint dedup for timestamp-less devices

Devices without a clock get the READ time as record time, so a HISTORY re-read would duplicate
every row. The journal (`ble1810-<address>-fingerprints.json`, atomic writes, count-wise
merge-on-save like the Garmin upload journals) stores, per VALUE fingerprint
(`sys|dia|MAP|pulse|status|userId` - never the read time), how many identical readings were
already delivered:

- **Occurrence counting is the "relative order"**: walking a burst in arrival order, the n-th
  record with a given fingerprint is delivered only when n exceeds the journaled count. Two
  real identical readings in one burst are both delivered; a full re-push of both delivers
  nothing; a ring-buffer shift (which would break a burst-INDEX fingerprint) changes nothing.
- **Timestamp-carrying records never touch the journal**: their identity is the stable device
  time; the downstream pipeline already dedups them (Room's unique
  `(timestamp, userSlot, sourceId)` index + the Garmin upload journal) - exactly how the Omron
  HISTORY ring re-read stays idempotent.
- **The journal grows only on a successful read pass** (anchors-never-advance, epic R5): a
  failed pass leaves every record retriable.
- Known residual edge (inherent to clock-less hardware): a "latest-only" device that pushes
  one new reading byte-identical to several already-journaled ones will have it suppressed.
  Duplicated storage is the alternative; fingerprint dedup follows the 4.2 rule.

### 7.3 Session decisions

- **Two windows**: the first indication waits up to 30 s (the button-press window - some
  monitors push only after a transfer button is pressed, 4.2); after anything arrived, the
  inter-indication idle window is 10 s.
- **Session end**: the status-19 self-disconnect AND the idle timeout both end the session
  normally with the collected records. The driver never auto-reconnects (4.2); other
  disconnect statuses are logged (warn) but also end the session - the burst data already
  received is real, and the fingerprint journal makes a later re-push safe either way.
- **Early indications** (before the CCCD write confirms, 4.2) are buffered by the glue from
  connection start, so nothing is lost.
- **Duplicate delivery**: a byte-identical re-delivery of the immediately previous indication
  is dropped and counted (the UA-651BLE quirk, 4.2) - stored once.
- **Arrival-time attribution**: clock-less records get the arrival second, made unique and
  monotonic within the pass (a frozen clock yields T, T+1, T+2, ...) - two records of one burst
  never share a read timestamp, so the storage's unique index keeps every record.

### 7.4 Connection + bonding

- Connect is direct (`autoConnect = false`) to the stored address; bonding is completed
  proactively when the device is not bonded (createBond + broadcast wait, 30 s) BEFORE the
  CCCD write, and insufficient-authentication statuses (5/15) on any GATT op trigger one
  bond-and-retry round. Pairing prompts are system UI - the flow shows an explanatory step
  (strings `ble1810_pairing_step1..3`).
- 0x2A49 (feature bitmap) and 0x180A (manufacturer + model, the friendly name) are read
  best-effort for diagnostics; a monitor without them still transfers readings.
- **Source removal drops the system bond** (`Ble1810Bonds.removeBond`) - best-effort via
  reflection (hidden API); failure is logged, and the user can always unpair in system
  Bluetooth settings. The removal flow (task .3) calls it.

### 7.5 Capabilities + registration (epic R2/R4/R5)

- `userAttribution = NONE` (app owner), `supportedSyncModes = {HISTORY}` - the device decides
  what a push contains (all-stored / latest-only / delete-after-transfer, 4.2), there is no
  windowed read (no RACP in practice), so every pass IS the idempotent full read.
  `requiresPairingKey = false` - connection is system bonding, not an app key.
- Registration is one line in `HealthSyncWiring.sourceRegistry()`
  (`Ble1810BpSourceType`) - no core-class changes (SC2 proof held).
- The scanner surfaces any 0x1810 advertiser; note the Omron M7 also advertises 0x1810 (1.2),
  so one scan feeds both BLE source types - the add-source flow decides which type to offer
  for a selected device.

### 7.6 Error surfaces

The pure layers carry raw English reasons (the `OmronBleResult` convention); the user-facing
texts are the string resources `ble1810_error_{bonding,connect,timeout,disconnected}` +
`ble1810_pairing_step{1,2,3}` (EN + RU in sync), rendered by the add-source flow (task .3)
and the read UI (task .6).

*Medical disclaimer: this document supports software integration only; it is not medical advice.*

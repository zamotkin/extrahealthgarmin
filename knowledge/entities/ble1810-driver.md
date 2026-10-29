---
type: entity
title: Ble1810 driver — standard 0x1810 blood-pressure source
status: current
generated: 2026-10-26
tags: [devices, ble, 1810]
---
**Responsibility.** Reads any Bluetooth SIG Blood Pressure Service (0x1810)
monitor; HISTORY-only, NONE attribution (ADR-05). Package:
`devices/ble1810/` (7 files); spec: `docs/ble-1810-protocol.md`.

**Components.**
- `Ble1810Protocol` — every constant: UUIDs, CCCD value `0x0002`, the
  `0x07FF` SFLOAT sentinel, kPa factor, session timeouts.
- `Ble1810MeasurementParser` — pure §3 parser (flags, SFLOAT specials,
  units, Date Time, User ID, Measurement Status); never throws.
- `Ble1810Session` — `Ble1810Transport` seam + read-session state machine
  (burst collection, quirk absorption, arrival stamping; status-19
  self-disconnect and idle timeout both end the session with the burst kept).
- `Ble1810Scanner` / `Ble1810GattConnection` / `Ble1810Client` — discovery,
  connect/bond (direct connect, `autoConnect=false`; bonding is device
  policy), result folding.
- `Ble1810Source` — the `BpSource` adapter (type id `ble-standard-1810`,
  HISTORY-only/NONE/no key) implementing `DeferredCommitSource`: a successful
  read DEFERS its journal commit to `commitDelivered()`, run by
  `SourceReadUseCase` only after Room confirmed the rows (ADR-03).
- `Ble1810FingerprintJournal` — per-source dedup for timestamp-less records:
  `filesDir/ble1810-<address-with-dashes>-fingerprints.json`, count-wise
  merge-on-save; `filterUnseen` passes only occurrences past the journaled
  count (two real identical readings in one burst are both delivered; a full
  re-push delivers nothing); timestamped records bypass it (Room index +
  upload journal dedup those).

**Tests.** `Ble1810SourceTest`, `Ble1810SessionTest`,
`Ble1810MeasurementParserTest`, `Ble1810ScannerTest`,
`Ble1810ClientTest`, `Ble1810FingerprintJournalTest`.

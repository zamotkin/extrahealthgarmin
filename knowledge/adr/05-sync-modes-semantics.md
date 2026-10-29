---
type: adr
title: ADR-05 Sync-mode semantics: History vs Current per source
status: accepted
generated: 2026-10-26
tags: [sync, blood-pressure]
---
## Context
Sources differ in what a read can fetch: the Omron ring buffer can be re-read
in full, standard 0x1810 monitors decide themselves what a push contains, and
Health Connect exposes only changes since a token.

## Decision
- **History = full device ring, idempotent** via the unique
  (timestamp, userSlot, sourceId) index (insert-ignore) plus the Garmin upload
  journal; for timestamp-less 0x1810 records, the fingerprint journal makes a
  re-push idempotent too.
- **Current = the device's unread window** since the last successful pass
  (Omron's unread counters); guarded by the ADR-03 fallback protocol.
- **The 0x1810 source is HISTORY-only**: devices decide what a push contains
  (all-stored / latest-only / delete-after-transfer, docs §4.2) and RACP is
  unimplemented in practice on consumer units, so every pass IS the full read.
- **Health Connect is CURRENT-only**: its changes token is the anchor; there
  is no history re-read to offer.

## Consequences
- The add-source flow renders each type's `supportedSyncModes`, so the UI
  never offers a mode a source rejects (`Ble1810Source`/`HcBpSource` answer
  non-supported requests with `Failed`).
- History re-reads are always safe but slower; Current passes are cheap but
  rely on the ADR-03 recovery path for crash windows.
- `SyncModeSection` in the source detail only shows pickable modes; see
  `SyncModeSectionTest`.

---
type: adr
title: ADR-07 Autosync is a post-read upload, not a scheduler
status: accepted
generated: 2026-10-26
tags: [sync, workmanager, blood-pressure]
---
## Context
BLE blood-pressure readout requires the monitor in transfer mode and the app
in the foreground; background BLE scans and reads are unreliable and
battery-hostile. The old Omron view had an autosync switch that had to keep a
meaning in the sources world (task xlg.6).

## Decision
**No scheduler for BP.** Autosync is a per-source flag (`ConnectedSource.autoSync`,
default true) consumed after a successful read pass: the reader runs one
Garmin upload pass for this source's slot (`BpSyncCoordinator.syncOnce(slot)`)
in the same foreground pass, immediately after `SourceReadUseCase.read`
succeeds. The flag is re-read from the stored row as of the post-read moment,
so a toggle mid-pass is honored; an upload problem outranks the read-success
status (readings are already saved and stay retriable).

The **only WorkManager job in the app is the HC weigh-in `SyncWorker`**
(~3-hourly periodic, network-constrained, exponential backoff; only a
Retryable outcome asks WorkManager to retry).

## Consequences
- No background BP work to drain the battery or fight BLE radio conditions;
  the user's "Read now" tap is the single trigger for read + upload.
- Health Connect BP readings still reach Garmin promptly because the HC
  pipeline uploads inside its own read pass (see `hc-bp-source.md`).
- Failed uploads never lose readings: un-synced rows stay pending and the
  Garmin journal prevents re-uploads (see `bp-sync-coordinator.md`).

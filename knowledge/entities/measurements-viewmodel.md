---
type: entity
title: MeasurementsViewModel — the BP tab state machine
status: current
generated: 2026-10-26
tags: [ui, viewmodel]
---
**Responsibility.** Owns the Measurements page (task xlg.6): the R8 tab
visibility, one `SourceCardState` per connected source ("Read now",
autosync toggle, read status) and the day-grouped readings list
(`MeasurementsListState` with `DayHeader`/`Reading` entries). File:
`ui/screens/measurements/MeasurementsViewModel.kt` (+ Screen, Factory).

**`readNow(sourceId)`.** One pass per source (a second tap while running is
ignored); re-loads the row as of the tap; maps `SourceReadOutcome` onto
`ReadNowState` (Idle / Reading / Finished with message + detail + isError).
Cancellation resets to Idle and propagates; unexpected errors land on an
error status — never a crash.

**`applyReadOutcome`.** A store failure (`StoreResult.success=false`) says so
instead of pretending; a source whose pipeline consumed readings itself (HC)
tells its story via counters; and when the source's CURRENT stored row has
autosync on, the same pass runs one Garmin upload of that source's slot
(`upload(slot)` → `BpSyncCoordinator.syncOnce`) — an upload problem outranks
the read success (ADR-07). A toggle mid-pass is honored (flag re-read after
the read). `setAutoSync` writes the row; `showMore` grows the list window.

**Tests.** `MeasurementsViewModelTest`.

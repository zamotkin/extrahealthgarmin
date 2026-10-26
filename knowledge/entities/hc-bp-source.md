---
type: entity
title: HcBpSource — Health Connect singleton source
status: current
generated: 2026-10-26
tags: [devices, health-connect]
---
**Responsibility.** `BpSource` adapter over the existing Health Connect
blood-pressure pipeline: one `read` runs one `BloodPressureSync` pass
(changes-token incremental read with its own state file, 30-day first-run
backfill and Garmin upload) — unchanged. File:
`devices/healthconnect/HcBpSource.kt`; type object `HealthConnectBpSourceType`
wires the real pipeline as an injected `suspend () -> BloodPressureResult`
seam, so the adapter is plain JVM.

**Capabilities.** type id `health-connect`; `userAttribution = NONE`
(no device-side users — every reading is the phone owner's),
`supportedSyncModes = {CURRENT}` (the changes token is the anchor; there is
no history re-read, ADR-05), `requiresPairingKey = false`. A userSlot sent by
a caller is meaningless and safely ignored.

**Mapping.** `!result.enabled` → `ConnectionRequired` (the permission grant
IS the connect step); success → `Success(readings = emptyList(), counters =
SourceCounters(uploaded, waiting, dropped))` — the pass consumes its readings
internally (Garmin needs the watch's pulse, so the pipeline uploads itself).

**Tests.** `HcBpSourceTest` (fake pass); the pipeline itself:
`BloodPressureSyncTest`.

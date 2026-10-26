---
type: entity
title: BpSource — the vendor-neutral source contract
status: current
generated: 2026-10-26
tags: [devices, api]
---
**Responsibility.** One read pass of one blood-pressure source; terminal
outcomes only (ADR-09). File: `devices/api/BpSource.kt` (plus
`BpSourceType.kt`, `BleSourceScanner.kt` in the same package).

**Key API.**
- `interface BpSource { suspend fun read(request: SourceReadRequest): SourceReadResult }`
  — mode and user slot come from the stored source configuration.
- `SourceReadResult` sealed: `Success(readings, counters)`,
  `ConnectionRequired(reason)`, `Timeout`, `Disconnected`, `Failed` (all with
  reasons). A source whose pipeline consumed readings itself (Health Connect)
  reports none and tells the story via `SourceCounters`
  (uploaded/waiting/dropped).
- `BpReading(timestampEpochSec, systolic, diastolic, pulse, mov, ihb,
  userSlot = APP_OWNER_SLOT)` — pure JVM; `APP_OWNER_SLOT = 0` for
  UserAttribution.NONE sources.
- `UserAttribution { SLOTS, NONE }`; `SyncMode { HISTORY, CURRENT }`.
- `interface BpSourceType { typeId; displayNameRes; capabilities }` in two
  shapes: `BleBpSourceType.createFromDevice(context, DiscoveredBleDevice)` and
  `SingletonBpSourceType.createSingleton(context)`.

**Seams.** Adapters receive their stack as injected lambdas (key provider,
BLE read), keeping them plain JVM. `displayNameRes` is a getter-backed val
(ADR-08).

**Tests.** `OmronBleSourceTest`, `Ble1810SourceTest`, `HcBpSourceTest`,
`SourceRegistryTest`.

---
type: entity
title: SourceRegistry — typeId to factory resolution
status: current
generated: 2026-10-26
tags: [devices, registry]
---
**Responsibility.** Resolves a source type id to its `BpSourceType` factory.
Deliberately a plain map — no reflection, no plugin loading (ADR-02). File:
`devices/SourceRegistry.kt`.

**API.** `SourceRegistry(registered: List<BpSourceType>)`;
`type(typeId): BpSourceType?` (unknown id → null, caller decides);
`types: List<BpSourceType>` in registration order.

**Registration.** One line per type in `HealthSyncWiring.sourceRegistry()`
(`health/HealthSyncFacade.kt`): `OmronBleSourceType` ("omron-ble"),
`Ble1810BpSourceType` ("ble-standard-1810"), `HealthConnectBpSourceType`
("health-connect"). The types are plain-JVM objects (Context is a create-time
parameter), so the registry is unit-testable with no Android.

**Seams.** `SourceReadUseCase` (resolve + rebuild sources from stored rows),
the add-source flow (type picker), `SettingsViewModel.typeOf`.

**Tests.** `SourceRegistryTest` (epic SC2: the registry resolves all three
type ids).

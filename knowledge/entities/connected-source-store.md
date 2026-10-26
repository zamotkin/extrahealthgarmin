---
type: entity
title: ConnectedSourceStore — persisted source rows
status: current
generated: 2026-10-26
tags: [devices, persistence]
---
**Responsibility.** Persists the connected BP sources as one JSON file
(`filesDir/bp-sources.json`) with atomic, row-atomic writes (ADR-10). Files:
`devices/store/ConnectedSourceStore.kt` (interface + `FileConnectedSourceStore`).

**Row fields (`ConnectedSource`).** `id` (UUID), `sourceTypeId` (registry
key), `name`, `address` (BLE), `pairingKeyRef`, `userSlot` (nullable),
`syncMode` (default HISTORY), `lastReadAnchor` (epoch sec of the newest
ingested reading; only the read orchestrator advances it), 
`fullReadFallbackPending` (ADR-03), `autoSync` (default true; ADR-07),
`createdAt`.

**API.** `load()`, `save(list)`, `update(id) { row -> row }`, `remove(id)`,
`add(row)` — all synchronized on one internal lock; `save` writes temp +
rename (copy-then-delete fallback). Corrupted file → empty list with a log.

**Seams.** The read orchestrator (`SourceReadUseCase`), the ViewModels
(Measurements/Settings/Sources/AddSource) and `HealthSyncWiring
.connectedSourceStore()` share ONE instance so writes compose.

**Tests.** `ConnectedSourceStoreTest`.

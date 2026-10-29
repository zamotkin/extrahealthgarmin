---
type: entity
title: SourceReadUseCase — the production read pass
status: current
generated: 2026-10-26
tags: [devices, orchestration]
---
**Responsibility.** Runs one read pass of a connected source into the app
dataset; owns the ADR-03 guarantees. File: `devices/SourceReadUseCase.kt`.

**Shape.** ConnectedSource → registry resolves the type → `BpSource.read`
with the STORED row's mode/slot (row reloaded under the per-source mutex) →
epic-R4 `filterByUserSlot` ingestion filter → `OmronBpRepository
.storeReadings(readings, sourceId)` (insert-ignore) → `DeferredCommitSource
.commitDelivered()` only if `StoreResult.success` → `advanceReadState`
(anchor = max(newest ingested), fallback flag cleared). A pending
`fullReadFallbackPending` overrides a Current plan with one full-ring History
plan (never rewriting the configured mode).

**API.** `read(source): SourceReadOutcome` (result + optional stored counts);
`SourceReadUseCase.create(context, registry, repository, store)` is the
manual wiring — BLE types rebuild `DiscoveredBleDevice` from the stored name
and address (RSSI is scan-time data).

**Seams.** Registry, `sourceFor: (ConnectedSource) -> BpSource?`,
repository, store — all injectable; free of UI knowledge.

**Tests.** `SourceReadUseCaseTest` (39 tests incl. the eight xlg.16
cancellation/DAO-failure interleavings).

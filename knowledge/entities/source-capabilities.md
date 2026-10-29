---
type: entity
title: SourceCapabilities — what a source type can do
status: current
generated: 2026-10-26
tags: [devices, api]
---
**Responsibility.** Declares a source type's shape so the add-source flow and
the read orchestrator adapt without vendor imports. File:
`devices/api/BpSource.kt` (data class, lines 24-65).

**Fields.**
- `userAttribution: UserAttribution` — SLOTS (device user profiles, e.g.
  Omron 1|2) or NONE (phone owner).
- `supportedSyncModes: Set<SyncMode>` — modes `read` accepts (ADR-05).
- `requiresPairingKey: Boolean` — connecting needs an app-level key (Omron)
  vs system bonding (0x1810).
- `pairingKeyBytes: Int?` — exact key length (M7: 16); null for keyless types.
  The add-source flow validates typed keys against this, never a vendor
  constant.
- `autoPairingKey: Boolean` — the app supplies the key itself (ADR-06);
  manual entry stays an advanced fallback.
- `userSlotCount: Int = 2` — device user slots for SLOTS sources (1-based);
  derived `userSlots: List<Int>` drives the one-time slot picker (epic R4).

**Consumers.** `SourceReadUseCase` (fallback eligibility, ingestion filter via
`filterByUserSlot`), add-source flow (`AddSourceViewModel`), source detail
(`SyncModeSection`).

**Tests.** `SourceRegistryTest` pins the three registered capability sets;
`AddSourceViewModelTest`, `SyncModeSectionTest` cover the UI consumption.

---
type: entity
title: SettingsViewModel — Settings tab groups
status: current
generated: 2026-10-26
tags: [ui, viewmodel]
---
**Responsibility.** Renders and drives the Settings tab (task xlg.7):
`ConnectionsGroupState` (connected sources with their
`SourceReadAnchor`/mode/autosync), `GarminGroupState` (sign-in state, sign-out,
login entry), `HcGroupState` (permission status, backfill picker), and
`AboutGroupState` (version, diagnostics). File:
`ui/screens/settings/SettingsViewModel.kt` (+ Screen, Factory).

**Key API.** `refresh()`; `requestGarminSignIn()` / `signOutGarmin()`
(delegate to the shared `MainViewModel` / repository signOut gate);
`hcPermissionsToRequest()` / `onHcPermissionsResult()` (Activity-Result
launchers live in the Compose UI, incl. the Play Store fallback and the HC
settings deep link); `backfill(days)`; destructive read management —
`deleteReadingsForUser(slot)` and `deleteAllReadings()` with
`DeleteReadingsResult` feedback (`setDeleteResult`). `typeOf(sourceTypeId)`
resolves display info through the registry.

**Seams.** `HealthSyncFacade`/`HealthSyncWiring`, `ConnectedSourceStore`,
`OmronBpRepository`, `SourceRegistry` — all constructor-injected by the
Factory; never throws to the UI.

**Tests.** `SettingsViewModelTest`.

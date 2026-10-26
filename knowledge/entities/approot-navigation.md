---
type: entity
title: AppRoot — scaffold, navigation and app-level dialogs
status: current
generated: 2026-10-26
tags: [ui, navigation]
---
**Responsibility.** The app scaffold (task xlg.1): one Scaffold with the
bottom `NavigationBar` (Home / Measurements / Settings, labels always
visible) over a single `NavHost`. File: `ui/navigation/AppRoot.kt`.

**Routes.** `home`, `measurements`, `settings`, plus sub-destinations
`sources` (source list), `add-source` (guided flow: type picker → BLE
scan/pair or HC grant → save), `source-detail/{id}` (sync mode, user slot,
remove).

**R8 gating.** The Measurements tab renders only while at least one BP source
is connected: `MeasurementsViewModel.tabVisible` (LiveData) drives
`EhgBottomBar(showMeasurements)`; a `LaunchedEffect(currentRoute)` re-runs
`refresh()` on every navigation change so a source added or removed anywhere
shows up on the next tap.

**App-level dialogs.** `GarminLoginDialog` (+ `LoginDialogVisibility` state
machine) opens over whichever tab is active from the Settings sign-in entry
or a LoginRequired pass; `triggerGarminAuthSpike` is the debug-only live auth
spike. The privacy-policy dialog opens only from the Health Connect
permission-usage rationale deep link. `MainActivity` calls `enableEdgeToEdge()`,
enqueues the periodic `SyncWorker` (idempotent) and renders `AppRoot`.

**Tests.** `LoginDialogVisibilityTest`; screen tests under
`ui/screens/**`.

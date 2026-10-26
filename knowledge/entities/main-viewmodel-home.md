---
type: entity
title: MainViewModel — shared weigh-in/account state
status: current
generated: 2026-10-26
tags: [ui, viewmodel]
---
**Responsibility.** The shared state machine BOTH Home and Settings render
(`ui/MainViewModel.kt` + `MainViewModelFactory`): `MainUiState` (HC status,
Garmin login state incl. `loginNeedsMfa`/`loginError`, upload status,
`UploadSummary`) and the weigh-in sync actions.

**Key API.** `syncNow()` — foreground pass via `HealthSyncFacade.sync()` so
the UI gets instant `MainUiState` updates; internally also
`syncBloodPressure()` and `syncHydration()` (the optional HC pipelines);
`startGarminUpload()`/`performUpload()`/`handleUploadOutcome()`;
`loginToGarmin()`/`requestGarminLogin()`/`onLoginCancelled()` — the login
dialog in `AppRoot` binds to these; `signOut()`; `refreshHcStatus(Once)()`
(on screen resume too); `backfill(days)`; `permissionsToRequest()` /
`onHcPermissionsResult()`.

**Flow.** With no valid Garmin token, a pass surfaces `LoginRequired` and the
app-level `GarminLoginDialog` runs before the next attempt; token refresh is
automatic thereafter. `GarminUploadStatus`/`UploadSummary` carry outcome
rendering for Home.

**Tests.** `MainViewModelSyncTest`, `MainViewModelUploadTest`.

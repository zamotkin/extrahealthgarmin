---
type: adr
title: ADR-01 Single-activity Compose app with bottom navigation
status: accepted
generated: 2026-10-26
tags: [ui, compose, navigation]
---
## Context
The v2 app had one XML-layout activity for weigh-ins plus a second activity
for the Omron blood-pressure flow. Epic `tefal2garmin-app-redesign-v3` (v3.0.0)
unified the product around pluggable BP sources and one Measurements page.

## Decision
One activity, no fragments, no XML layouts: the whole UI is Jetpack Compose
under the shared `EhgTheme` (`ui/kit/theme`), scaffolded by
`ui/navigation/AppRoot` — one Scaffold with a bottom NavigationBar over three
destinations: **Home** (weigh-in status + Sync now, backed by `ui/MainViewModel`),
**Measurements** (one card per connected BP source over the day-grouped
readings list), **Settings** (Connections / Garmin Connect / Health Connect /
About). Sub-flows (source list, source detail, add-source) are NavHost
destinations, not activities. The Measurements tab renders only while at least
one BP source is connected (epic R8): `MeasurementsViewModel.tabVisible`
re-reads the source store on every navigation change.

**Rejected alternatives (epic contract, DO NOT REVISIT):** restyling the two
old activities with a shared kit (split navigation remains, extensibility
becomes decorative); a BLE-only driver seam with Health Connect left hidden.

## Consequences
- One design-token source (`EhgTheme`); no per-screen theming drift.
- App-level dialogs (Garmin login, privacy rationale) open over whichever tab
  is active — `LoginDialogVisibility` + `GarminLoginDialog` in `ui/navigation`.
- Business logic stays in ViewModels; Composables only render and forward taps
  (epic forbidden pattern: no business logic in Composables).
- Covered by `ui/navigation/LoginDialogVisibilityTest`,
  `ui/screens/**` ViewModel tests; SC1 of the epic (OmronActivity gone, no
  XML layout driving the main UI) verified at xlg.8.

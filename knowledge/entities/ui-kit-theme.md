---
type: entity
title: EhgTheme and the shared UI kit
status: current
generated: 2026-10-26
tags: [ui, compose, theming]
---
**Responsibility.** The single design-token source for the whole app plus the
shared widgets. Files: `ui/kit/theme/EhgTheme.kt`, `ui/kit/components/`
(EhgButton, SectionCard, SectionHeader, StatusRow, EmptyState, LabeledValue,
UploadSummaryText).

**`EhgTheme(darkTheme, dynamicColor = true, content)`.** Material 3 with
dynamic color on Android 12+ (`dynamicDark/LightColorScheme`), a health-brand
teal/green fallback palette (`EhgLightColors`/`EhgDarkColors`) below that,
and a FULL dark scheme so pre-S devices and the XML window theme share one
brand look. `dynamicColor` is a parameter (not a constant) so previews and
tests can pin the brand palette even on 12+ devices. Semantic tokens that
must never follow the wallpaper stay FIXED (error roles keep baseline values
in fallback schemes). Typography `EhgTypography` and shapes `EhgShapes`
complete the tokens; the expressive feel uses stable material3 1.4.0 APIs
only (ADR-08).

**Conventions.** All strings via `R.string` (EN + RU parity — ADR-08);
getter-backed vals for companion resource ids; no business logic in
Composables; components are stateless renderers fed by ViewModel state.

**Tests.** Component behavior is covered via the ViewModel/screen tests
(`SourcesViewModelTest`, `SyncModeSectionTest`, `SettingsViewModelTest`,
`MeasurementsViewModelTest`); no dedicated theme unit test class exists.

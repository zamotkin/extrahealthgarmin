---
type: adr
title: ADR-08 UI kit tokens and locale gates
status: accepted
generated: 2026-10-26
tags: [ui, compose, i18n]
---
## Context
v3 rebuilt the UI in Compose; Material "Expressive" was announced but the app
rides `compose-bom` 2026.06.01, and the epic (R10) demanded bilingual UI with
no hardcoded strings.

## Decision
- **`EhgTheme` (`ui/kit/theme/EhgTheme.kt`) is the single design-token
  source:** dynamic color on Android 12+ (`dynamicColor` parameter so previews
  and tests can pin the brand palette), a health-brand fallback palette below,
  and a FULL dark scheme. Clinical tokens that must never follow the wallpaper
  stay FIXED (error roles keep baseline values in the fallback schemes).
- **Stable `material3` 1.4.0 only** — Expressive components are NOT in
  compose-bom 2026.06.01; the "2026 expressive" feel comes from stable APIs
  (color + generous shapes + default spring motion).
- **`R.string` references in companion objects must be getter-backed `val`**
  (`val X: Int get() = R.string.x`): a `const val` from an R field is not a
  compile-time constant under AGP 9 built-in Kotlin and compiles cross-class
  references to a getter that is never generated — `NoSuchMethodError` at
  runtime (hit in xlg.15, pinned in all three source companions).
- **EN/RU key parity is a hard gate** (epic R10; SC8): every UI string exists
  in both `res/values/strings.xml` and `res/values-ru/strings.xml`; enforced
  by the PR-template checklist and review — no CI workflow exists in-repo.

## Consequences
- One look across light/dark, dynamic and fallback; tests can pin themes.
- Bumping to a newer BOM/Expressive later is a token-level change, not a
  rewrite; the const-val trap must be remembered when copying companions.

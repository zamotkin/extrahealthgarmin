---
type: context
title: Tooling and conventions (summary)
status: current
generated: 2026-10-26
tags: [process, tooling]
---
Pointers into `CLAUDE.md` (the authoritative version) — summarized, not
duplicated.

**Task tracker: `br` (beads, DB in `.beads/`).** All epics, tasks, issues and
dependencies live there, not in markdown; keep statuses current; the v3 epic
is `tefal2garmin-app-redesign-v3-xlg` (see `epic-history.md`).

**Wiring.** No DI framework — manual, explicit wiring in
`HealthSyncWiring` (inside `health/HealthSyncFacade.kt`) and the
`*ViewModelFactory` classes. Logging via `android.util.Log` with a per-class
TAG in the companion object (pure-JVM classes take an injected `onError`
hook instead). SOLID + KISS; match surrounding style; commits/identifiers in
English; `Co-Authored-By: Claude Code <noreply@anthropic.com>`.

**Tests.** One test class per production class under
`app/src/test/java/com/zamotkin/extrahealthgarmin/`, mirroring packages
(health/, garmin/, data/, devices/, ui/, omron/). JUnit4 + Mockito +
coroutines-test + Health Connect fakes + MockWebServer; gateways tested
through fake seams, not mocks of the system under test. Changes to sync
logic, aggregation, FIT encoding or auth require a unit test (epic R10;
suite ~664 tests green).

**Build/debug (from CLAUDE.md).** `./gradlew assembleDebug`,
`assembleRelease` (local `app/release.keystore`, gitignored),
`testDebugUnitTest [--tests ...]`, `lintDebug`; `./debug_setup.sh` builds,
installs and launches the debug APK; `adb logcat -s MainActivity
MainViewModel SyncWorker` for runtime logs. Toolchain: Gradle 9.6.0 / JDK 26 /
AGP 9.4.1 (built-in Kotlin, no `org.jetbrains.kotlin.android`), KSP 2.3.12
for Room, compose plugin 2.2.10 = AGP's embedded KGP, compose-bom 2026.06.01,
material3 1.4.0 stable.

**Tool routing** (CLAUDE.md "Tools"): fetch for live URLs, context7 for
library docs, serena for symbol navigation/edits, claude-mem for cross-session
memory, this `knowledge/` base before design work.

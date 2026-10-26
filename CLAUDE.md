# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

Single-module Android app (Kotlin, Gradle wrapper). minSdk 26 / targetSdk 34 / compileSdk 36.

```bash
./gradlew assembleDebug                # debug APK
./gradlew assembleRelease              # release APK (needs local app/release.keystore — gitignored)
./gradlew testDebugUnitTest            # all unit tests (JUnit4 + Mockito + coroutines-test + HC fakes + MockWebServer)
# Single class / single method:
./gradlew testDebugUnitTest --tests "com.zamotkin.extrahealthgarmin.health.SyncEngineTest"
./gradlew testDebugUnitTest --tests "com.zamotkin.extrahealthgarmin.garmin.UploadJournalTest.*"
./gradlew lintDebug
./debug_setup.sh                       # build + install + launch debug APK on the connected device
adb logcat -s MainActivity MainViewModel SyncWorker   # runtime logs (per-class TAGs)
```

App version: `versionCode`/`versionName` in `app/build.gradle`.

## Architecture

Extra Health Garmin syncs body-composition weigh-ins from **Health Connect** into **Garmin Connect**,
and collects blood-pressure readings from pluggable sources (Omron M7 Intelli IT over BLE, the
standard BLE Blood Pressure Service 0x1810, Health Connect) into a Room database, uploading them to
Garmin Connect as one `blood_pressure.fit` batch. Single activity, no fragments, no XML layouts —
the whole UI is Jetpack Compose under the shared `EhgTheme`: `MainActivity.setContent` →
`ui/navigation/AppRoot`. MVVM: ViewModels own the logic (LiveData bridged via `observeAsState`),
Composables render and forward taps. No DI framework — wiring is manual in `HealthSyncWiring.facade` /
`*ViewModelFactory`.

Packages under `app/src/main/java/com/zamotkin/extrahealthgarmin/`:

- `ui/` — the Compose UI (epic tefal2garmin-app-redesign-v3; `ui/omron` and `res/layout` are gone).
  `ui/kit/theme/EhgTheme` is the single design-token source (dynamic color on Android 12+, a
  health-brand fallback palette, full dark scheme); `ui/kit/components` the shared widgets
  (EhgButton, SectionCard, StatusRow, EmptyState…). `ui/navigation/AppRoot` owns the Scaffold +
  bottom nav (Home / Measurements / Settings — Measurements renders only while at least one BP
  source is connected, epic R8) and the app-level Garmin login dialog (`GarminLoginDialog` +
  `LoginDialogVisibility`), opened over whichever tab is active from the Settings sign-in entry or
  a LoginRequired pass. `ui/screens/home` — weigh-in status + "Sync now" (backed by `MainViewModel`
  at `ui/`); `ui/screens/measurements` — the single BP page: one card per connected source
  ("Read now", autosync toggle, read status) over the day-grouped readings list;
  `ui/screens/settings` — grouped Connections (sources, destructive reads management) / Garmin
  Connect / Health Connect (permission launchers, backfill picker, notes) / About (version,
  diagnostics); `ui/screens/devices` — the add-source flow (type picker → BLE scan/pair or HC
  grant → save), the source list and the source detail (sync mode, user slot, remove).
- `devices/` — the vendor-neutral BP-source seam (epic R2: adding a source type = driver/adapter +
  one registration, no core surgery). `api/` holds the contracts (`BpSource` — never-throwing
  `read`, `BpSourceType` with Ble/Singleton shapes, `SourceCapabilities`, `SourceReadResult`
  variants); `SourceRegistry` is a plain map (no reflection); `store/ConnectedSourceStore`
  persists `bp-sources.json` (one row per source: type, name/address, pairing-key ref, user slot,
  sync mode, autosync, last-read anchor, full-read-fallback flag) with row-atomic `update`;
  `omron/OmronBleSource`, `ble1810/` (standard 0x1810 driver + fingerprint journal for clock-less
  records) and `healthconnect/HcBpSource` (wraps the existing `BloodPressureSync` pass) are the
  three registered types. `SourceReadUseCase.read` is the production caller: per-source mutex,
  plan from the stored row, the epic-R4 user-slot ingestion filter, idempotent store via
  `OmronBpRepository` (insert-ignore). Stabilized read protocol: a Current plan is flagged
  `fullReadFallbackPending` BEFORE it runs (a pass killed by cancellation or process death gets
  its window re-read as History next time); the anchor advance and the flag clear happen only
  after `StoreResult.success` confirmed Room holds the rows; a `DeferredCommitSource` (the 0x1810
  fingerprint journal) commits its dedup state only after the same confirmation.
- `health/` — the sync core. `HealthConnectSource` reads the seven record types (weight, body fat,
  bone mass, lean body mass, body water, BMR, height) and manages changes tokens (first run = 30-day
  backfill; expired token = range re-read since the last sync anchor). `BodyMeasurementAggregator`
  correlates same-time measurements into one weigh-in (anchor + companions; the newest height record
  is cached so BMI survives). `SyncEngine` runs one pass: fetch → cache height → aggregate → dedup
  (against `SyncState.processedRecordIds`) → upload, mapping onto `SyncResult`
  (Idle/Uploaded/PartialUploaded/Retryable/LoginRequired). `SyncWorker` is the WorkManager ~3-hourly
  periodic pass over the same engine (network-constrained, exponential backoff; only Retryable asks
  WorkManager to retry). `SyncAlerts` turns background problems the user must fix (Garmin
  sign-in gone, Health Connect access revoked, records rejected, a day of failures, Android's
  unused-app pause, battery optimization) into once-per-episode notifications. `BloodPressureSync`
  (optional READ_BLOOD_PRESSURE, own changes token + `blood-pressure-sync.json`) uploads Health Connect
  blood pressure as a FIT blood_pressure file via `GarminBloodPressureRepository`; Garmin needs a
  pulse, so readings wait for the watch's heart rate at that minute (`dailyHeartRate`), max 24 h. `HydrationSync`
  (optional READ_HYDRATION, own changes token + `hydration-sync.json`) PUTs each new Health Connect
  hydration entry to Garmin's daily hydration log via `GarminHydrationRepository`; Garmin keeps only a
  daily total, so sent entries are remembered (35 d) and deletions/edits are taken back with negative
  logs; no backfill (Garmin cannot tell what is already counted). `HealthPermissions` pins the permission set mirrored in the manifest.
- `omron/protocol/` — pure-JVM wire protocol for the Omron M7 Intelli IT monitor
  (`docs/omron-protocol.md` is the spec). `SessionFrameCodec` builds/parses the XOR-CRC session
  frames; `RecordDecoder` unpacks bit-packed 16-byte EEPROM records (systolic = raw bits + 25);
  `UnlockCodec` frames the 17-byte unlock payloads; `ReadPlanCalculator` turns the ring-buffer
  counters into read commands; `OmronSession` orchestrates the readout over the `OmronTransport`
  seam; every constant lives in `OmronProtocol`.
- `omron/ble/` — the Android BLE side. `GattChannelTransport` implements `OmronTransport` with
  16-byte channel chunking over the `GattChannelConnection` seam; `RetryCommandRunner` retries
  each command 5x under a 1s timeout; `OmronGattConnection` is the only file touching
  `android.bluetooth` (connect/GATT glue; `gatt.close()` on every exit path); `OmronBleClient`
  folds the readout into `OmronBleResult`. The 16-byte pairing key lives in `OmronKeyStore`,
  AES-encrypted by `KeystoreAesPairingKeyCrypto` (Android Keystore, alias
  `extrahealthgarmin_omron_pairing_key` — the `TokenCrypto` counterpart).
- `omron/data/` — the only Room in the app (vendor-scoped, but its DAO/repository are shared by
  every BP source). `BloodPressureRecordEntity` carries the unique (timestamp, userSlot, sourceId)
  index that makes device re-reads idempotent (schema v2 — destructive migration by product-owner
  decision, BP data is re-read from sources); `BloodPressureDao` is the interface seam unit tests
  fake; `OmronBpRepository` is the never-throws gateway; `BpSyncCoordinator` marks records synced
  only after the upload confirmed them.
- `garmin/` — the Garmin side. `GarminAuthClient` implements DI OAuth2 login with MFA resume
  (garth/python-garminconnect wire contract); tokens live in `GarminTokenStore`, AES-encrypted by
  `TokenCrypto` (Android Keystore, alias `extrahealthgarmin_di_tokens`). `FitWeightFileBuilder`
  encodes weigh-ins into a FIT weight_scale file (official Garmin FIT SDK); `GarminUploadClient`
  uploads it; `UploadJournal` is the timestamp dedup journal; `FitBloodPressureFileBuilder`
  encodes blood-pressure readings into a FIT blood_pressure file; `BpUploadJournal` is the
  composite-key (timestamp, userSlot) dedup journal with merge-on-save; `RedactingHttpLogger`
  keeps secrets out of debug logs (inactive in release).
- `data/` — `GarminRepository` (`GarminUploadOrchestrator`): the UI-facing gateway that dedups
  against the journal, uploads new records as one batch and reports `UploadOutcome`; never throws.
  `uploadBloodPressure` is the BP-sources entry point — a single-flight `Mutex` serializes
  filter → upload → journal so concurrent callers cannot double-upload. `signOut` clears only the
  tokens; token saves of in-flight upload passes are suppressed until the next successful login.
- `ui/MainViewModel` (+ factory) — the shared weigh-in/account state machine both Home and
  Settings render.

State is deliberately one JSON file in `filesDir` (`hc-sync-state.json`, see `SyncStateStore`):
changes token, last-sync instant, processed-record-id dedup set, cached height. Atomic writes
(temp file + rename). No DataStore — the only Room in the app is the Omron blood-pressure
database in `omron/data/`.

### Main flow

1. `MainActivity.onCreate` calls `enableEdgeToEdge()`, enqueues the periodic `SyncWorker`
   (idempotent) and renders `AppRoot` (bottom nav: Home / Measurements / Settings). The Health
   Connect permission screen, the Play Store fallback and the Health Connect settings deep link
   are launched from the Settings tab's Compose UI via Activity-Result contracts; the
   permission-usage rationale deep link opens the app-level privacy dialog.
2. Weigh-ins: "Sync now" (Home) runs `HealthSyncFacade.sync()` directly so the UI gets instant
   `MainUiState` updates; the background worker runs the identical pass without touching UI state.
   The backfill picker lives in Settings → Health Connect.
3. Garmin auth: with no valid token the flow surfaces `LoginRequired` and the app-level login
   dialog (`GarminAuthClient`, email + password + MFA — `ui/navigation`) runs before the next
   attempt; token refresh is automatic thereafter. Sign-out (Settings → Garmin Connect) clears
   only the tokens — a concurrently finishing upload pass cannot resurrect the session.
4. Blood pressure: Settings → Connections → Add source runs the guided flow (scan → pair → save,
   or the Health Connect grant). The Measurements tab reads each source via
   `SourceReadUseCase.read` ("Read now") and, when that source's autosync is on, uploads its slot
   right after a successful read (`BpSyncCoordinator`). There is no background BP work.
5. Failed records are never journaled — they stay retriable and ride with the next sync pass;
   a failed BP read advances no anchor.

## Conventions

- All UI strings via `R.string` — English in `res/values/strings.xml`, Russian in
  `res/values-ru/strings.xml`; keep both in sync (the app name "Extra Health Garmin" is the
  identical brand string in both locales — it is not translated).
- Logging: `android.util.Log` with a per-class `TAG` in the `companion object`.
- No DI framework — wiring stays manual and explicit.
- The toolchain is Gradle 9.6.0 / JDK 26 with a single AGP version (9.4.1) declared only in the
  root `build.gradle` `plugins {}` block — no `buildscript {}` classpath. AGP 9 compiles Kotlin via
  its built-in Kotlin support, so `org.jetbrains.kotlin.android` is not applied anywhere and
  Kotlin's `jvmTarget` defaults to `compileOptions.targetCompatibility` (Java 11, required by
  work-runtime 2.12.0's inline functions). Room's compiler runs via KSP (`com.google.devtools.ksp`
  2.3.12 — 2.3.10+ carries the AGP built-in-Kotlin support; kapt is not an option without
  `org.jetbrains.kotlin.android`). Compose rides `org.jetbrains.kotlin.plugin.compose` 2.2.10,
  which must equal AGP's embedded KGP version, on `compose-bom` 2026.06.01 (newer BOMs already
  require compileSdk 37). `gradle.properties` keeps only `android.useAndroidX`,
  `org.gradle.jvmargs` and `android.enableResourceOptimizations=false` (the latter keeps the
  adaptive launcher icon rendering on API 37).
- JSpecify-era API notes: AndroidX dependencies are migrating to JSpecify nullness annotations —
  expect new/changed nullness warnings when bumping them; fix Kotlin call sites rather than
  suppressing.
- Changes to sync logic, aggregation, FIT encoding or auth must come with a unit test — the suite
  mirrors the packages, one test class per production class in
  `app/src/test/java/com/zamotkin/extrahealthgarmin/` (`health/`, `garmin/`, `data/`, `devices/`,
  `ui/`, `omron/`).

## Tools: when to use what

- **fetch** — live content of a specific URL: web docs, changelogs, raw HTML, localhost. Not for library docs (that's context7) and not for "where to read" lookups (web search).
- **context7** — BEFORE writing code against a library/framework (AndroidX, Lifecycle, Coroutines, WorkManager, Health Connect, Gradle): exact signatures, configuration, migrations. Even if you "remember the API" — the model's data may be stale. Order: `resolve-library-id`, then `query-docs`; one query — one concept. Not for business logic or general Kotlin concepts.
- **claude-mem** — memory across sessions. Before design/debugging/review — `mem_search`: don't re-solve what's solved. Durable lessons — `save_memory` (after a duplicate search); current to-do lists — `work_state_write`.
- **knowledge/** — Open Knowledge Format v0.2 knowledge base; read before design work

## Process

- **Task tracker: `br`** (beads, DB in `.beads/`) — all epics, tasks, issues and dependencies live there, not in markdown files. Create: `br create --type epic|task|bug`; link: `br dep add <child> <parent> --type parent-child`; check: `br epic status`, `br list --status open`, `br ready`. Keep statuses current as work progresses.
- **USE PARALLEL AGENTS WHEREVER POSSIBLE**: launch independent tasks/investigations/reviews as a single message with multiple Agent calls. Only in parallel where there are no shared files or state. EXCEPTION: when the session runs on the Kimi API (`kimi-for-coding` model), the provider gateway rate-limits subagent traffic hard (429s) — work WITHOUT subagents in that case: do investigations/reviews/implementations directly in the main session.
- Commits, code, and identifiers — in English. Commit attribution: `Co-Authored-By: Claude Code <noreply@anthropic.com>`.
- Principles: SOLID + KISS; code style — match the surroundings.

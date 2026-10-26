# Extra Health Garmin — Release Notes

## Version 3.0.0 — Unified App Redesign: Compose UI, Blood-Pressure Sources

### Overview

Extra Health Garmin becomes one app with one interface. The whole UI is rebuilt in
Jetpack Compose (Material 3, dynamic color, full dark theme) around three tabs —
Home, Measurements, Settings — and blood-pressure collection turns from an
Omron-only feature into **pluggable sources**: the Omron M7 Intelli IT over its
proprietary BLE protocol, **any standard BLE Blood Pressure Service (0x1810)
monitor**, and **Health Connect** itself. The weigh-in sync engine, Garmin
authentication and upload behavior are unchanged.

### What changed

New features:

- **Unified Material 3 interface**: single activity, bottom navigation
  Home / Measurements / Settings, dynamic color on Android 12+ with a
  health-brand fallback palette, complete dark theme, English and Russian
- **Three blood-pressure source types**: Omron M7 Intelli IT (proprietary BLE
  readout), standard BLE Blood Pressure Service 0x1810 monitors (system
  Bluetooth bonding), and Health Connect (permission grant) — all equal
  citizens added through Settings → Connections → Add source
- **Selected user only**: the Omron source asks once, at pairing, which monitor
  user slot (USER1 / USER2) is yours; the other user's readings never enter the
  app. Sources without user attribution assign readings to the app owner
- **History / Current sync modes** per source: History re-reads the full device
  memory (idempotent), Current reads only new measurements since the last
  successful read — a failed read never advances the anchor, so no reading is
  lost
- **One Measurements page**: day-grouped readings (systolic/diastolic/pulse,
  upload status, source tag), a per-source **Read now** button and an
  automatic-upload toggle; the tab appears only while at least one source is
  connected
- **Grouped Settings**: Connections (blood-pressure sources), Garmin Connect
  (sign in/out and status), Health Connect (permissions, backfill) and About
  (version, diagnostics) — Home slims down to status plus **Sync now**

### Breaking changes

- **Blood-pressure data starts fresh**: the local blood-pressure database moves
  to a new schema (per-source records), applied as a **destructive migration** —
  previously stored readings are dropped. Connect your monitor as a source and
  read it again: the History sync mode re-imports everything the device still
  holds, and nothing already uploaded to Garmin Connect is duplicated (the
  upload journal is preserved)
- The legacy Omron pairing preferences are not migrated — the monitor is
  re-added once through the new Add source flow, re-entering the same
  16-character pairing key it already knows (the key is stored encrypted, as
  before)

### Upgrade notes

- Installs directly over 2.x (same application id, same Android 8.0+ floor)
- **Garmin sign-in survives the upgrade** — encrypted tokens and the weigh-in
  history/dedup journal are untouched; weigh-in sync continues without
  re-login
- After upgrading, add your blood-pressure monitor (or Health Connect) as a
  source in Settings → Connections to re-enable blood-pressure syncing

### Technical

- **Minimum Android version**: API 26 (Android 8.0)
- **Target Android version**: API 34
- **Compile SDK**: 36
- **Architecture**: MVVM, single-activity Jetpack Compose app (no XML layouts);
  new `devices/` package holds the vendor-neutral blood-pressure source seam
  (registry + atomic JSON store + the three drivers); `health/`, `garmin/`,
  `omron/` and `data/` keep their logic
- **Tests**: 668 unit tests (JUnit4 + Mockito + coroutines-test + Health
  Connect fakes + MockWebServer)

---

## Version 2.1.1 — Main Screen Fix

### Overview

One focused fix: on the main screen, the optional blood-pressure and hydration notes
could draw on top of each other once the content grew beyond the screen height — most
visible with Russian text, large system fonts, or when the Garmin connection controls
appeared.

### What changed

Fixes:

- **No more overlapping elements on the main screen**: the screen is now a scrollable
  column, so content of any height (long locales, accessibility font scaling, small
  screens) stacks cleanly and never overlaps
- The "Connect Garmin" button keeps its position right below the Backfill button, with
  both notes below it

### Upgrade notes

- Installs directly over 2.1.0; no permissions, settings or data change

## Version 2.1.0 — Smarter Sync, Blood Pressure and Hydration

### Overview

The sync engine gets sharper about duplicates and gaps, and grows two new data types:
blood pressure and hydration now travel from Health Connect into Garmin Connect
alongside the weigh-ins.

### What changed

New features:

- **Skip what Garmin already has**: weigh-ins already present in Garmin Connect are no
  longer re-uploaded (#2)
- **On-demand backfill**: trigger a manual re-read of the last 24 hours, 7 days or 30
  days from Health Connect (#3)
- **BMI without a height record**: when Health Connect holds no height, the Garmin
  profile height is used so BMI keeps working (#5)
- **Background sync alerts**: the app now notifies once per episode about background
  problems the user must fix — Garmin sign-in gone, Health Connect access revoked,
  records rejected, a full day of failures, Android pausing the unused app, or battery
  optimization throttling the worker (#6)
- **Blood pressure sync** (optional): Health Connect blood pressure readings upload as
  a FIT blood-pressure file; Garmin needs a pulse, so each reading waits for the
  watch's heart rate at that minute (up to 24 hours) before it is sent (#7)
- **Hydration sync** (optional): new Health Connect hydration entries are pushed into
  Garmin's daily water log; Garmin keeps only a daily total, so sent entries are
  remembered (35 days) and deletions or edits are taken back with negative logs (#8)

Fixes:

- **No more sync loops**: records that Garmin Connect itself writes back into Health
  Connect are never re-imported and re-uploaded (#1)
- **Zero means not measured**: body-composition readings of exactly 0 (body fat, body
  water, bone mass and so on) are treated as absent instead of being uploaded (#4)

### Upgrade notes

- Blood pressure and hydration sync are **opt-in**: each needs its own Health Connect
  permission (READ_BLOOD_PRESSURE / READ_HYDRATION); nothing changes until enabled
- Hydration sync starts from now — no backfill, because Garmin cannot tell what is
  already counted in its daily total
- Installs directly over 2.0.0 (same application id, same Android 8.0+ floor)

### Technical

- **Minimum Android version**: API 26 (Android 8.0)
- **Target Android version**: API 34
- **Compile SDK**: 36
- **Architecture**: unchanged MVVM core; blood pressure and hydration each keep their
  own changes token and state file next to `hc-sync-state.json`
- **Tests**: 235 unit tests (JUnit4 + Mockito + coroutines-test + Health Connect
  fakes + MockWebServer)

---

## Version 2.0.0 — The Health Connect Pivot

### Overview

**Extra Health Garmin** (formerly a scale-CSV converter — see the 1.0.0 notes below) is now a
Health Connect → Garmin Connect sync app. Instead of converting manually-exported CSV files,
it reads body-composition measurements straight from Health Connect and uploads the weigh-ins
into Garmin Connect automatically.

### What changed

- **Health Connect as the source**: reads weight, body fat, bone mass, lean body mass, body
  water, basal metabolic rate and height records (7 types), with an initial 30-day backfill and
  incremental changes-token syncs afterwards
- **Smart weigh-in aggregation**: measurements taken at the same moment are merged into one
  weigh-in; the newest height record is cached so BMI keeps working
- **Automatic upload to Garmin Connect**: weigh-ins are encoded as a FIT file (official Garmin
  FIT SDK) and pushed via the DI OAuth2 upload endpoint — the CSV import flow is gone entirely
- **Garmin login with MFA**: one-time sign-in (email + password + verification code); only
  Keystore-encrypted session tokens persist, credentials never touch disk
- **Background sync**: a WorkManager job runs the same sync pass roughly every 3 hours
  (network-constrained, exponential backoff) alongside the manual **Sync now** button
- **Dedup journal + offline retry**: uploaded record ids are journaled on-device so nothing is
  ever uploaded twice; failed records stay retriable for the next pass
- **Rebrand**: new name, package `com.zamotkin.extrahealthgarmin`, new vector adaptive launcher
  icon (heart + ECG pulse on navy)
- **Platform floor raised**: minSdk moves from 21 to 26 (Android 8.0) — required by the Health
  Connect client library

### Upgrade notes

- The new app installs **alongside** version 1.x (different application id); the old app must be
  uninstalled manually if no longer wanted
- The old CSV state (dedup journal entries from CSV uploads) is not migrated — Health Connect
  becomes the single source of truth
- Garmin login must be performed once after upgrading (the encrypted token store starts clean)

### Technical

- **Minimum Android version**: API 26 (Android 8.0)
- **Target Android version**: API 34
- **Compile SDK**: 36
- **Architecture**: MVVM (single activity), `health/` + `garmin/` + `data/` + `ui/` packages,
  sync state in one JSON file in `filesDir`
- **Tests**: 152 unit tests (JUnit4 + Mockito + coroutines-test + Health Connect fakes +
  MockWebServer)

---

## Version 1.0.0 — Original CSV Converter Release

The original MVP: an Android utility that accepted Goodvibes scale CSV exports via the Android
share system, validated and converted them into the Garmin Connect Fitbit-CSV import format,
saved them to Downloads and opened the Garmin web import. Multilingual (EN/RU) UI, minSdk 21.
Later 1.x iterations added direct upload to Garmin Connect via the DI OAuth2 API with a
converted-CSV fallback and a dedup journal. Fully superseded by 2.0.0.

**Release date**: October 2025

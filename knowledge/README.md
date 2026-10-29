---
type: index
title: Extra Health Garmin knowledge base (OKF v0.2)
status: current
generated: 2026-10-26
tags: [okf, index]
---
Open Knowledge Format v0.2: each file is Markdown with YAML frontmatter
(`type`, `title`, `status`, `generated`, optional `tags`). ADRs follow
Context/Decision/Consequences; entities give responsibility, key API/fields,
seams, file paths and the covering test; context files give background
pointers. Start at `project-overview.md`, then the ADRs in number order.

## Overview
- `project-overview.md` — the app, its three data pipelines, single-activity Compose shape.

## ADRs (`adr/`)
- `01-single-activity-compose-app.md` — one activity, bottom nav, R8 Measurements gating.
- `02-bp-source-seam.md` — vendor-neutral BpSource behind a registry + capabilities.
- `03-read-orchestration-guarantees.md` — SourceReadUseCase no-loss read protocol.
- `04-fresh-start-migration.md` — destructive Room v2 for BP data; tokens and sync state survive.
- `05-sync-modes-semantics.md` — History vs Current semantics per source type.
- `06-omron-pairing-key.md` — random per-install key; no serial-to-key derivation exists.
- `07-autosync-is-post-read-upload.md` — no BP scheduler; autosync rides the read pass.
- `08-ui-kit-and-locale-gates.md` — EhgTheme tokens, material3 pin, R.string/locale rules.
- `09-never-throw-gateways.md` — terminal results everywhere; CancellationException rethrown.
- `10-atomic-source-store.md` — bp-sources.json atomic, row-atomic mutations.

## Entities (`entities/`)
- `bp-source.md` — the source contract: BpSource, BpReading, SourceReadResult.
- `source-capabilities.md` — SourceCapabilities fields and who consumes them.
- `connected-source-store.md` — persisted source rows (bp-sources.json).
- `source-registry.md` — typeId-to-factory map; three registered types.
- `source-read-use-case.md` — the production read-pass orchestrator.
- `omron-ble-source.md` — Omron M7 adapter over omron/protocol + omron/ble.
- `ble1810-driver.md` — standard 0x1810 stack + fingerprint journal.
- `hc-bp-source.md` — Health Connect singleton source.
- `bp-record-entity-dao.md` — Room rows, unique (ts, slot, sourceId) index, DAO seam.
- `omron-bp-repository.md` — never-throws DAO gateway and StoreResult.
- `bp-sync-coordinator.md` — per-slot upload pass and BpUploadJournal semantics.
- `garmin-repository.md` — UI-facing Garmin gateway, single-flight uploads, signOut gate.
- `garmin-auth-client.md` — DI OAuth2 login/MFA, token store, crypto, redacting logger.
- `health-connect-weighins.md` — weigh-in/SyncState/BP/hydration pipelines.
- `omron-protocol-stack.md` — pure-JVM Omron wire protocol + Android BLE transport.
- `ui-kit-theme.md` — EhgTheme and the shared kit components.
- `approot-navigation.md` — Scaffold, nav graph, app-level dialogs, R8 gate.
- `measurements-viewmodel.md` — Measurements tab state machine.
- `settings-viewmodel.md` — Settings groups and destructive read actions.
- `main-viewmodel-home.md` — shared weigh-in/account state machine for Home.

## Context (`context/`)
- `epic-history.md` — the v3 redesign story: contract, review fixes, execution model.
- `protocol-docs.md` — pointers into docs/omron-protocol.md and docs/ble-1810-protocol.md.
- `tooling-conventions.md` — tracker, manual wiring, test layout, build/debug commands.

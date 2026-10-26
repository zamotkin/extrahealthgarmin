---
type: adr
title: ADR-04 Fresh start for BP data, surviving Garmin and sync state
status: accepted
generated: 2026-10-26
tags: [room, migration, product]
---
## Context
Epic R11 needed Room schema v2: `BloodPressureRecordEntity` gained `sourceId`
and the unique index widened to (timestampEpochSec, userSlot, sourceId).
Preserving legacy rows would require a speculative migration for data that the
devices themselves still hold.

## Decision
**Destructive Room migration for BP data, no legacy preference migration**
(product decision R11, 2026-10-25): the schema is not exported; the database
builder falls back to a destructive migration. Blood-pressure data is re-read
fresh from the sources, so legacy rows are not preserved.

**Garmin tokens and weigh-in sync state survive an in-place upgrade** — they
live outside Room (`GarminTokenStore` files, `hc-sync-state.json`), and the
v2.1.1 → 3.0.0 path was verified live on the owner's device (release APK
signed with the same local keystore, installed over v2.1.1 during the xlg.10
hardware walkthrough).

## Consequences
- First v3 launch starts BP history empty; a History re-read of each connected
  source repopulates it (the idempotent insert-ignore index absorbs re-reads).
- The unique index change rides the fresh schema; no migration JSONs to review.
- Anything that must survive upgrades belongs in `filesDir` JSON state or the
  Keystore-encrypted token files — not in the BP Room database.

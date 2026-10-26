---
type: adr
title: ADR-10 Atomic bp-sources.json with row-atomic mutations
status: accepted
generated: 2026-10-26
tags: [persistence, concurrency]
---
## Context
`FileConnectedSourceStore` initially saved whole lists after load-modify; the
xlg.5 review found that whole-list load-modify-save silently reverted
concurrent row writes (an anchor write landing between another writer's load
and save was lost).

## Decision
- **`bp-sources.json` via atomic temp+rename** (like `hc-sync-state.json`);
  if the cross-filesystem rename fails, fall back to copy-then-delete so the
  final file is always written whole, never appended to.
- **Row-atomic `update`/`remove`/`add` under one store lock**: `update(id)`
  is a single load-modify-save of that row; `remove` and `add` take the same
  lock, so a mode toggle in the UI cannot interleave with the read
  orchestrator's anchor/fallback-flag writes and lose either change.
- A missing, unreadable or corrupted file loads as an empty list with a log —
  the worst case is the user reconnecting a source, never a crash. A stored
  sync-mode string that no longer parses keeps the default (HISTORY).

## Consequences
- Concurrent writers serialize on the store; `SourceReadUseCase`'s flag write
  and `SettingsViewModel`'s toggles compose safely.
- Same atomic-write shape as `Ble1810FingerprintJournal` and the Garmin
  journals (merge-on-save variants there; see their entities).
- Covered by `ConnectedSourceStoreTest`.

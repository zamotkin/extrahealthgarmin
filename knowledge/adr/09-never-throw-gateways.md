---
type: adr
title: ADR-09 Never-throw gateways and cancellation hygiene
status: accepted
generated: 2026-10-26
tags: [error-handling, coroutines]
---
## Context
UI and background callers must render every outcome without exception
handling; an exception escaping a gateway crashes the app or kills a worker
pass. The xlg.2 review formalized this as remediation task xlg.13.

## Decision
- **`BpSource.read` returns terminal results, never throws**: sources fold
  their stack's failures onto `SourceReadResult` variants
  (ConnectionRequired/Timeout/Disconnected/Failed).
- **Stores and repositories never throw**: `OmronBpRepository` maps every DAO
  failure onto empty/failed results (`StoreResult.success=false`), reporting
  through an injected `onError` hook; `GarminRepository` and
  `BpSyncCoordinator` map failures onto `UploadOutcome`s.
- **`CancellationException` is ALWAYS rethrown, never swallowed into a
  failure result** — every catch chain re-raises it before folding; the same
  hygiene applies to the use case's own I/O guards and ViewModel passes.

## Consequences
- Callers branch on sealed variants; no defensive try/catch stacking.
- Unit tests fake the seams (DAO interface, injected seams) and assert
  outcomes, not exceptions — see `OmronBpRepositoryTest`,
  `GarminRepositoryBpTest`, `SourceReadUseCaseTest`.
- The pattern is the repository-side mirror of `data/GarminRepository`'s
  never-throws rule; new gateways must follow it (epic forbidden pattern:
  no stubs, failures surface on results).

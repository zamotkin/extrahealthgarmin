---
type: context
title: Epic tefal2garmin-app-redesign-v3-xlg — the v3 redesign story
status: current
generated: 2026-10-26
tags: [history, epic]
---
**Origin.** The redesign epic was registered 2026-10-25 at P1 with 8 draft
tasks; the owner interrupted autonomous dispatch to brainstorm device types
and user semantics first. Brainstorm outcomes (locked into the br contract):
single-activity Compose + BpSource seam is validated option A; Health Connect
promoted to a first-class BP source; one-time Omron user-slot pick with
app-owner attribution for 0x1810-without-User-ID and HC; read anchors never
advance on failed reads; Room gains sourceId + unique (timestamp, userSlot,
sourceId) index under a no-migration policy.

**The contract** hardened into 12 immutable requirements R1–R12 and 9 success
criteria (SC1: OmronActivity removed, no XML main UI; SC2: the registry
resolves omron-ble / ble-standard-1810 / health-connect; SC8: unit tests +
lint green). Forbidden anti-patterns: no second BP activity, no vendor code
outside drivers, no android.bluetooth outside BLE driver/transport packages,
no business logic in Composables, no anchor advance on failed reads, no
TODO/stubs, no DI framework, no user-visible hardcoded strings (EN+RU in
sync). Rejected (DO NOT REVISIT): BLE-only seam with HC hidden; restyling
both activities with a shared kit. R12 (docs + GitHub metadata) chained after
the xlg.10 on-device verification; R11 = the fresh-start decision (ADR-04).

**Review-caught bugs and their fixes (6):** (1) `BpSource.read` guards —
never-throw hygiene (xlg.2 review → xlg.13, folded into xlg.3); (2) the
full-read fallback flag had to persist BEFORE a Current plan (xlg.5 → xlg.16);
(3) a DAO failure must not advance the anchor (xlg.5 → xlg.16); (4)
whole-list store saves reverted concurrent row writes (xlg.5 → xlg.16); (5)
the 0x1810 fingerprint journal committed before Room confirmed the insert,
plus the zero-count ambiguity solved by `StoreResult.success` (xlg.9 →
xlg.15); (6) the signOut token-resurrection race in `persistRotatedTokens`
(xlg.7 fix). A reusable gotcha surfaced alongside: companion `R.string` vals
must be getter-backed (NoSuchMethodError under AGP 9 built-in Kotlin).

**Execution model.** ralph-cc loop with parallel agents: task .1 ran directly
in the main redesign tree while isolated locked worktrees
(`.claude/worktrees/agent-*`) served parallel tasks; heavy implementers were
quota-capped to one at a time, reviews (quality/implementer/spec) gated every
task, and each NEEDS_FIX verdict became a remediation task (xlg.13/.15/.16).
Milestones: 13/16 children closed by xlg.8 (v3.0.0 in place); xlg.10 live
hardware walkthrough on the owner's Pixel 8 Pro (release APK over v2.1.1,
8 screenshots, RU locale + dark theme); xlg.11 docs and xlg.12 GitHub
metadata landed; xlg.17 added the auto pairing key (ADR-06).

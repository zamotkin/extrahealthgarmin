---
type: adr
title: ADR-06 Omron pairing key: generated per install, never derived
status: accepted
generated: 2026-10-26
tags: [omron, security, bluetooth]
---
## Context
The Omron M7 Intelli IT gates its data channel behind an application-level
16-byte key (unlock `01 || key(16)`, docs/omron-protocol.md §3.3). Task xlg.17
researched whether the key can be computed from the device serial instead of
invented (§12).

## Decision
**The app generates one random 16-byte key per install** via
`OmronKeyStore.getOrCreate()` and unlocks with it. Rationale (verified facts):
- An UNREGISTERED monitor accepts ANY key — captured on this very
  HEM-7361T-ALRU (arbitrary key answered `8100`, §3.3/§12 row 5).
- NO serial→key derivation exists anywhere in the community: omblepy ships an
  arbitrary key (`deadbeaf... #arbitrary choice`); esphome-omron states
  "The key is yours to invent"; the Home Assistant write-ups corroborate the
  key-in-the-client model (§12 table).
- Manual entry remains an ADVANCED fallback (collapsed in the pairing step,
  `submitPairingKey`) for monitors previously registered by another client
  (UBPM/Omron Connect with their key) — unlock with our key would fail there.

**Documented limitation:** all Omron sources share ONE key file today;
per-source keys land with future device targeting, which the driver does not
yet do — it connects to the first advertising monitor.

## Consequences
- The add-source flow asks for NO key (`autoPairingKey = true`); the
  capabilities carry `pairingKeyBytes = 16`, so the flow validates manual keys
  against the capability, never a vendor constant.
- A monitor registered by another client stays reachable via manual entry.
- Research references: docs/omron-protocol.md §3.1/§3.3/§12; test:
  `OmronKeyStoreTest`.

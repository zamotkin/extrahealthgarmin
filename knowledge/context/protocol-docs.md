---
type: context
title: Protocol documentation pointers
status: current
generated: 2026-10-26
tags: [docs, protocol, pointers]
---
The two wire-protocol specs live in `docs/` and are the authority — this
knowledge base links to them and never duplicates their tables.

**`docs/omron-protocol.md` — Omron M7 Intelli IT (HEM-7361T-ALRU), v1.0
verified.** omblepy-derived baseline confirmed against a real-device btsnoop
capture (2026-10-24). Coverage: §1 GATT map (standard services + the
proprietary channel/unlock UUIDs); §2 SMP bonding; §3 the application-level
pairing key (properties, registration `00||key`, unlock `01||key`,
discrepancies); §4 session protocol (lifecycle, XOR-CRC frames, read/write
blocks, multi-channel chunking); §5 EEPROM memory map incl. the unread-counters
and time-sync blocks; §6 the 16-byte record layout with the corrected bit
numbering; §7 discrepancy table; §8 the binding decision log; §9 the capture
runbook (HCI snoop, Wireshark, redaction); §10 known gaps; §11 capture
amendments; §12 the pairing-key origin research (task xlg.17 — no
serial→key derivation exists; see ADR-06).

**`docs/ble-1810-protocol.md` — Bluetooth SIG Blood Pressure Service
(0x1810), v0.2 draft.** SIG-verified layout, community-verified behavior.
Coverage: §1 overview and relation to the Omron vendor driver; §2 service
table (0x2A35/0x2A36/0x2A49/0x2A52 RACP summary); §3 the measurement payload
(SFLOAT/medfloat16, flags, time stamp, User ID, worked example); §4 the
Android read session incl. the per-device quirks matrix (§4.2 — transfer
semantics, self-disconnect, missing timestamps, duplicate delivery); §5
limitations vs the Omron vendor protocol; §6 sources; §7 driver
implementation notes v1 (parser decisions, fingerprint dedup §7.2, session
decisions, connection+bonding, capabilities/registration §7.5, error
surfaces).

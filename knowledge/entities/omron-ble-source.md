---
type: entity
title: OmronBleSource — Omron M7 adapter
status: current
generated: 2026-10-26
tags: [devices, omron, ble]
---
**Responsibility.** Maps the `BpSource` seam onto the existing Omron M7 BLE
stack — `omron/ble` and `omron/protocol` are untouched; the adapter only
translates. File: `devices/omron/OmronBleSource.kt` (type object
`OmronBleSourceType` binds the Context).

**Capabilities.** type id `omron-ble`; `userAttribution = SLOTS`,
`supportedSyncModes = {HISTORY, CURRENT}`, `requiresPairingKey = true`,
`pairingKeyBytes = OmronProtocol.PAIRING_KEY_BYTES` (16),
`autoPairingKey = true` (ADR-06), `userSlotCount = 2`.

**Mapping.** `read` requires `userSlot`, maps `SyncMode` onto the stack's
full-read flag (HISTORY = whole ring, CURRENT = unread window), gets the key
via the injected `keyProvider` (failures fold onto `Failed`), and folds
`OmronBleResult` (Success/BondingRequired/AuthFailed→ConnectionRequired/
Timeout/Disconnected/Failed).

**Stack pointers.** Wire protocol: `omron/protocol/` (`OmronProtocol` constants,
`SessionFrameCodec`, `RecordDecoder`, `UnlockCodec`, `ReadPlanCalculator`,
`OmronSession` over the `OmronTransport` seam). Android BLE: `omron/ble/`
(`GattChannelTransport`, `RetryCommandRunner`, `OmronGattConnection`,
`OmronBleClient`, `OmronKeyStore`). Spec: `docs/omron-protocol.md`.
Limitation: connects to the first advertising monitor (ADR-06).

**Tests.** `OmronBleSourceTest`; the stack itself: `OmronSessionTest`,
`RecordDecoderTest`, `SessionFrameCodecTest`, `UnlockCodecTest`,
`ReadPlanCalculatorTest`, `GattChannelTransportTest`, `OmronBleClientTest`,
`OmronKeyStoreTest` et al.

---
type: entity
title: omron/protocol + omron/ble — the Omron M7 wire stack
status: current
generated: 2026-10-26
tags: [omron, ble, protocol]
---
**Responsibility.** The pure-JVM wire protocol and the Android BLE transport
for the Omron M7 Intelli IT (HEM-7361T-ALRU). Spec:
`docs/omron-protocol.md` (v1.0, capture-verified). The devices/ adapter
(`omron-ble-source.md`) maps this stack onto the `BpSource` seam.

**`omron/protocol/` (pure JVM, no Android).** `OmronProtocol` holds every
constant (UUIDs, `PAIRING_KEY_BYTES`); `SessionFrameCodec` builds/parses the
XOR-CRC session frames; `RecordDecoder` unpacks bit-packed 16-byte EEPROM
records (systolic = raw bits + 25; corrected omblepy bit numbering);
`SFloatDecoder` the medfloat16 values; `UnlockCodec` frames the 17-byte
unlock payloads (`01 || key(16)` every session, `00 || key(16)` registration
— optional on the ALRU, see ADR-06); `ReadPlanCalculator` turns the ring
buffer counters (per-user `lastWrittenSlot_i`/`unreadRecords_i` at
settings[0x00:0x10]) into read commands; `OmronSession` orchestrates the
readout over the `OmronTransport` seam.

**`omron/ble/` (Android).** `GattChannelTransport` implements `OmronTransport`
with 16-byte channel chunking (`ChannelChunker`, `PendingWriteSlot`) over the
`GattChannelConnection` seam; `RetryCommandRunner` retries each command 5x
under a 1 s timeout; `OmronGattConnection` is the ONLY file touching
`android.bluetooth` (connect/GATT glue, `gatt.close()` on every exit path);
`OmronBleClient` folds the readout into `OmronBleResult`
(Success/BondingRequired/AuthFailed/Timeout/Disconnected/Failed). The pairing
key lives in `OmronKeyStore`, AES-encrypted by `KeystoreAesPairingKeyCrypto`
(Android Keystore, alias `extrahealthgarmin_omron_pairing_key`).

**Tests.** One test class per file: `SessionFrameCodecTest`,
`RecordDecoderTest`, `SFloatDecoderTest`, `UnlockCodecTest`,
`ReadPlanCalculatorTest`, `OmronSessionTest`, `GattChannelTransportTest`,
`ChannelChunkerTest`, `PendingWriteSlotTest`, `RetryCommandRunnerTest`,
`OmronBleClientTest`, `OmronKeyStoreTest`.

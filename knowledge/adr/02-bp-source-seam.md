---
type: adr
title: ADR-02 Vendor-neutral BpSource seam behind a registry
status: accepted
generated: 2026-10-26
tags: [architecture, bluetooth, health-connect]
---
## Context
Blood pressure arrives from heterogeneous devices: Omron's proprietary
EEPROM-over-BLE protocol, the Bluetooth SIG 0x1810 service, and Health
Connect. The v2 code was Omron-only; the epic (R2) demanded a seam where a
new vendor costs a driver and nothing else.

## Decision
`devices/api/BpSource.kt` defines the contract: `BpSource.read(request)` runs
one pass and returns a terminal `SourceReadResult` (Success with readings or
counters / ConnectionRequired / Timeout / Disconnected / Failed) — never an
exception. `BpSourceType` is the factory/identity (typeId, displayNameRes,
capabilities) in two shapes: `BleBpSourceType` (one source per discovered BLE
device) and `SingletonBpSourceType` (one instance; Health Connect's connect
step is a permission grant, not a scan). `SourceCapabilities` declares
`userAttribution` (SLOTS/NONE), `supportedSyncModes`, `requiresPairingKey`,
`pairingKeyBytes`, `autoPairingKey`, `userSlotCount`. `SourceRegistry`
(`devices/SourceRegistry.kt`) is a plain map — no reflection, no plugins:
registration is one entry in `HealthSyncWiring.sourceRegistry()`
(omron-ble, ble-standard-1810, health-connect).

## Consequences
- Adding a vendor = driver/adapter + one registration line; no core surgery
  (epic R2, SC2). Vendor-specific code stays inside the driver; no
  `android.bluetooth` imports outside BLE driver/transport packages.
- Drivers arrive as injected seams (key provider, BLE read function), so the
  adapters are plain JVM and unit-testable with fakes.
- Adding a source type later only means a new `BpSourceType` + companion
  capabilities; `ConnectedSourceStore` rows already carry the type id.

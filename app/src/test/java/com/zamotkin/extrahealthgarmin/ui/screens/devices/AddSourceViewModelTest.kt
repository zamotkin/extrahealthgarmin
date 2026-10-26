package com.zamotkin.extrahealthgarmin.ui.screens.devices

import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.zamotkin.extrahealthgarmin.R
import com.zamotkin.extrahealthgarmin.devices.SourceRegistry
import com.zamotkin.extrahealthgarmin.devices.api.BleBpSourceType
import com.zamotkin.extrahealthgarmin.devices.api.BleSourceScanner
import com.zamotkin.extrahealthgarmin.devices.api.BpSource
import com.zamotkin.extrahealthgarmin.devices.api.BpSourceType
import com.zamotkin.extrahealthgarmin.devices.api.DiscoveredBleDevice
import com.zamotkin.extrahealthgarmin.devices.api.SingletonBpSourceType
import com.zamotkin.extrahealthgarmin.devices.api.SourceCapabilities
import com.zamotkin.extrahealthgarmin.devices.api.SourceReadRequest
import com.zamotkin.extrahealthgarmin.devices.api.SourceReadResult
import com.zamotkin.extrahealthgarmin.devices.api.SyncMode
import com.zamotkin.extrahealthgarmin.devices.api.UserAttribution
import com.zamotkin.extrahealthgarmin.devices.store.ConnectedSource
import com.zamotkin.extrahealthgarmin.devices.store.ConnectedSourceStore
import com.zamotkin.extrahealthgarmin.devices.store.FileConnectedSourceStore
import com.zamotkin.extrahealthgarmin.health.HealthPermissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Pins the [AddSourceViewModel] state machine (task
 * tefal2garmin-app-redesign-v3-xlg.3): scan accumulation and address dedup,
 * late-callback guarding after stop, scanner errors, the BLE permission
 * gate (including the explanatory denial state), pairing-key validation
 * before save, persistence through a faked [ConnectedSourceStore] and the
 * Health Connect grant path.
 *
 * Same harness conventions as ui.MainViewModelSyncTest: no Robolectric,
 * Dispatchers.Main swapped for a StandardTestDispatcher, LiveData read
 * under InstantTaskExecutorRule, hand fakes for every seam. The fake source
 * types mirror SourceRegistryTest's third-type proof: plain JVM objects.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddSourceViewModelTest {

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    // ---- fakes ------------------------------------------------------ //

    /** A BpSource the fake types hand out; the flow never reads it. */
    private class FakeSource(override val sourceTypeId: String) : BpSource {
        override val displayNameRes: Int get() = 0
        override val capabilities: SourceCapabilities = SourceCapabilities(
            userAttribution = UserAttribution.NONE,
            supportedSyncModes = setOf(SyncMode.HISTORY, SyncMode.CURRENT),
            requiresPairingKey = false
        )

        override suspend fun read(request: SourceReadRequest): SourceReadResult =
            SourceReadResult.Failed("not read in tests")
    }

    /** BLE type shaped like Omron ([requiresKey]) or like the 0x1810 driver.
     *  [keyBytes] lets a test pin a non-Omron key length (task xlg.14). */
    private class FakeBleType(
        override val typeId: String,
        private val requiresKey: Boolean,
        private val keyBytes: Int = 16,
        private val autoKey: Boolean = false,
    ) : BleBpSourceType {
        override val displayNameRes: Int get() = R.string.bp_source_type_omron_ble
        override val capabilities: SourceCapabilities = SourceCapabilities(
            userAttribution = if (requiresKey) UserAttribution.SLOTS else UserAttribution.NONE,
            supportedSyncModes = setOf(SyncMode.HISTORY, SyncMode.CURRENT),
            requiresPairingKey = requiresKey,
            pairingKeyBytes = if (requiresKey) keyBytes else null,
            autoPairingKey = requiresKey && autoKey
        )

        override fun createFromDevice(context: Context, device: DiscoveredBleDevice): BpSource =
            FakeSource(typeId)
    }

    /** Singleton type shaped like Health Connect (Current-only, no key). */
    private class FakeSingletonType(
        override val typeId: String,
    ) : SingletonBpSourceType {
        override val displayNameRes: Int get() = R.string.bp_source_type_health_connect
        override val capabilities: SourceCapabilities = SourceCapabilities(
            userAttribution = UserAttribution.NONE,
            supportedSyncModes = setOf(SyncMode.CURRENT),
            requiresPairingKey = false
        )

        override fun createSingleton(context: Context): BpSource = FakeSource(typeId)
    }

    /** Records every session so tests can fire late callbacks per session. */
    private class FakeScanner : BleSourceScanner {
        val sessions = mutableListOf<Session>()

        inner class Session(
            val onDevice: (DiscoveredBleDevice) -> Unit,
            val onError: (String) -> Unit,
        ) : BleSourceScanner.ScanSession {
            var stopped = false
                private set

            override fun stop() {
                stopped = true
            }
        }

        override fun start(
            onDevice: (DiscoveredBleDevice) -> Unit,
            onError: (String) -> Unit,
        ): BleSourceScanner.ScanSession = Session(onDevice, onError).also { sessions += it }
    }

    private class FakeStore(var sources: List<ConnectedSource> = emptyList()) : ConnectedSourceStore {
        val saved = mutableListOf<List<ConnectedSource>>()
        val added = mutableListOf<ConnectedSource>()
        var failSave = false

        override fun load(): List<ConnectedSource> = sources

        override fun save(sources: List<ConnectedSource>): Boolean {
            if (failSave) return false
            saved += sources
            this.sources = sources
            return true
        }

        /**
         * Mirrors the production [FileConnectedSourceStore] override: one
         * atomic append that never routes through the whole-list [save]
         * seam - which is what lets the tests see a ViewModel that writes
         * whole stale lists (task xlg.16).
         */
        override fun add(source: ConnectedSource): Boolean {
            if (failSave) return false
            added += source
            sources = sources + source
            return true
        }
    }

    /** Exposes the protected onCleared so tests can simulate back navigation. */
    private class TestableViewModel(
        registry: SourceRegistry,
        store: ConnectedSourceStore,
        scannerFor: (BleBpSourceType) -> BleSourceScanner?,
        missingBlePermissions: () -> List<String>,
        hcBpPermissionGranted: suspend () -> Boolean,
        storePairingKey: (typeId: String, key: ByteArray) -> String,
        autoPairingKeyRef: (typeId: String) -> String,
        clock: () -> Long,
    ) : AddSourceViewModel(
        registry, store, scannerFor, missingBlePermissions, hcBpPermissionGranted, storePairingKey, autoPairingKeyRef, clock
    ) {
        fun simulateCleared() = onCleared()
    }

    // ---- fixtures --------------------------------------------------- //

    private val keyBleType = FakeBleType("key-ble", requiresKey = true)
    private val autoKeyType = FakeBleType("auto-ble", requiresKey = true, autoKey = true)
    private val keylessBleType = FakeBleType("keyless-ble", requiresKey = false)
    private val hcType = FakeSingletonType("hc")
    private val scanner = FakeScanner()
    private val store = FakeStore()

    private var missingPermissions: List<String> = emptyList()
    private var hcGranted: Boolean = false
    private val storedKeys = mutableListOf<Pair<String, ByteArray>>()
    private val autoKeyRefs = mutableListOf<String>()
    private var autoKeyRefFails = false
    private var nowMs = 1_000_000L

    private fun newViewModel(
        types: List<BpSourceType> = listOf(keyBleType, autoKeyType, keylessBleType, hcType),
    ): TestableViewModel = TestableViewModel(
        registry = SourceRegistry(types),
        store = store,
        scannerFor = { scanner },
        missingBlePermissions = { missingPermissions },
        hcBpPermissionGranted = { hcGranted },
        storePairingKey = { typeId, key ->
            storedKeys += typeId to key
            "stored-key-ref"
        },
        autoPairingKeyRef = { typeId ->
            if (autoKeyRefFails) throw IllegalStateException("key store died")
            autoKeyRefs += typeId
            "auto-key-ref"
        },
        clock = { nowMs },
    )

    /** Selects the option with [typeId] from the type picker. */
    private fun AddSourceViewModel.selectOption(typeId: String) {
        val options = (uiState.value as AddSourceUiState.TypePicker).options
        onSelectOption(options.first { it.typeId == typeId })
    }

    /** Starts the flow for [typeId] with permissions granted and one device emitted. */
    private fun startedScan(vm: TestableViewModel, typeId: String = "key-ble"): FakeScanner.Session {
        vm.selectOption(typeId)
        scanner.sessions.last().onDevice(DiscoveredBleDevice("OMRON M7", "AA:BB:CC:DD:EE:01", -60))
        return scanner.sessions.last()
    }

    private fun inPairingWithDevice(vm: TestableViewModel, typeId: String = "key-ble") {
        startedScan(vm, typeId)
        vm.selectDevice(DiscoveredBleDevice("OMRON M7", "AA:BB:CC:DD:EE:01", -60))
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---- type picker ------------------------------------------------ //

    @Test
    fun `type picker lists the registry types with their capabilities`() {
        val vm = newViewModel()
        val options = (vm.uiState.value as AddSourceUiState.TypePicker).options
        assertEquals(listOf("key-ble", "auto-ble", "keyless-ble", "hc"), options.map { it.typeId })
        val key = options[0]
        assertTrue(key.isBle)
        assertTrue(key.requiresPairingKey)
        assertFalse(key.autoPairingKey) // a key type without automation keeps the manual field
        assertEquals(16, key.pairingKeyBytes) // the type's capability, not a constant
        assertTrue(key.supportsUserSlots)
        assertEquals(setOf(SyncMode.HISTORY, SyncMode.CURRENT), key.supportedSyncModes)
        val auto = options[1]
        assertTrue(auto.requiresPairingKey) // the device still needs a key at unlock...
        assertTrue(auto.autoPairingKey)     // ...the app just supplies it (task xlg.17)
        val hc = options[3]
        assertFalse(hc.isBle)
        assertFalse(hc.requiresPairingKey)
        assertNull(hc.pairingKeyBytes) // keyless type
        assertFalse(hc.supportsUserSlots)
        assertEquals(setOf(SyncMode.CURRENT), hc.supportedSyncModes)
    }

    // ---- permissions ------------------------------------------------ //

    @Test
    fun `selecting a ble type with permissions granted starts scanning`() {
        val vm = newViewModel()
        vm.selectOption("key-ble")
        assertTrue(vm.uiState.value is AddSourceUiState.Scanning)
        assertEquals(1, scanner.sessions.size)
    }

    @Test
    fun `missing ble permissions surface the request state`() {
        missingPermissions = listOf("android.permission.BLUETOOTH_SCAN")
        val vm = newViewModel()
        vm.selectOption("key-ble")
        val state = vm.uiState.value as AddSourceUiState.BlePermissionsRequired
        assertEquals(listOf("android.permission.BLUETOOTH_SCAN"), state.permissions)
        assertFalse(state.denied)
        assertTrue(scanner.sessions.isEmpty())
    }

    @Test
    fun `granted ble permissions resume the flow into scanning`() {
        missingPermissions = listOf("android.permission.BLUETOOTH_SCAN")
        val vm = newViewModel()
        vm.selectOption("key-ble")
        vm.onBlePermissionsResult(mapOf("android.permission.BLUETOOTH_SCAN" to true))
        assertTrue(vm.uiState.value is AddSourceUiState.Scanning)
        assertEquals(1, scanner.sessions.size)
    }

    @Test
    fun `denied ble permissions show the explanatory state`() {
        missingPermissions = listOf("android.permission.BLUETOOTH_SCAN")
        val vm = newViewModel()
        vm.selectOption("key-ble")
        vm.onBlePermissionsResult(mapOf("android.permission.BLUETOOTH_SCAN" to false))
        val state = vm.uiState.value as AddSourceUiState.BlePermissionsRequired
        assertTrue(state.denied)
        assertTrue(scanner.sessions.isEmpty())
    }

    // ---- scan ------------------------------------------------------- //

    @Test
    fun `scan results accumulate in discovery order`() {
        val vm = newViewModel()
        val session = startedScan(vm)
        session.onDevice(DiscoveredBleDevice("Second", "AA:BB:CC:DD:EE:02", -70))
        val devices = (vm.uiState.value as AddSourceUiState.Scanning).devices
        assertEquals(
            listOf("AA:BB:CC:DD:EE:01", "AA:BB:CC:DD:EE:02"),
            devices.map { it.address }
        )
    }

    @Test
    fun `duplicate addresses are deduped keeping the first advertisement`() {
        val vm = newViewModel()
        val session = startedScan(vm)
        session.onDevice(DiscoveredBleDevice("OMRON again", "AA:BB:CC:DD:EE:01", -40))
        session.onDevice(DiscoveredBleDevice("Other", "AA:BB:CC:DD:EE:02", -80))
        val devices = (vm.uiState.value as AddSourceUiState.Scanning).devices
        assertEquals(2, devices.size)
        assertEquals("OMRON M7", devices[0].name) // first advertisement wins
        assertEquals(-60, devices[0].rssi)
    }

    @Test
    fun `late scanner callbacks after stop are ignored`() {
        val vm = newViewModel()
        val session = startedScan(vm)
        vm.stopScan()
        assertTrue(vm.uiState.value is AddSourceUiState.Found)
        session.onDevice(DiscoveredBleDevice("Late", "AA:BB:CC:DD:EE:99", -10))
        session.onError("late failure")
        val state = vm.uiState.value as AddSourceUiState.Found
        assertEquals(listOf("AA:BB:CC:DD:EE:01"), state.devices.map { it.address })
        assertTrue(session.stopped)
    }

    @Test
    fun `scanner error lands in an error state without crashing`() {
        val vm = newViewModel()
        val session = startedScan(vm)
        session.onError("BLE adapter died")
        val state = vm.uiState.value as AddSourceUiState.Error
        assertEquals(R.string.add_source_scan_error, state.messageRes)
        assertEquals("BLE adapter died", state.detail)
        assertTrue(session.stopped)
    }

    @Test
    fun `selecting a device stops the scan and enters pairing`() {
        val vm = newViewModel()
        val session = startedScan(vm)
        vm.selectDevice(DiscoveredBleDevice("OMRON M7", "AA:BB:CC:DD:EE:01", -60))
        val state = vm.uiState.value as AddSourceUiState.Pairing
        assertEquals("key-ble", state.option.typeId)
        assertEquals("AA:BB:CC:DD:EE:01", state.device?.address)
        assertTrue(session.stopped)
    }

    @Test
    fun `scan again restarts with a fresh list and dead sessions stay dead`() {
        val vm = newViewModel()
        val first = startedScan(vm)
        vm.stopScan()
        vm.scanAgain()
        val scanning = vm.uiState.value as AddSourceUiState.Scanning
        assertTrue(scanning.devices.isEmpty())
        assertEquals(2, scanner.sessions.size)
        first.onDevice(DiscoveredBleDevice("Zombie", "AA:BB:CC:DD:EE:77", -10))
        assertTrue((vm.uiState.value as AddSourceUiState.Scanning).devices.isEmpty())
    }

    @Test
    fun `onCleared stops the scan so back navigation leaks no scanner`() {
        val vm = newViewModel()
        val session = startedScan(vm)
        vm.simulateCleared()
        assertTrue(session.stopped)
    }

    // ---- pair + save (BLE) ------------------------------------------ //

    @Test
    fun `wrong-length pairing key is rejected before any save`() {
        val vm = newViewModel()
        inPairingWithDevice(vm)
        vm.selectUserSlot(1)
        vm.submitPairingKey("123456789012345") // 15 bytes
        val state = vm.uiState.value as AddSourceUiState.Pairing
        assertTrue(state.keyError)
        assertTrue(store.saved.isEmpty())
        assertTrue(storedKeys.isEmpty())
        vm.submitPairingKey("12345678901234567") // 17 bytes
        assertTrue((vm.uiState.value as AddSourceUiState.Pairing).keyError)
        assertTrue(store.saved.isEmpty())
    }

    @Test
    fun `key length is validated against the type capability not a fixed 16`() {
        // A future key-requiring type with a different key length (task xlg.14).
        val eightByteType = FakeBleType("eight-byte-ble", requiresKey = true, keyBytes = 8)
        val vm = newViewModel(types = listOf(eightByteType))
        inPairingWithDevice(vm, "eight-byte-ble")
        vm.selectUserSlot(1)
        vm.submitPairingKey("1234567890123456") // Omron's 16 - wrong for this type
        assertTrue((vm.uiState.value as AddSourceUiState.Pairing).keyError)
        assertTrue(store.saved.isEmpty())
        vm.clearKeyError()
        vm.submitPairingKey("12345678") // matches the type's own capability
        assertTrue(vm.uiState.value is AddSourceUiState.Done)
        assertArrayEquals("12345678".toByteArray(), storedKeys.single().second)
    }

    @Test
    fun `clearKeyError resets the marker when the user edits the key`() {
        val vm = newViewModel()
        inPairingWithDevice(vm)
        vm.selectUserSlot(1)
        vm.submitPairingKey("short")
        vm.clearKeyError()
        assertFalse((vm.uiState.value as AddSourceUiState.Pairing).keyError)
    }

    @Test
    fun `valid pairing key stores the key and saves the source`() {
        val existing = ConnectedSource(
            id = "existing",
            sourceTypeId = "hc",
            name = "hc",
            createdAt = 1L
        )
        store.sources = listOf(existing)
        val vm = newViewModel()
        inPairingWithDevice(vm)
        vm.selectUserSlot(2)
        vm.submitPairingKey("1234567890123456")
        val done = vm.uiState.value as AddSourceUiState.Done
        assertEquals(1, storedKeys.size)
        assertEquals("key-ble", storedKeys[0].first)
        assertArrayEquals("1234567890123456".toByteArray(), storedKeys[0].second)
        // Task xlg.16: the save is one row-atomic add - never a whole-list
        // save the ViewModel built from a stale load.
        assertEquals(1, store.added.size)
        assertTrue(store.saved.isEmpty())
        assertEquals(listOf(existing, done.source), store.sources) // existing rows preserved
        val saved = done.source
        assertEquals("key-ble", saved.sourceTypeId)
        assertEquals("OMRON M7", saved.name)
        assertEquals("AA:BB:CC:DD:EE:01", saved.address)
        assertEquals("stored-key-ref", saved.pairingKeyRef)
        assertEquals(2, saved.userSlot) // the one-time pick (task xlg.4)
        assertEquals(SyncMode.HISTORY, saved.syncMode)
        assertEquals(nowMs, saved.createdAt)
        assertEquals(saved, store.added[0])
    }

    @Test
    fun `the save keeps a row-level update that landed while the flow was running`() {
        // A background read pass updates an existing row between the flow's
        // start and the save - the row-atomic add must not revert it (the
        // old whole-list save rewrote every row from a fresh-but-then-stale
        // load under a true race).
        val existing = ConnectedSource(
            id = "existing",
            sourceTypeId = "hc",
            name = "hc",
            createdAt = 1L
        )
        store.sources = listOf(existing)
        val vm = newViewModel()
        inPairingWithDevice(vm)
        vm.selectUserSlot(1)
        store.sources = listOf(existing.copy(lastReadAnchor = 42L, fullReadFallbackPending = true))
        vm.submitPairingKey("1234567890123456")

        assertTrue(vm.uiState.value is AddSourceUiState.Done)
        assertEquals(1, store.added.size)
        assertTrue(store.saved.isEmpty())
        val survivor = store.sources.first { it.id == "existing" }
        assertEquals(42L, survivor.lastReadAnchor)
        assertTrue(survivor.fullReadFallbackPending)
    }

    @Test
    fun `non-ascii device names survive the flow`() {
        val vm = newViewModel()
        vm.selectOption("key-ble")
        scanner.sessions.last().onDevice(DiscoveredBleDevice("Омрон M7", "AA:BB:CC:DD:EE:05", -55))
        vm.selectDevice(DiscoveredBleDevice("Омрон M7", "AA:BB:CC:DD:EE:05", -55))
        vm.selectUserSlot(1)
        vm.submitPairingKey("1234567890123456")
        val done = vm.uiState.value as AddSourceUiState.Done
        assertEquals("Омрон M7", done.source.name)
    }

    @Test
    fun `unnamed device falls back to the address as name`() {
        val vm = newViewModel()
        vm.selectOption("key-ble")
        scanner.sessions.last().onDevice(DiscoveredBleDevice("", "AA:BB:CC:DD:EE:06", -55))
        vm.selectDevice(DiscoveredBleDevice("", "AA:BB:CC:DD:EE:06", -55))
        vm.selectUserSlot(1)
        vm.submitPairingKey("1234567890123456")
        assertEquals("AA:BB:CC:DD:EE:06", (vm.uiState.value as AddSourceUiState.Done).source.name)
    }

    @Test
    fun `keyless ble source saves without a pairing key`() {
        val vm = newViewModel()
        inPairingWithDevice(vm, "keyless-ble")
        vm.saveSource()
        val done = vm.uiState.value as AddSourceUiState.Done
        assertEquals("keyless-ble", done.source.sourceTypeId)
        assertNull(done.source.pairingKeyRef)
        assertTrue(storedKeys.isEmpty())
    }

    @Test
    fun `saveSource is a no-op for key-requiring types`() {
        val vm = newViewModel()
        inPairingWithDevice(vm)
        vm.saveSource()
        assertTrue(vm.uiState.value is AddSourceUiState.Pairing)
        assertTrue(store.saved.isEmpty())
    }

    @Test
    fun `failed store write lands in an error state`() {
        store.failSave = true
        val vm = newViewModel()
        inPairingWithDevice(vm)
        vm.selectUserSlot(1)
        vm.submitPairingKey("1234567890123456")
        val state = vm.uiState.value as AddSourceUiState.Error
        assertEquals(R.string.add_source_save_error, state.messageRes)
        assertTrue(store.added.isEmpty())
    }

    // ---- user-slot pick (task xlg.4) -------------------------------- //

    @Test
    fun `slots type pairing offers the type's user slots`() {
        val vm = newViewModel()
        inPairingWithDevice(vm) // key-ble = SLOTS attribution
        val state = vm.uiState.value as AddSourceUiState.Pairing
        assertTrue(state.option.supportsUserSlots)
        assertEquals(listOf(1, 2), state.option.userSlots)
        assertNull(state.selectedUserSlot)
        assertFalse(state.slotError)
    }

    @Test
    fun `none type pairing offers no user slots`() {
        val vm = newViewModel()
        inPairingWithDevice(vm, "keyless-ble") // NONE attribution
        val state = vm.uiState.value as AddSourceUiState.Pairing
        assertFalse(state.option.supportsUserSlots)
        assertTrue(state.option.userSlots.isEmpty())
        assertNull(state.selectedUserSlot)
    }

    @Test
    fun `selecting a user slot stores the choice in the pairing state`() {
        val vm = newViewModel()
        inPairingWithDevice(vm)
        vm.selectUserSlot(2)
        assertEquals(2, (vm.uiState.value as AddSourceUiState.Pairing).selectedUserSlot)
    }

    @Test
    fun `selectUserSlot is a no-op for types without user slots`() {
        val vm = newViewModel()
        inPairingWithDevice(vm, "keyless-ble")
        vm.selectUserSlot(1)
        val state = vm.uiState.value as AddSourceUiState.Pairing
        assertNull(state.selectedUserSlot)
        assertFalse(state.slotError)
    }

    @Test
    fun `submitting without a picked slot marks the error and saves nothing`() {
        val vm = newViewModel()
        inPairingWithDevice(vm)
        vm.submitPairingKey("1234567890123456")
        val state = vm.uiState.value as AddSourceUiState.Pairing
        assertTrue(state.slotError)
        assertFalse(state.keyError) // the key was fine; the slot is the blocker
        assertTrue(store.saved.isEmpty())
        assertTrue(storedKeys.isEmpty())
    }

    @Test
    fun `picking a slot after the error clears it`() {
        val vm = newViewModel()
        inPairingWithDevice(vm)
        vm.submitPairingKey("1234567890123456")
        vm.selectUserSlot(1)
        assertFalse((vm.uiState.value as AddSourceUiState.Pairing).slotError)
    }

    @Test
    fun `the picked slot is persisted into the connected source`() {
        val vm = newViewModel()
        inPairingWithDevice(vm)
        vm.selectUserSlot(2)
        vm.submitPairingKey("1234567890123456")
        val saved = (vm.uiState.value as AddSourceUiState.Done).source
        assertEquals(2, saved.userSlot)
        assertEquals(2, store.added.single().userSlot)
    }

    @Test
    fun `reset forgets a picked slot`() {
        val vm = newViewModel()
        inPairingWithDevice(vm)
        vm.selectUserSlot(1)
        vm.reset()
        // A fresh flow to pairing starts with no slot pre-selected.
        vm.selectOption("key-ble")
        scanner.sessions.last().onDevice(DiscoveredBleDevice("OMRON M7", "AA:BB:CC:DD:EE:01", -60))
        vm.selectDevice(DiscoveredBleDevice("OMRON M7", "AA:BB:CC:DD:EE:01", -60))
        val state = vm.uiState.value as AddSourceUiState.Pairing
        assertNull(state.selectedUserSlot)
        assertFalse(state.slotError)
    }

    // ---- automatic pairing key (task xlg.17) ------------------------ //

    @Test
    fun `auto key type saves without any key input`() {
        val vm = newViewModel()
        inPairingWithDevice(vm, "auto-ble")
        vm.selectUserSlot(1)
        vm.saveSource()
        val done = vm.uiState.value as AddSourceUiState.Done
        assertEquals("auto-ble", done.source.sourceTypeId)
        assertEquals("auto-key-ref", done.source.pairingKeyRef)
        assertEquals(1, done.source.userSlot)
        assertEquals(listOf("auto-ble"), autoKeyRefs)
        assertTrue(storedKeys.isEmpty()) // nothing typed - no manual store call
    }

    @Test
    fun `auto key save still demands the user slot pick`() {
        val vm = newViewModel()
        inPairingWithDevice(vm, "auto-ble")
        vm.saveSource()
        val state = vm.uiState.value as AddSourceUiState.Pairing
        assertTrue(state.slotError)
        assertTrue(autoKeyRefs.isEmpty()) // no key generated before the pick
        assertTrue(store.added.isEmpty())
    }

    @Test
    fun `auto key seam failure lands in an error state`() {
        autoKeyRefFails = true
        val vm = newViewModel()
        inPairingWithDevice(vm, "auto-ble")
        vm.selectUserSlot(1)
        vm.saveSource()
        val state = vm.uiState.value as AddSourceUiState.Error
        assertEquals(R.string.add_source_save_error, state.messageRes)
        assertTrue(store.added.isEmpty())
    }

    @Test
    fun `manual key entry still overrides the automatic path`() {
        // The advanced fallback (collapsed in the UI): a manually entered
        // key is stored through the manual seam and no key is generated.
        val vm = newViewModel()
        inPairingWithDevice(vm, "auto-ble")
        vm.selectUserSlot(2)
        vm.submitPairingKey("1234567890123456")
        val done = vm.uiState.value as AddSourceUiState.Done
        assertEquals("stored-key-ref", done.source.pairingKeyRef)
        assertEquals(1, storedKeys.size)
        assertEquals("auto-ble", storedKeys[0].first)
        assertArrayEquals("1234567890123456".toByteArray(), storedKeys[0].second)
        assertTrue(autoKeyRefs.isEmpty())
    }

    @Test
    fun `wrong-length manual key is rejected on the auto path too`() {
        val vm = newViewModel()
        inPairingWithDevice(vm, "auto-ble")
        vm.selectUserSlot(1)
        vm.submitPairingKey("12345")
        assertTrue((vm.uiState.value as AddSourceUiState.Pairing).keyError)
        assertTrue(autoKeyRefs.isEmpty())
        assertTrue(store.added.isEmpty())
    }

    // ---- pair + save (Health Connect) ------------------------------- //

    @Test
    fun `health connect pre-check marks the permission granted`() {
        hcGranted = true
        val vm = newViewModel()
        vm.selectOption("hc")
        testDispatcher.scheduler.advanceUntilIdle()
        val state = vm.uiState.value as AddSourceUiState.Pairing
        assertTrue(state.permissionGranted)
        assertFalse(state.permissionDenied)
        assertNull(state.device)
    }

    @Test
    fun `health connect save names the source after the type and defaults to current`() {
        hcGranted = true
        val vm = newViewModel()
        vm.selectOption("hc")
        testDispatcher.scheduler.advanceUntilIdle()
        vm.saveSource()
        val saved = (vm.uiState.value as AddSourceUiState.Done).source
        assertEquals("hc", saved.sourceTypeId)
        assertEquals("hc", saved.name) // no device name - screens render the type name
        assertNull(saved.address)
        assertEquals(SyncMode.CURRENT, saved.syncMode)
    }

    @Test
    fun `health connect grant result saves immediately`() {
        hcGranted = false
        val vm = newViewModel()
        vm.selectOption("hc")
        testDispatcher.scheduler.advanceUntilIdle()
        vm.onHcPermissionsResult(setOf(HealthPermissions.READ_BLOOD_PRESSURE))
        assertTrue(vm.uiState.value is AddSourceUiState.Done)
        assertEquals(1, store.added.size)
    }

    @Test
    fun `health connect denial is explanatory and saves nothing`() {
        val vm = newViewModel()
        vm.selectOption("hc")
        testDispatcher.scheduler.advanceUntilIdle()
        vm.onHcPermissionsResult(emptySet())
        val state = vm.uiState.value as AddSourceUiState.Pairing
        assertTrue(state.permissionDenied)
        assertTrue(store.saved.isEmpty())
    }

    @Test
    fun `health connect permission screen unavailability is an error state`() {
        val vm = newViewModel()
        vm.onHcPermissionsUnavailable()
        assertEquals(
            R.string.add_source_hc_unavailable,
            (vm.uiState.value as AddSourceUiState.Error).messageRes
        )
    }

    // ---- reset ------------------------------------------------------ //

    @Test
    fun `reset returns to the type picker and stops the scan`() {
        val vm = newViewModel()
        val session = startedScan(vm)
        vm.reset()
        val state = vm.uiState.value as AddSourceUiState.TypePicker
        assertEquals(4, state.options.size)
        assertTrue(session.stopped)
    }

}

package com.zamotkin.extrahealthgarmin.ui.screens.devices

import android.app.Application
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.zamotkin.extrahealthgarmin.R
import com.zamotkin.extrahealthgarmin.devices.SourceRegistry
import com.zamotkin.extrahealthgarmin.devices.api.BleBpSourceType
import com.zamotkin.extrahealthgarmin.devices.api.BleSourceScanner
import com.zamotkin.extrahealthgarmin.devices.api.BpSourceType
import com.zamotkin.extrahealthgarmin.devices.api.DiscoveredBleDevice
import com.zamotkin.extrahealthgarmin.devices.api.SyncMode
import com.zamotkin.extrahealthgarmin.devices.api.UserAttribution
import com.zamotkin.extrahealthgarmin.devices.ble.BleServiceScanner
import com.zamotkin.extrahealthgarmin.devices.omron.OmronBleSourceType
import com.zamotkin.extrahealthgarmin.devices.store.ConnectedSource
import com.zamotkin.extrahealthgarmin.devices.store.ConnectedSourceStore
import com.zamotkin.extrahealthgarmin.health.HealthPermissions
import com.zamotkin.extrahealthgarmin.health.HealthSyncWiring
import com.zamotkin.extrahealthgarmin.omron.ble.missingPermissions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * One pickable source type in the add-source flow - the registry's
 * [BpSourceType] flattened to what the picker renders and the save step
 * needs. Carrying the capabilities here (instead of the screens
 * re-consulting the registry) is what keeps the flow capability-driven: the
 * standard 0x1810 driver (task xlg.9) registers its type and the picker,
 * the pair step and the saved defaults follow with no flow changes.
 *
 * @property supportsUserSlots whether readings carry a device user slot
 *   (the one-time pick of task xlg.4 renders for such types)
 * @property userSlots the pickable device user slots, 1-based; empty when
 *   [supportsUserSlots] is false (nothing to configure)
 * @property pairingKeyBytes the exact pairing-key length the type accepts,
 *   straight from its capabilities; null for keyless types (task xlg.14:
 *   the length rides with the capability, no vendor constant here)
 */
data class SourceTypeOption(
    val typeId: String,
    @StringRes val nameRes: Int,
    val isBle: Boolean,
    val requiresPairingKey: Boolean,
    val pairingKeyBytes: Int?,
    val supportsUserSlots: Boolean,
    val userSlots: List<Int>,
    val supportedSyncModes: Set<SyncMode>,
)

/**
 * UI state machine of the add-source flow (task
 * tefal2garmin-app-redesign-v3-xlg.3): type picker -> BLE scan -> per-type
 * pair step -> save. Same convention as ui.omron.OmronUiState: the state
 * carries NO user-visible text, only markers - every message is resolved
 * from an R.string by the screen.
 */
sealed interface AddSourceUiState {

    /** Step 1 - pick the source type; [options] mirrors the registry. */
    data class TypePicker(val options: List<SourceTypeOption>) : AddSourceUiState

    /**
     * BLE runtime permissions are missing; [permissions] is the set the
     * screen should request. [denied] marks a refused request - the
     * explanatory retry state (a denial never crashes or hangs silently).
     */
    data class BlePermissionsRequired(
        val option: SourceTypeOption,
        val permissions: List<String>,
        val denied: Boolean = false,
    ) : AddSourceUiState

    /**
     * Scanning for devices of the chosen BLE type. [devices] accumulates
     * live, deduped by address (the first advertisement wins).
     */
    data class Scanning(
        val option: SourceTypeOption,
        val devices: List<DiscoveredBleDevice>,
    ) : AddSourceUiState

    /** Scan stopped; the list stays selectable and a new scan can start. */
    data class Found(
        val option: SourceTypeOption,
        val devices: List<DiscoveredBleDevice>,
    ) : AddSourceUiState

    /**
     * Connect step, shaped by the type's capabilities: [device] is the scan
     * pick (null for singleton sources such as Health Connect); a
     * key-requiring BLE type renders the 16-byte key entry ([keyError]
     * marks a rejected key); a singleton renders the permission step
     * ([permissionGranted] after the pre-check, [permissionDenied] after a
     * refused grant). SLOTS-attribution types pick their device user once
     * here (task xlg.4): [selectedUserSlot] is the pick, [slotError] marks
     * a save attempted without one.
     */
    data class Pairing(
        val option: SourceTypeOption,
        val device: DiscoveredBleDevice? = null,
        val keyError: Boolean = false,
        val permissionGranted: Boolean = false,
        val permissionDenied: Boolean = false,
        val selectedUserSlot: Int? = null,
        val slotError: Boolean = false,
    ) : AddSourceUiState

    /** The ConnectedSource row is being persisted. */
    data object Saving : AddSourceUiState

    /** Saved; [source] is the persisted row. */
    data class Done(val source: ConnectedSource) : AddSourceUiState

    /**
     * Terminal failure. [messageRes] is the localized primary message;
     * [detail] is a raw non-localized diagnostic shown beneath it.
     */
    data class Error(@StringRes val messageRes: Int, val detail: String? = null) : AddSourceUiState
}

/**
 * Drives the add-source flow state machine (task xlg.3). Every environment
 * dependency is an injected seam, so the whole machine is unit-testable
 * with fakes: scanning runs through [BleSourceScanner] (never inside a
 * Composable), persistence through [ConnectedSourceStore], the pairing key
 * through [storePairingKey] (which owns HOW keys are stored - the flow
 * only validates the length), permissions through
 * [missingBlePermissions] and [hcBpPermissionGranted].
 *
 * Scan lifecycle: at most one live [BleSourceScanner.ScanSession]; late
 * callbacks after stop are dropped by session generation (a second guard
 * on top of the scanner's own), and [onCleared] - back navigation pops the
 * flow destination - stops the scan so no scanner leaks.
 *
 * Open solely so the unit test can subclass it and simulate [onCleared].
 */
open class AddSourceViewModel(
    private val registry: SourceRegistry,
    private val store: ConnectedSourceStore,
    private val scannerFor: (BleBpSourceType) -> BleSourceScanner?,
    private val missingBlePermissions: () -> List<String>,
    private val hcBpPermissionGranted: suspend () -> Boolean,
    private val storePairingKey: (typeId: String, key: ByteArray) -> String,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val _uiState = MutableLiveData<AddSourceUiState>()
    val uiState: LiveData<AddSourceUiState> = _uiState

    private val typesById: Map<String, BpSourceType> = registry.types.associateBy { it.typeId }

    /** The live scan's session; null while no scan is running. */
    private var scanSession: BleSourceScanner.ScanSession? = null

    /** Bumped on every stop/start - callbacks of a dead generation are dropped. */
    private var scanGeneration = 0

    init {
        _uiState.value = AddSourceUiState.TypePicker(registry.types.map(::asOption))
    }

    // ------------------------------------------------------------------ //
    //  Type picker                                                       //
    // ------------------------------------------------------------------ //

    /** Step 1: the user picked a source type from the picker. */
    fun onSelectOption(option: SourceTypeOption) {
        when (typesById[option.typeId]) {
            is BleBpSourceType -> startBleFlow(option)
            null -> Unit
            else -> startSingletonFlow(option)
        }
    }

    private fun startBleFlow(option: SourceTypeOption) {
        val missing = try {
            missingBlePermissions()
        } catch (e: Exception) {
            // The check is a pure grant lookup - treat a failure as
            // "granted" and let the scanner report an honest error instead.
            Log.w(TAG, "BLE permission check failed: ${e.message}")
            emptyList()
        }
        if (missing.isNotEmpty()) {
            _uiState.value = AddSourceUiState.BlePermissionsRequired(option, missing)
            return
        }
        startScan(option)
    }

    /**
     * Singleton path (Health Connect today): the connect step is the
     * permission grant, so the flow enters the pair step and pre-checks
     * whether the grant is already there (the screen then offers Save
     * instead of a redundant system dialog).
     */
    private fun startSingletonFlow(option: SourceTypeOption) {
        _uiState.value = AddSourceUiState.Pairing(option)
        viewModelScope.launch {
            val granted = try {
                hcBpPermissionGranted()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Health Connect status check failed: ${e.message}")
                false
            }
            val state = _uiState.value as? AddSourceUiState.Pairing ?: return@launch
            if (state.option.typeId == option.typeId) {
                _uiState.value = state.copy(permissionGranted = granted)
            }
        }
    }

    // ------------------------------------------------------------------ //
    //  Permissions                                                       //
    // ------------------------------------------------------------------ //

    /** The permission set the Health Connect grant step asks for. */
    fun hcPermissionsToRequest(): Set<String> = setOf(HealthPermissions.READ_BLOOD_PRESSURE)

    /** BLE runtime-permission result; the scan starts when everything landed. */
    fun onBlePermissionsResult(granted: Map<String, Boolean>) {
        val state = _uiState.value as? AddSourceUiState.BlePermissionsRequired ?: return
        val stillMissing = state.permissions.filter { granted[it] != true }
        if (stillMissing.isEmpty()) {
            startScan(state.option)
        } else {
            _uiState.value = state.copy(permissions = stillMissing, denied = true)
        }
    }

    /** Health Connect grant result: granted => save, refused => explain. */
    fun onHcPermissionsResult(granted: Set<String>) {
        val state = _uiState.value as? AddSourceUiState.Pairing ?: return
        if (state.option.isBle) return
        if (HealthPermissions.READ_BLOOD_PRESSURE in granted) {
            saveSource()
        } else {
            _uiState.value = state.copy(permissionDenied = true)
        }
    }

    /**
     * The Health Connect permission screen could not launch (provider not
     * installed or needs an update) - an honest error instead of a crash;
     * the Home tab keeps the Play Store fallback for that case.
     */
    fun onHcPermissionsUnavailable() {
        _uiState.value = AddSourceUiState.Error(R.string.add_source_hc_unavailable)
    }

    // ------------------------------------------------------------------ //
    //  Scan                                                              //
    // ------------------------------------------------------------------ //

    private fun startScan(option: SourceTypeOption) {
        val type = typesById[option.typeId] as? BleBpSourceType
        val scanner = try {
            type?.let(scannerFor)
        } catch (e: Exception) {
            Log.e(TAG, "Scanner lookup failed", e)
            null
        }
        if (type == null || scanner == null) {
            _uiState.value = AddSourceUiState.Error(
                messageRes = R.string.add_source_scan_error,
                detail = "no scanner for ${option.typeId}"
            )
            return
        }
        stopScanSession()
        val generation = ++scanGeneration
        _uiState.value = AddSourceUiState.Scanning(option, emptyList())
        scanSession = try {
            scanner.start(
                onDevice = { device ->
                    if (generation == scanGeneration) onDeviceFound(device)
                },
                onError = { reason ->
                    if (generation == scanGeneration) onScanError(reason)
                },
            )
        } catch (e: Exception) {
            // The seam promises no throws, but a broken implementation must
            // not kill the flow.
            onScanError(e.message ?: e.javaClass.simpleName)
            null
        }
    }

    private fun onDeviceFound(device: DiscoveredBleDevice) {
        val state = _uiState.value as? AddSourceUiState.Scanning ?: return
        if (state.devices.any { it.address == device.address }) return
        _uiState.value = state.copy(devices = state.devices + device)
    }

    private fun onScanError(reason: String) {
        stopScanSession()
        _uiState.value = AddSourceUiState.Error(
            messageRes = R.string.add_source_scan_error,
            detail = reason
        )
    }

    /** Stops the scan and keeps the found list selectable. */
    fun stopScan() {
        stopScanSession()
        val state = _uiState.value as? AddSourceUiState.Scanning ?: return
        _uiState.value = AddSourceUiState.Found(state.option, state.devices)
    }

    /** From Found: restarts the scan with a fresh live list. */
    fun scanAgain() {
        val state = _uiState.value as? AddSourceUiState.Found ?: return
        startScan(state.option)
    }

    private fun stopScanSession() {
        scanSession?.stop()
        scanSession = null
        scanGeneration++
    }

    // ------------------------------------------------------------------ //
    //  Pair + save                                                       //
    // ------------------------------------------------------------------ //

    /** The user picked a device from the (live or stopped) scan list. */
    fun selectDevice(device: DiscoveredBleDevice) {
        val option = when (val state = _uiState.value) {
            is AddSourceUiState.Scanning -> state.option
            is AddSourceUiState.Found -> state.option
            else -> return
        }
        stopScanSession()
        _uiState.value = AddSourceUiState.Pairing(option, device)
    }

    /**
     * The one-time device user pick (task xlg.4, epic R4): stores the choice
     * in the pairing state — it is written to [ConnectedSource.userSlot] on
     * save and shown read-only in the source detail afterwards. A no-op for
     * types without user attribution.
     */
    fun selectUserSlot(slot: Int) {
        val state = _uiState.value as? AddSourceUiState.Pairing ?: return
        if (!state.option.supportsUserSlots) return
        _uiState.value = state.copy(selectedUserSlot = slot, slotError = false)
    }

    /**
     * Submits the typed pairing key: a SLOTS type without its user pick or a
     * key whose length differs from the type's
     * [SourceTypeOption.pairingKeyBytes] capability is rejected BEFORE any
     * save; a valid one is stored through the seam and the source saved.
     */
    fun submitPairingKey(key: String) {
        val state = _uiState.value as? AddSourceUiState.Pairing ?: return
        if (!state.option.requiresPairingKey) return
        if (missingUserSlot(state)) return
        val keyBytes = key.toByteArray(Charsets.UTF_8)
        if (keyBytes.size != state.option.pairingKeyBytes) {
            _uiState.value = state.copy(keyError = true)
            return
        }
        val keyRef = try {
            storePairingKey(state.option.typeId, keyBytes)
        } catch (e: Exception) {
            Log.e(TAG, "Pairing key store failed", e)
            _uiState.value = AddSourceUiState.Error(
                messageRes = R.string.add_source_save_error,
                detail = e.message
            )
            return
        }
        persist(state.option, state.device, keyRef, state.selectedUserSlot)
    }

    /** Typing after a rejected key clears the error marker. */
    fun clearKeyError() {
        val state = _uiState.value as? AddSourceUiState.Pairing ?: return
        if (state.keyError) {
            _uiState.value = state.copy(keyError = false)
        }
    }

    /** Saves the source for types whose pair step needs no key. */
    fun saveSource() {
        val state = _uiState.value as? AddSourceUiState.Pairing ?: return
        if (state.option.requiresPairingKey) return
        if (missingUserSlot(state)) return
        persist(state.option, state.device, null, state.selectedUserSlot)
    }

    /**
     * True when a SLOTS-attribution type has no user pick yet — marks the
     * state so the screen can demand the pick. Changing the slot later is
     * remove-and-re-add by design (epic R4): nothing here can be edited
     * once saved.
     */
    private fun missingUserSlot(state: AddSourceUiState.Pairing): Boolean {
        if (!state.option.supportsUserSlots || state.selectedUserSlot != null) return false
        _uiState.value = state.copy(slotError = true)
        return true
    }

    private fun persist(
        option: SourceTypeOption,
        device: DiscoveredBleDevice?,
        pairingKeyRef: String?,
        userSlot: Int?,
    ) {
        _uiState.value = AddSourceUiState.Saving
        val existing = store.load()
        val source = ConnectedSource(
            id = UUID.randomUUID().toString(),
            sourceTypeId = option.typeId,
            // BLE sources are named after the device (fallback: the
            // address); singleton sources have no device name - the
            // list/detail screens render the type's display name for them.
            name = device?.let { it.name.ifEmpty { it.address } } ?: option.typeId,
            address = device?.address,
            pairingKeyRef = pairingKeyRef,
            // The one-time user pick; null for types without user
            // attribution (selectUserSlot is a no-op for them, so a NONE
            // source can never carry a slot here).
            userSlot = userSlot,
            syncMode = defaultSyncMode(option),
            createdAt = clock(),
        )
        val saved = store.save(existing + source)
        _uiState.value = if (saved) {
            AddSourceUiState.Done(source)
        } else {
            AddSourceUiState.Error(R.string.add_source_save_error)
        }
    }

    /**
     * HISTORY when the type supports it (the store default), else the one
     * mode it supports (Health Connect is Current-only).
     */
    private fun defaultSyncMode(option: SourceTypeOption): SyncMode =
        if (SyncMode.HISTORY in option.supportedSyncModes) {
            SyncMode.HISTORY
        } else {
            option.supportedSyncModes.first()
        }

    /** Terminal states offer a fresh start. */
    fun reset() {
        stopScanSession()
        _uiState.value = AddSourceUiState.TypePicker(registry.types.map(::asOption))
    }

    override fun onCleared() {
        // Back navigation pops the flow destination - the scan must not leak.
        stopScanSession()
        super.onCleared()
    }

    private fun asOption(type: BpSourceType): SourceTypeOption = SourceTypeOption(
        typeId = type.typeId,
        nameRes = type.displayNameRes,
        isBle = type is BleBpSourceType,
        requiresPairingKey = type.capabilities.requiresPairingKey,
        pairingKeyBytes = type.capabilities.pairingKeyBytes,
        supportsUserSlots = type.capabilities.userAttribution == UserAttribution.SLOTS,
        userSlots = type.capabilities.userSlots,
        supportedSyncModes = type.capabilities.supportedSyncModes,
    )

    private companion object {
        const val TAG = "AddSourceViewModel"
    }
}

/**
 * Manual wiring for [AddSourceViewModel] (no DI framework - same style as
 * ui.MainViewModelFactory): the seams get their production
 * implementations, all of them Android-bound and therefore kept out of the
 * ViewModel - the BLE scanner (devices/ble), the Omron pairing-key store,
 * the permission lookups and the Health Connect status facade.
 */
@Suppress("UNCHECKED_CAST")
class AddSourceViewModelFactory(
    private val application: Application,
) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AddSourceViewModel::class.java)) {
            return createInstance(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }

    companion object {

        fun createInstance(application: Application): AddSourceViewModel {
            val facade = HealthSyncWiring.facade(application)
            val scanner = BleServiceScanner(application)
            return AddSourceViewModel(
                registry = HealthSyncWiring.sourceRegistry(),
                store = HealthSyncWiring.connectedSourceStore(application),
                scannerFor = { _ -> scanner },
                missingBlePermissions = { missingPermissions(application) },
                hcBpPermissionGranted = { facade.status().bloodPressureGranted },
                storePairingKey = { _, key ->
                    // omron-ble is the only key-requiring type today; a
                    // future second one dispatches on the type id here.
                    OmronBleSourceType.storePairingKey(application, key)
                },
            )
        }
    }
}

package com.zamotkin.extrahealthgarmin.ui.screens.devices

import android.app.Application
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.zamotkin.extrahealthgarmin.devices.SourceRegistry
import com.zamotkin.extrahealthgarmin.devices.api.BpSourceType
import com.zamotkin.extrahealthgarmin.devices.api.SyncMode
import com.zamotkin.extrahealthgarmin.devices.store.ConnectedSource
import com.zamotkin.extrahealthgarmin.devices.store.ConnectedSourceStore
import com.zamotkin.extrahealthgarmin.health.HealthSyncWiring

/**
 * Backs the connected-sources list and detail screens (task
 * tefal2garmin-app-redesign-v3-xlg.3): loads [ConnectedSourceStore] into
 * LiveData and removes a confirmed source. Removal touches ONLY the
 * connection - readings already stored in the Room database are kept (the
 * spec pins this: family history stays, only the link goes). Task xlg.5
 * added the per-source sync-mode switch ([setSyncMode]) the detail
 * screen's History/Current toggle drives.
 */
class SourcesViewModel(
    private val registry: SourceRegistry,
    private val store: ConnectedSourceStore,
) : ViewModel() {

    private val _sources = MutableLiveData<List<ConnectedSource>>()
    val sources: LiveData<List<ConnectedSource>> = _sources

    init {
        refresh()
    }

    /** Re-reads the store (first entry and every return to the screen). */
    fun refresh() {
        _sources.value = store.load()
    }

    /** The registry type behind a stored source, or null for an unknown id. */
    fun typeOf(sourceTypeId: String): BpSourceType? = registry.type(sourceTypeId)

    /**
     * Removes one connected source after the user confirmed. True when the
     * store was updated; false when the write failed or the id is unknown -
     * the store's never-throws contract holds here too.
     */
    fun remove(id: String): Boolean {
        val current = store.load()
        val remaining = current.filterNot { it.id == id }
        if (remaining.size == current.size) return false // unknown id - nothing to do
        val saved = store.save(remaining)
        if (saved) _sources.value = remaining
        return saved
    }

    /**
     * Switches [sourceId]'s sync mode (task tefal2garmin-app-redesign-v3-
     * xlg.5, epic R5): the History/Current toggle in the source detail.
     * The mode must be one the source TYPE supports - a Current-only
     * source (Health Connect) never takes History - and the write goes
     * through [ConnectedSourceStore.update] so it cannot lose a concurrent
     * anchor write of a background read. True when the store holds the new
     * mode (a no-op counts); false when the id is unknown, the type does
     * not support the mode, or the write failed - the old mode stays.
     */
    fun setSyncMode(sourceId: String, syncMode: SyncMode): Boolean {
        val source = store.load().firstOrNull { it.id == sourceId } ?: return false
        val supported = registry.type(source.sourceTypeId)
            ?.capabilities
            ?.supportedSyncModes
            // Unknown type: nothing to validate the request against, and
            // the detail screen renders no toggle for it either.
            ?: return false
        if (syncMode !in supported) return false
        if (source.syncMode == syncMode) return true // already stored - no write
        val saved = store.update(sourceId) { it.copy(syncMode = syncMode) }
        if (saved) refresh()
        return saved
    }
}

/**
 * Manual wiring for [SourcesViewModel] (no DI framework - same style as
 * ui.MainViewModelFactory): the real registry and the app's shared
 * connected-source store.
 */
@Suppress("UNCHECKED_CAST")
class SourcesViewModelFactory(
    private val application: Application,
) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SourcesViewModel::class.java)) {
            return SourcesViewModel(
                registry = HealthSyncWiring.sourceRegistry(),
                store = HealthSyncWiring.connectedSourceStore(application),
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

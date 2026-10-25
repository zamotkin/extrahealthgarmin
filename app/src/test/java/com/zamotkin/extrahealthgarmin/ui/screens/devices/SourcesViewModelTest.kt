package com.zamotkin.extrahealthgarmin.ui.screens.devices

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.zamotkin.extrahealthgarmin.devices.SourceRegistry
import com.zamotkin.extrahealthgarmin.devices.api.SyncMode
import com.zamotkin.extrahealthgarmin.devices.healthconnect.HealthConnectBpSourceType
import com.zamotkin.extrahealthgarmin.devices.omron.OmronBleSourceType
import com.zamotkin.extrahealthgarmin.devices.store.ConnectedSource
import com.zamotkin.extrahealthgarmin.devices.store.ConnectedSourceStore
import com.zamotkin.extrahealthgarmin.devices.store.FileConnectedSourceStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Pins [SourcesViewModel] (task tefal2garmin-app-redesign-v3-xlg.3): the
 * store load/refresh mapping and the removal logic - only the confirmed
 * connection is removed (readings stay in Room by construction: nothing
 * here touches the database), an unknown id saves nothing, and a failed
 * write keeps the old list. Task xlg.16 (the xlg.5 review) additionally
 * pins that remove is the store's ROW-ATOMIC operation, not a whole-list
 * load-modify-save that could revert a concurrent row-level write. The
 * registry is the real one (plain-JVM objects, same as
 * SourceRegistryTest); the store is a hand fake.
 */
class SourcesViewModelTest {

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private class FakeStore(var sources: List<ConnectedSource>) : ConnectedSourceStore {
        val saved = mutableListOf<List<ConnectedSource>>()
        val removed = mutableListOf<String>()
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
         * atomic list change that never routes through the whole-list
         * [save] seam (a single-threaded fake, so plain state logic is
         * equally atomic) - which is what lets the tests see a ViewModel
         * that writes whole stale lists.
         */
        override fun remove(id: String): Boolean {
            if (sources.none { it.id == id }) return false
            if (failSave) return false
            removed += id
            sources = sources.filterNot { it.id == id }
            return true
        }
    }

    private val omronSource = ConnectedSource(
        id = "id-1",
        sourceTypeId = "omron-ble",
        name = "OMRON M7",
        address = "AA:BB:CC:DD:EE:FF",
        createdAt = 1L
    )
    private val hcSource = ConnectedSource(
        id = "id-2",
        sourceTypeId = "health-connect",
        name = "health-connect",
        createdAt = 2L
    )
    private val store = FakeStore(listOf(omronSource, hcSource))

    private fun newViewModel(): SourcesViewModel = SourcesViewModel(
        registry = SourceRegistry(listOf(OmronBleSourceType, HealthConnectBpSourceType)),
        store = store
    )

    @Test
    fun `load exposes the connected sources`() {
        val vm = newViewModel()
        assertEquals(listOf(omronSource, hcSource), vm.sources.value)
    }

    @Test
    fun `refresh re-reads the store`() {
        val vm = newViewModel()
        store.sources = listOf(omronSource)
        vm.refresh()
        assertEquals(listOf(omronSource), vm.sources.value)
    }

    @Test
    fun `remove deletes only the confirmed connection`() {
        val vm = newViewModel()
        assertTrue(vm.remove("id-1"))
        // Task xlg.16: the removal is the store's row-atomic operation -
        // one delegated call, never a whole-list save the ViewModel built
        // from a stale load (that save could revert a background pass's
        // row-level write).
        assertEquals(listOf("id-1"), store.removed)
        assertTrue(store.saved.isEmpty())
        assertEquals(listOf(hcSource), store.sources)
        assertEquals(listOf(hcSource), vm.sources.value)
    }

    @Test
    fun `remove keeps a row-level update that landed after the screen loaded its list`() {
        // A background read pass (anchor/fallback bookkeeping) lands while
        // the screen shows the list it loaded on entry - the row-atomic
        // removal must not revert it.
        val vm = newViewModel()
        store.sources = listOf(
            omronSource,
            hcSource.copy(lastReadAnchor = 42L, fullReadFallbackPending = true)
        )

        assertTrue(vm.remove("id-1"))

        val survivor = store.sources.single()
        assertEquals("id-2", survivor.id)
        assertEquals(42L, survivor.lastReadAnchor)
        assertTrue(survivor.fullReadFallbackPending)
    }

    @Test
    fun `remove of an unknown id saves nothing`() {
        val vm = newViewModel()
        assertFalse(vm.remove("no-such-id"))
        assertTrue(store.removed.isEmpty())
        assertTrue(store.saved.isEmpty())
        assertEquals(listOf(omronSource, hcSource), vm.sources.value)
    }

    @Test
    fun `remove reports a failed write and keeps the list`() {
        store.failSave = true
        val vm = newViewModel()
        assertFalse(vm.remove("id-2"))
        assertTrue(store.removed.isEmpty())
        assertTrue(store.saved.isEmpty())
        assertEquals(listOf(omronSource, hcSource), vm.sources.value)
    }

    @Test
    fun `typeOf resolves the registry type behind a stored source`() {
        val vm = newViewModel()
        assertEquals(OmronBleSourceType, vm.typeOf("omron-ble"))
        assertEquals(HealthConnectBpSourceType, vm.typeOf("health-connect"))
        assertNull(vm.typeOf("no-such-type"))
    }

    // ------------------------------------------------------------------ //
    //  Sync-mode toggle (task tefal2garmin-app-redesign-v3-xlg.5)        //
    // ------------------------------------------------------------------ //

    @Test
    fun `setSyncMode persists a current-history-current switch`() {
        val vm = newViewModel()
        store.sources = listOf(omronSource.copy(syncMode = SyncMode.CURRENT))

        assertTrue(vm.setSyncMode("id-1", SyncMode.HISTORY))
        assertTrue(vm.setSyncMode("id-1", SyncMode.CURRENT))

        assertEquals(SyncMode.CURRENT, store.saved.last().single().syncMode)
        assertEquals(SyncMode.CURRENT, vm.sources.value?.single()?.syncMode)
    }

    @Test
    fun `setSyncMode rejects a mode the source type does not support`() {
        val vm = newViewModel()
        // Health Connect is Current-only; its History is not selectable.
        store.sources = listOf(hcSource.copy(syncMode = SyncMode.CURRENT))

        assertFalse(vm.setSyncMode("id-2", SyncMode.HISTORY))

        assertTrue(store.saved.isEmpty())
    }

    @Test
    fun `setSyncMode of an unknown id saves nothing`() {
        val vm = newViewModel()

        assertFalse(vm.setSyncMode("no-such-id", SyncMode.CURRENT))

        assertTrue(store.saved.isEmpty())
    }

    @Test
    fun `setSyncMode to the stored mode is a no-op`() {
        val vm = newViewModel()

        assertTrue(vm.setSyncMode("id-1", SyncMode.HISTORY))

        assertTrue(store.saved.isEmpty())
    }

    @Test
    fun `setSyncMode reports a failed write and keeps the list`() {
        store.failSave = true
        val vm = newViewModel()

        assertFalse(vm.setSyncMode("id-1", SyncMode.CURRENT))

        assertTrue(store.saved.isEmpty())
        assertEquals(listOf(omronSource, hcSource), vm.sources.value)
    }
}

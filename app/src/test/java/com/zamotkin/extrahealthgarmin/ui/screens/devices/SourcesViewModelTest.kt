package com.zamotkin.extrahealthgarmin.ui.screens.devices

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.zamotkin.extrahealthgarmin.devices.SourceRegistry
import com.zamotkin.extrahealthgarmin.devices.healthconnect.HealthConnectBpSourceType
import com.zamotkin.extrahealthgarmin.devices.omron.OmronBleSourceType
import com.zamotkin.extrahealthgarmin.devices.store.ConnectedSource
import com.zamotkin.extrahealthgarmin.devices.store.ConnectedSourceStore
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
 * write keeps the old list. The registry is the real one (plain-JVM
 * objects, same as SourceRegistryTest); the store is a hand fake.
 */
class SourcesViewModelTest {

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private class FakeStore(var sources: List<ConnectedSource>) : ConnectedSourceStore {
        val saved = mutableListOf<List<ConnectedSource>>()
        var failSave = false

        override fun load(): List<ConnectedSource> = sources

        override fun save(sources: List<ConnectedSource>): Boolean {
            if (failSave) return false
            saved += sources
            this.sources = sources
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
        assertEquals(1, store.saved.size)
        assertEquals(listOf(hcSource), store.saved[0])
        assertEquals(listOf(hcSource), vm.sources.value)
    }

    @Test
    fun `remove of an unknown id saves nothing`() {
        val vm = newViewModel()
        assertFalse(vm.remove("no-such-id"))
        assertTrue(store.saved.isEmpty())
        assertEquals(listOf(omronSource, hcSource), vm.sources.value)
    }

    @Test
    fun `remove reports a failed write and keeps the list`() {
        store.failSave = true
        val vm = newViewModel()
        assertFalse(vm.remove("id-2"))
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
}

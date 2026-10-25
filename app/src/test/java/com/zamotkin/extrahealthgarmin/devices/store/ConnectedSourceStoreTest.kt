package com.zamotkin.extrahealthgarmin.devices.store

import com.zamotkin.extrahealthgarmin.devices.api.SyncMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Persistence tests for [FileConnectedSourceStore] (task
 * tefal2garmin-app-redesign-v3-xlg.2) - the [com.zamotkin.extrahealthgarmin.health.SyncStateStoreTest]
 * pattern: roundtrip, corrupted JSON, atomic save. File-based: plain JVM
 * with a temp folder; android.util.Log calls run against stubbed framework
 * methods (see app/build.gradle testOptions) and org.json comes from the
 * JVM test dependency.
 */
class ConnectedSourceStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newStore(): FileConnectedSourceStore =
        FileConnectedSourceStore(File(tmp.root, "bp-sources.json"))

    private fun omronSource(syncMode: SyncMode = SyncMode.CURRENT) = ConnectedSource(
        id = "uuid-1",
        sourceTypeId = "omron-ble",
        name = "M7",
        address = "AA:BB:CC:DD:EE:FF",
        pairingKeyRef = "key-ref-1",
        userSlot = 2,
        syncMode = syncMode,
        lastReadAnchor = 1_760_000_000L,
        createdAt = 1_760_000_001_000L
    )

    private fun healthConnectSource() = ConnectedSource(
        id = "uuid-2",
        sourceTypeId = "health-connect",
        name = "Health Connect",
        createdAt = 1_760_000_002_000L
    )

    @Test
    fun `roundtrip persists every field of a BLE source`() {
        newStore().save(listOf(omronSource()))

        assertEquals(listOf(omronSource()), newStore().load())
    }

    @Test
    fun `roundtrip persists a Health Connect source with null optional fields`() {
        newStore().save(listOf(healthConnectSource()))

        val loaded = newStore().load().single()
        assertEquals(healthConnectSource(), loaded)
        assertEquals(null, loaded.address)
        assertEquals(null, loaded.pairingKeyRef)
        assertEquals(null, loaded.userSlot)
        assertEquals(null, loaded.lastReadAnchor)
        assertEquals(SyncMode.HISTORY, loaded.syncMode)
    }

    @Test
    fun `missing file loads as an empty list`() {
        assertEquals(emptyList<ConnectedSource>(), newStore().load())
    }

    @Test
    fun `corrupted json loads as an empty list without crashing`() {
        val file = File(tmp.root, "bp-sources.json")
        file.writeText("{{{not json at all")

        assertEquals(emptyList<ConnectedSource>(), newStore().load())
    }

    @Test
    fun `a row missing a required field is treated as corrupt`() {
        val file = File(tmp.root, "bp-sources.json")
        file.writeText(
            """{"sources":[{"sourceTypeId":"omron-ble","name":"M7","createdAt":1}]}"""
        )

        assertEquals(emptyList<ConnectedSource>(), newStore().load())
    }

    @Test
    fun `save replaces the previous list without stale rows`() {
        val store = newStore()
        store.save(listOf(omronSource(), healthConnectSource()))
        store.save(listOf(healthConnectSource()))

        val loaded = newStore().load()
        assertEquals(listOf(healthConnectSource()), loaded)
    }

    @Test
    fun `failed save returns false and leaves the old file intact`() {
        val store = newStore()
        store.save(listOf(omronSource()))
        // Block the temp path with a directory: the atomic write cannot even
        // start, so the save must fail without touching the previous file.
        val blockedTemp = File(tmp.root, "bp-sources.json.tmp")
        assertTrue(blockedTemp.mkdirs())

        val saved = store.save(listOf(healthConnectSource()))

        assertFalse(saved)
        assertEquals(listOf(omronSource()), newStore().load())
    }

    @Test
    fun `atomic save leaves no temp file behind`() {
        val store = newStore()
        store.save(listOf(omronSource()))

        val leftovers = tmp.root.list().orEmpty().filter { it.endsWith(".tmp") }
        assertTrue("temp file must be renamed away: $leftovers", leftovers.isEmpty())
        assertTrue(File(tmp.root, "bp-sources.json").readText().contains("uuid-1"))
    }

    @Test
    fun `an unknown stored sync mode falls back to History`() {
        val file = File(tmp.root, "bp-sources.json")
        file.writeText(
            """{"sources":[{"id":"u1","sourceTypeId":"omron-ble","name":"M7",""" +
                """"syncMode":"FULL_HISTORY","createdAt":123}]}"""
        )

        assertEquals(SyncMode.HISTORY, newStore().load().single().syncMode)
    }

    // ------------------------------------------------------------------ //
    //  Sync-mode state (task tefal2garmin-app-redesign-v3-xlg.5)         //
    // ------------------------------------------------------------------ //

    @Test
    fun `roundtrip persists the full-read fallback flag`() {
        val pending = omronSource().copy(fullReadFallbackPending = true)

        newStore().save(listOf(pending))

        assertEquals(listOf(pending), newStore().load())
    }

    @Test
    fun `a row saved before the fallback flag existed loads it as false`() {
        val file = File(tmp.root, "bp-sources.json")
        file.writeText(
            """{"sources":[{"id":"u1","sourceTypeId":"omron-ble","name":"M7",""" +
                """"syncMode":"CURRENT","lastReadAnchor":100,"createdAt":123}]}"""
        )

        val loaded = newStore().load().single()
        assertFalse(loaded.fullReadFallbackPending)
        assertEquals(SyncMode.CURRENT, loaded.syncMode)
        assertEquals(100L, loaded.lastReadAnchor)
    }

    @Test
    fun `update changes one row and leaves the rest untouched`() {
        val store = newStore()
        store.save(listOf(omronSource(), healthConnectSource()))

        val updated = store.update("uuid-1") { it.copy(syncMode = SyncMode.HISTORY) }

        assertTrue(updated)
        assertEquals(
            listOf(omronSource().copy(syncMode = SyncMode.HISTORY), healthConnectSource()),
            store.load()
        )
    }

    @Test
    fun `update of an unknown id saves nothing`() {
        val store = newStore()
        store.save(listOf(omronSource()))

        assertFalse(store.update("no-such-id") { it.copy(syncMode = SyncMode.HISTORY) })

        assertEquals(listOf(omronSource()), store.load())
    }

    @Test
    fun `an update that changes nothing returns true without writing`() {
        val store = newStore()
        store.save(listOf(omronSource()))
        val before = File(tmp.root, "bp-sources.json").readText()

        assertTrue(store.update("uuid-1") { it })

        assertEquals(before, File(tmp.root, "bp-sources.json").readText())
    }

    @Test
    fun `a failed update returns false and leaves the old file intact`() {
        val store = newStore()
        store.save(listOf(omronSource()))
        // Same blocked-temp trick as the save test: the write cannot start.
        val blockedTemp = File(tmp.root, "bp-sources.json.tmp")
        assertTrue(blockedTemp.mkdirs())

        assertFalse(store.update("uuid-1") { it.copy(syncMode = SyncMode.HISTORY) })

        assertEquals(listOf(omronSource()), newStore().load())
    }
}

package com.zamotkin.extrahealthgarmin.devices.store

import com.zamotkin.extrahealthgarmin.devices.api.SyncMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

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

    // ------------------------------------------------------------------ //
    //  Row-atomic remove/add (task xlg.16, xlg.5 review)                 //
    // ------------------------------------------------------------------ //

    /**
     * load/save-only fake inheriting the interface defaults - the shape the
     * unit-test fakes build on, so the default contract (a plain
     * load-modify-save over [load]/[save]) stays pinned too.
     */
    private class DefaultsFakeStore(var sources: List<ConnectedSource>) : ConnectedSourceStore {
        override fun load(): List<ConnectedSource> = sources

        override fun save(sources: List<ConnectedSource>): Boolean {
            this.sources = sources
            return true
        }
    }

    @Test
    fun `the interface default remove filters the row out through load and save`() {
        val store = DefaultsFakeStore(listOf(omronSource(), healthConnectSource()))

        assertTrue(store.remove("uuid-1"))
        assertEquals(listOf(healthConnectSource()), store.load())
        assertFalse(store.remove("no-such-id"))
        assertEquals(listOf(healthConnectSource()), store.load())
    }

    @Test
    fun `the interface default add appends the row through load and save`() {
        val store = DefaultsFakeStore(listOf(omronSource()))

        assertTrue(store.add(healthConnectSource()))

        assertEquals(listOf(omronSource(), healthConnectSource()), store.load())
    }

    @Test
    fun `remove deletes one row and leaves the rest untouched`() {
        val store = newStore()
        store.save(listOf(omronSource(), healthConnectSource()))
        store.update("uuid-2") { it.copy(syncMode = SyncMode.CURRENT, lastReadAnchor = 42L) }

        val removed = store.remove("uuid-1")

        assertTrue(removed)
        assertEquals(
            listOf(healthConnectSource().copy(syncMode = SyncMode.CURRENT, lastReadAnchor = 42L)),
            store.load()
        )
    }

    @Test
    fun `remove of an unknown id returns false and writes nothing`() {
        val store = newStore()
        store.save(listOf(omronSource()))
        val before = File(tmp.root, "bp-sources.json").readText()

        assertFalse(store.remove("no-such-id"))

        assertEquals(before, File(tmp.root, "bp-sources.json").readText())
    }

    @Test
    fun `a failed remove returns false and leaves the old file intact`() {
        val store = newStore()
        store.save(listOf(omronSource(), healthConnectSource()))
        // Same blocked-temp trick as the save test: the write cannot start.
        val blockedTemp = File(tmp.root, "bp-sources.json.tmp")
        assertTrue(blockedTemp.mkdirs())

        assertFalse(store.remove("uuid-1"))

        assertEquals(listOf(omronSource(), healthConnectSource()), newStore().load())
    }

    @Test
    fun `add appends the row and keeps the existing rows untouched`() {
        val store = newStore()
        store.save(listOf(omronSource()))
        store.update("uuid-1") {
            it.copy(syncMode = SyncMode.CURRENT, fullReadFallbackPending = true)
        }

        val added = store.add(healthConnectSource())

        assertTrue(added)
        assertEquals(
            listOf(
                omronSource().copy(syncMode = SyncMode.CURRENT, fullReadFallbackPending = true),
                healthConnectSource()
            ),
            store.load()
        )
    }

    @Test
    fun `a failed add returns false and leaves the old file intact`() {
        val store = newStore()
        store.save(listOf(omronSource()))
        val blockedTemp = File(tmp.root, "bp-sources.json.tmp")
        assertTrue(blockedTemp.mkdirs())

        assertFalse(store.add(healthConnectSource()))

        assertEquals(listOf(omronSource()), newStore().load())
    }

    @Test
    fun `a row update landing while remove runs survives it`() {
        // The xlg.5 review's finding 3 shape: remove must serialize with a
        // row-level update on the store's lock. The update's lambda runs
        // INSIDE the lock and blocks there holding it; the remove runs on
        // another thread, and whichever order the scheduler picks, the
        // update's change must survive the remove - a whole-list remove
        // that had loaded its rows before the update completed would
        // silently revert it.
        val store = newStore()
        store.save(listOf(omronSource(), healthConnectSource()))
        val updateStarted = CountDownLatch(1)
        val letUpdateFinish = CountDownLatch(1)
        val updater = thread {
            store.update("uuid-2") {
                updateStarted.countDown()
                letUpdateFinish.await()
                it.copy(syncMode = SyncMode.CURRENT)
            }
        }
        assertTrue(updateStarted.await(5, TimeUnit.SECONDS)) // the lock is held inside the update
        val remover = thread { store.remove("uuid-1") }
        letUpdateFinish.countDown()
        updater.join(TimeUnit.SECONDS.toMillis(5))
        remover.join(TimeUnit.SECONDS.toMillis(5))
        assertFalse(remover.isAlive)

        assertEquals(
            listOf(healthConnectSource().copy(syncMode = SyncMode.CURRENT)),
            store.load()
        )
    }

    @Test
    fun `a row update landing while add runs survives it`() {
        // The add-source counterpart of the remove test: the append must
        // not write its list from rows loaded before a concurrent row-level
        // update completed.
        val store = newStore()
        store.save(listOf(omronSource()))
        val updateStarted = CountDownLatch(1)
        val letUpdateFinish = CountDownLatch(1)
        val updater = thread {
            store.update("uuid-1") {
                updateStarted.countDown()
                letUpdateFinish.await()
                it.copy(lastReadAnchor = 42L, fullReadFallbackPending = true)
            }
        }
        assertTrue(updateStarted.await(5, TimeUnit.SECONDS))
        val adder = thread { store.add(healthConnectSource()) }
        letUpdateFinish.countDown()
        updater.join(TimeUnit.SECONDS.toMillis(5))
        adder.join(TimeUnit.SECONDS.toMillis(5))
        assertFalse(adder.isAlive)

        assertEquals(
            listOf(
                omronSource().copy(lastReadAnchor = 42L, fullReadFallbackPending = true),
                healthConnectSource()
            ),
            store.load()
        )
    }
}

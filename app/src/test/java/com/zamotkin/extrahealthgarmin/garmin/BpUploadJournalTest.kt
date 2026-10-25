package com.zamotkin.extrahealthgarmin.garmin

import com.zamotkin.extrahealthgarmin.omron.BloodPressureReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Tests for [BpUploadJournal] against an in-memory [JournalPersistence]
 * fake — same fixture style as [UploadJournalTest]. The medical-data
 * isolation rule gets its own scenarios: the dedup key is the COMPOSITE
 * (timestampEpochSec, userSlot), so the same measurement time for USER1 and
 * USER2 is two DIFFERENT records and must never dedup each other. Plain
 * JUnit only (Robolectric cannot run on this repo's JDK 26 toolchain;
 * android.util.Log is a stubbed no-op).
 *
 * Persistence-failure behavior (save throws / load throws at construction)
 * pinned by task tefal2garmin-ji0.
 */
class BpUploadJournalTest {

    private class InMemoryPersistence : JournalPersistence {
        var stored: String? = null
        var savedCount = 0

        override fun load(): String? = stored

        override fun save(json: String) {
            stored = json
            savedCount++
        }
    }

    private fun reading(epochSecondsUtc: Long, userSlot: Int = 1): BloodPressureReading =
        BloodPressureReading(
            timestampEpochSec = epochSecondsUtc,
            systolic = 120,
            diastolic = 80,
            pulse = 62,
            mov = false,
            ihb = false,
            userSlot = userSlot
        )

    // ------------------------------------------------------------------ //
    //  Spec scenarios (names match the task's TDD list 1:1)              //
    // ------------------------------------------------------------------ //

    @Test
    fun `empty_journal_all_records_new`() {
        val journal = BpUploadJournal(InMemoryPersistence())

        val newRecords = journal.filterNew(listOf(reading(1L), reading(2L)))

        assertEquals(listOf(1L, 2L), newRecords.map { it.timestampEpochSec })
        assertEquals(0, journal.uploadedCount())
    }

    @Test
    fun `marked_records_filtered_out`() {
        val journal = BpUploadJournal(InMemoryPersistence())

        journal.markUploaded(listOf(reading(1L), reading(2L, userSlot = 2)))
        val newRecords = journal.filterNew(
            listOf(reading(1L), reading(2L, userSlot = 2), reading(3L))
        )

        assertEquals(listOf(3L), newRecords.map { it.timestampEpochSec })
        assertEquals(2, journal.uploadedCount())
    }

    @Test
    fun `intra_batch_duplicates_same_ts_and_slot_deduped`() {
        val journal = BpUploadJournal(InMemoryPersistence())

        val newRecords = journal.filterNew(
            listOf(
                reading(5L),
                reading(5L), // the device re-serving the same measurement
                reading(6L)
            )
        )

        assertEquals(
            "same ts+slot inside ONE batch must upload once",
            listOf(5L, 6L),
            newRecords.map { it.timestampEpochSec }
        )
    }

    @Test
    fun `same_ts_different_slot_not_deduped`() {
        val journal = BpUploadJournal(InMemoryPersistence())

        val newRecords = journal.filterNew(
            listOf(reading(7L, userSlot = 1), reading(7L, userSlot = 2))
        )

        assertEquals(
            "medical-data isolation: USER1 and USER2 readings at the same " +
                "measurement time are two different records",
            listOf(Pair(7L, 1), Pair(7L, 2)),
            newRecords.map { it.timestampEpochSec to it.userSlot }
        )
    }

    @Test
    fun `marked_user1_does_not_suppress_user2_at_same_ts`() {
        val journal = BpUploadJournal(InMemoryPersistence())

        journal.markUploaded(listOf(reading(8L, userSlot = 1)))
        val newRecords = journal.filterNew(listOf(reading(8L, userSlot = 1), reading(8L, userSlot = 2)))

        assertEquals(
            "a journaled USER1 reading must never suppress USER2's upload",
            listOf(2),
            newRecords.map { it.userSlot }
        )
    }

    @Test
    fun `persistence_roundtrip_via_seam`() {
        val persistence = InMemoryPersistence()
        val first = BpUploadJournal(persistence)
        first.markUploaded(listOf(reading(11L), reading(22L, userSlot = 2)))

        // A fresh journal over the same persistence must see the marked set.
        val second = BpUploadJournal(persistence)
        assertEquals(2, second.uploadedCount())
        val newRecords = second.filterNew(listOf(reading(11L), reading(33L)))
        assertEquals(listOf(33L), newRecords.map { it.timestampEpochSec })

        // And the persisted form is a JSON array of composite key strings.
        val stored = persistence.stored!!
        assertTrue("journal must persist a JSON array: $stored", stored.startsWith("["))
        assertTrue(stored.contains("\"11:1\""))
        assertTrue(stored.contains("\"22:2\""))
    }

    @Test
    fun `corrupted_json_starts_fresh`() {
        val persistence = InMemoryPersistence()
        persistence.stored = "{{{not json at all"

        val journal = BpUploadJournal(persistence)

        assertEquals("corrupted journal must start empty, never crash", 0, journal.uploadedCount())
        val newRecords = journal.filterNew(listOf(reading(9L)))
        assertEquals(listOf(9L), newRecords.map { it.timestampEpochSec })
    }

    // ------------------------------------------------------------------ //
    //  Persistence failures (task tefal2garmin-ji0)                       //
    // ------------------------------------------------------------------ //

    /**
     * A save that throws must never escape [markUploaded] nor corrupt the
     * in-memory dedup set — worst case the journal forgets its history on
     * restart (Garmin answers duplicate-detection), it must not crash the
     * sync pass.
     */
    @Test
    fun `save_failure_keeps_in_memory_state_and_never_throws`() {
        val persistence = object : JournalPersistence {
            override fun load(): String? = null

            override fun save(json: String) {
                throw IOException("disk full")
            }
        }
        val journal = BpUploadJournal(persistence)

        journal.markUploaded(listOf(reading(1L), reading(2L, userSlot = 2)))

        assertEquals("the in-memory set grows even when persistence fails", 2, journal.uploadedCount())
        assertEquals(
            "filterNew still dedups from the in-memory set",
            listOf(3L),
            journal.filterNew(listOf(reading(1L), reading(2L, userSlot = 2), reading(3L)))
                .map { it.timestampEpochSec }
        )
    }

    /**
     * A load that throws at construction must degrade to a fresh empty
     * journal, mirroring the corrupted-JSON behavior — construction never
     * crashes.
     */
    @Test
    fun `load_failure_at_construction_degrades_to_fresh_journal`() {
        val persistence = object : JournalPersistence {
            override fun load(): String? {
                throw IOException("journal unreadable")
            }

            override fun save(json: String) {}
        }

        val journal = BpUploadJournal(persistence)

        assertEquals("an unreadable journal starts empty, never crashes", 0, journal.uploadedCount())
        assertEquals(
            listOf(1L),
            journal.filterNew(listOf(reading(1L))).map { it.timestampEpochSec }
        )
    }

    // ------------------------------------------------------------------ //
    //  Cross-instance merge-on-save (task tefal2garmin-n3q)               //
    //  ------------------------------------------------------------------ //

    /**
     * Task tefal2garmin-n3q: a SECOND journal instance over the same
     * persistence (e.g. a second GarminRepository built from context) holds a
     * stale in-memory key set — its save() must not overwrite the whole
     * bp_upload_journal.json and erase keys the first instance persisted
     * after this one was constructed. The save path MERGES: reload the
     * persisted set, union it with the in-memory additions, write the union.
     *
     * Instance A (constructed AFTER the stale one, so its save is already on
     * disk) marks two records; the stale instance B then marks a DIFFERENT
     * record and saves; a third instance must see BOTH key sets.
     */
    @Test
    fun `stale_instance_save_merges_persisted_keys_instead_of_erasing`() {
        val persistence = InMemoryPersistence()
        val stale = BpUploadJournal(persistence)
        val first = BpUploadJournal(persistence)

        first.markUploaded(listOf(reading(100L), reading(200L, userSlot = 2)))
        stale.markUploaded(listOf(reading(300L)))

        val reloaded = BpUploadJournal(persistence)
        assertEquals(
            "merge-on-save: the stale instance's save must not erase the first instance's keys",
            3,
            reloaded.uploadedCount()
        )
        assertTrue(
            "all three keys must survive the stale save: ${persistence.stored}",
            reloaded.filterNew(
                listOf(reading(100L), reading(200L, userSlot = 2), reading(300L))
            ).isEmpty()
        )
    }

    /**
     * Task tefal2garmin-n3q: the merge happens on EVERY save, not only the
     * first — two long-lived instances alternating saves never lose each
     * other's earlier keys (each save re-reads the persisted set before
     * writing the union).
     */
    @Test
    fun `every_save_merges_alternating_instances_lose_no_keys`() {
        val persistence = InMemoryPersistence()
        val a = BpUploadJournal(persistence)
        val b = BpUploadJournal(persistence)

        a.markUploaded(listOf(reading(1L)))
        b.markUploaded(listOf(reading(2L)))
        a.markUploaded(listOf(reading(3L)))
        b.markUploaded(listOf(reading(4L)))

        val reloaded = BpUploadJournal(persistence)
        assertEquals(
            "all four keys must survive four alternating saves: ${persistence.stored}",
            4,
            reloaded.uploadedCount()
        )
        assertEquals(
            "nothing may re-upload after the merge-on-save sequence",
            emptyList<BloodPressureReading>(),
            reloaded.filterNew(listOf(reading(1L), reading(2L), reading(3L), reading(4L)))
        )
    }
}

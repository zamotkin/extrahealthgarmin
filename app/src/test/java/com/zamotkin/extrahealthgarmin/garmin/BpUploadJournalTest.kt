package com.zamotkin.extrahealthgarmin.garmin

import com.zamotkin.extrahealthgarmin.omron.BloodPressureReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [BpUploadJournal] against an in-memory [JournalPersistence]
 * fake — same fixture style as [UploadJournalTest]. The medical-data
 * isolation rule gets its own scenarios: the dedup key is the COMPOSITE
 * (timestampEpochSec, userSlot), so the same measurement time for USER1 and
 * USER2 is two DIFFERENT records and must never dedup each other. Plain
 * JUnit only (Robolectric cannot run on this repo's JDK 26 toolchain;
 * android.util.Log is a stubbed no-op).
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
}

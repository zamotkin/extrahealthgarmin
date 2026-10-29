package com.zamotkin.extrahealthgarmin.devices.ble1810

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [Ble1810FingerprintJournal] tests (task tefal2garmin-app-redesign-v3-xlg.9):
 * the occurrence-counting dedup for timestamp-less records, the pass-through
 * of timestamp-carrying records, and the file persistence (roundtrip,
 * corrupted file, merge-on-save). File-based like
 * [com.zamotkin.extrahealthgarmin.devices.store.ConnectedSourceStoreTest]:
 * plain JVM with a temp folder; android.util.Log runs against stubbed
 * framework methods and org.json comes from the JVM test dependency.
 */
class Ble1810FingerprintJournalTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun measurement(
        systolic: Int,
        timestampEpochSec: Long? = null
    ): Ble1810Measurement = Ble1810Measurement(
        systolicMmHg = systolic,
        diastolicMmHg = 80,
        mapMmHg = 102,
        pulseBpm = 64,
        timestampEpochSec = timestampEpochSec ?: 1_000,
        timestampFromDeviceClock = timestampEpochSec != null,
        userId = null,
        status = Ble1810MeasurementStatus(
            bodyMovement = false,
            cuffTooLoose = false,
            irregularPulse = false,
            improperPosition = false,
            raw = 0
        )
    )

    private fun journal(): Ble1810FingerprintJournal =
        Ble1810FingerprintJournal.inFilesDir(tmp.root, "AA:BB:CC:DD:EE:FF")

    // ------------------------------------------------------------------ //
    //  Occurrence counting (timestamp-less records)                       //
    // ------------------------------------------------------------------ //

    @Test
    fun `first pass delivers every timestamp-less record`() {
        val journal = journal()

        val delivered = journal.filterUnseen(listOf(measurement(120), measurement(125)))

        assertEquals(listOf(120, 125), delivered.map { it.systolicMmHg })
        journal.markSeen(delivered)
        assertEquals(2, journal.fingerprintCount())
    }

    @Test
    fun `full re-read of the same records delivers nothing`() {
        val journal = journal()
        val burst = listOf(measurement(120), measurement(125))
        journal.markSeen(journal.filterUnseen(burst))

        assertEquals(emptyList<Ble1810Measurement>(), journal.filterUnseen(burst))
    }

    @Test
    fun `a re-read extended by new records delivers only the new ones`() {
        val journal = journal()
        journal.markSeen(journal.filterUnseen(listOf(measurement(120), measurement(125))))

        val delivered = journal.filterUnseen(
            listOf(measurement(120), measurement(125), measurement(130))
        )

        assertEquals(listOf(130), delivered.map { it.systolicMmHg })
    }

    @Test
    fun `two identical records in one burst are both delivered, then both recognized`() {
        val journal = journal()
        val burst = listOf(measurement(120), measurement(120))

        val firstPass = journal.filterUnseen(burst)
        journal.markSeen(firstPass)

        assertEquals(2, firstPass.size)
        assertEquals(emptyList<Ble1810Measurement>(), journal.filterUnseen(burst))
    }

    @Test
    fun `different values never dedup each other`() {
        val journal = journal()
        journal.markSeen(journal.filterUnseen(listOf(measurement(120))))

        val delivered = journal.filterUnseen(listOf(measurement(126)))

        assertEquals(1, delivered.size)
    }

    // ------------------------------------------------------------------ //
    //  Timestamp-carrying records                                         //
    // ------------------------------------------------------------------ //

    @Test
    fun `timestamp-carrying records pass through and never touch the journal`() {
        val journal = journal()
        val deviceTimed = measurement(120, timestampEpochSec = 1_760_000_000)

        val firstPass = journal.filterUnseen(listOf(deviceTimed))
        journal.markSeen(firstPass)
        val secondPass = journal.filterUnseen(listOf(deviceTimed))

        assertEquals(1, firstPass.size)
        assertEquals(1, secondPass.size) // identity is the stable device time, dedup is downstream
        assertEquals(0, journal.fingerprintCount())
    }

    // ------------------------------------------------------------------ //
    //  Persistence                                                        //
    // ------------------------------------------------------------------ //

    @Test
    fun `counts survive a new journal instance from the same file`() {
        val burst = listOf(measurement(120), measurement(125))
        journal().let { first ->
            first.markSeen(first.filterUnseen(burst))
        }

        val reloaded = journal()

        assertEquals(emptyList<Ble1810Measurement>(), reloaded.filterUnseen(burst))
    }

    @Test
    fun `a corrupted journal file loads empty, never throws`() {
        val file = File(tmp.root, Ble1810FingerprintJournal.fileNameFor("AA:BB:CC:DD:EE:FF"))
        file.writeText("not json at all")

        val journal = Ble1810FingerprintJournal.inFilesDir(tmp.root, "AA:BB:CC:DD:EE:FF")

        assertEquals(0, journal.fingerprintCount())
        assertEquals(1, journal.filterUnseen(listOf(measurement(120))).size)
    }

    @Test
    fun `merge-on-save keeps counts another instance persisted`() {
        val first = journal()
        val second = journal() // constructed before first saved anything

        first.markSeen(first.filterUnseen(listOf(measurement(120))))
        second.markSeen(second.filterUnseen(listOf(measurement(125))))

        val reloaded = journal()
        assertEquals(
            emptyList<Ble1810Measurement>(),
            reloaded.filterUnseen(listOf(measurement(120), measurement(125)))
        )
    }

    @Test
    fun `journal file name dashes the MAC colons`() {
        assertEquals(
            "ble1810-AA-BB-CC-DD-EE-FF-fingerprints.json",
            Ble1810FingerprintJournal.fileNameFor("AA:BB:CC:DD:EE:FF")
        )
    }
}

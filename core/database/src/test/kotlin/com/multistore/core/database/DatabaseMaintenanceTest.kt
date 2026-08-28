package com.multistore.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.multistore.core.database.entity.StoreIndexEntryEntity
import com.multistore.core.model.StoreId
import kotlin.time.Instant
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The "Reclaim space" button, tested against the disk rather than on trust.
 *
 * The database is **on file** and not in memory, unlike every other test in this module, and that
 * is not an oversight: `VACUUM` has nothing to return to a database living in RAM, and the number
 * the operation reports to the user is precisely the file's change in size. Testing it in memory
 * would prove only that the method does not throw.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DatabaseMaintenanceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: MultiStoreDatabase
    private lateinit var maintenance: DatabaseMaintenance

    @Before
    fun setUp() {
        context.getDatabasePath(MultiStoreDatabase.NAME).delete()
        db = Room.databaseBuilder(context, MultiStoreDatabase::class.java, MultiStoreDatabase.NAME)
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()
        maintenance = DatabaseMaintenance(db, context, UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        db.close()
        context.getDatabasePath(MultiStoreDatabase.NAME).delete()
    }

    @Test
    fun `after a deletion the space returns to the device`() = runTest {
        val dao = db.indexDao()
        // A realistic payload: in the real index a pruned entry weighs a few kilobytes, and it
        // is the thousands of entries that make up the table's 35.7 MB.
        dao.upsertEntries((1..400).map { entry(it, payload = "x".repeat(4_096)) })
        maintenance.compact()
        val mainFileWhenFull = mainFile()

        dao.deleteEntries(StoreId.FDROID, (1..400).map { "app.$it" })
        val mainFileAfterDelete = mainFile()
        val reclaimed = maintenance.compact()

        // This is the whole point: SQLite does **not** return the pages freed by a deletion on
        // its own — it keeps them for reuse, which is right during use and useless when the user
        // is asking for space. The comparison is on the **main file**: the `-wal` grows and
        // zeroes itself, so looking at the total would confuse what `VACUUM` returns with what
        // the checkpoint moves.
        assertThat(mainFileAfterDelete).isAtLeast(mainFileWhenFull)
        assertThat(reclaimed.freedBytes).isGreaterThan(0L)
        assertThat(mainFile()).isLessThan(mainFileAfterDelete)
        assertThat(reclaimed.bytesAfter).isEqualTo(occupied())
        // **The `-wal` is measured on its own**, and that is not pedantry. In WAL mode `VACUUM`
        // rewrites the entire database inside the write-ahead log: checkpointing *first* — as the
        // first version did — leaves a `-wal` as large as the database, and the operation meant
        // to free space doubles it. On the real device that was 65.3 MB becoming 129.4. Looking
        // only at totals on a small database does not see it.
        assertThat(walBytes()).isLessThan(mainFile() / 10)
    }

    @Test
    fun `on an already compact database it promises no saving that is not there`() = runTest {
        db.indexDao().upsertEntries(listOf(entry(1, payload = "y".repeat(1_024))))
        maintenance.compact()


        // Zero is a legitimate outcome, and the one the UI renders with a sentence other than
        // "0 bytes freed".
        assertThat(maintenance.compact().freedBytes).isEqualTo(0L)
    }

    private fun mainFile(): Long = context.getDatabasePath(MultiStoreDatabase.NAME).length()

    private fun walBytes(): Long =
        java.io.File(context.getDatabasePath(MultiStoreDatabase.NAME).path + "-wal").length()

    private fun occupied(): Long = listOf("", "-wal", "-shm")
        .sumOf { java.io.File(context.getDatabasePath(MultiStoreDatabase.NAME).path + it).length() }

    private fun entry(index: Int, payload: String) = StoreIndexEntryEntity(
        storeId = StoreId.FDROID,
        entryId = "app.$index",
        payload = payload,
        updatedAt = Instant.fromEpochMilliseconds(index.toLong()),
    )
}

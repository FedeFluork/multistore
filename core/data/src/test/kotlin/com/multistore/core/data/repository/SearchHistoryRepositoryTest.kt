package com.multistore.core.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.multistore.core.database.MultiStoreDatabase
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Remembering what was searched for, and the switch that stops it.
 *
 * ### Why the record exists at all
 *
 * One aggregated search is up to nine requests to third-party sites. Retyping a query because there
 * was no way to recall it pays that twice, which is the opposite of the courtesy this project
 * practises everywhere else — the rate limits honoured, the speculative prefetch refused, the
 * `Crawl-delay` waited out.
 *
 * ### And the two things that make it a history rather than a log
 *
 * The same search twice is one entry that moved, and the ceiling is enforced on write. Without the
 * first the list fills with the word being refined; without the second it becomes a record of
 * everything ever typed, kept for nobody, in a list that only ever shows ten.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SearchHistoryRepositoryTest {

    private lateinit var db: MultiStoreDatabase
    private lateinit var settings: LocalSettings
    private lateinit var repository: SearchHistoryRepositoryImpl

    private var now = Instant.fromEpochMilliseconds(1_000)
    private val clock = object : Clock {
        override fun now(): Instant = now
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MultiStoreDatabase::class.java,
        ).allowMainThreadQueries().build()
        settings = LocalSettings()
        repository = SearchHistoryRepositoryImpl(
            dao = db.searchHistoryDao(),
            settings = settings,
            clock = clock,
            io = Dispatchers.Unconfined,
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `the newest search comes first, and repeating one moves it rather than adding`() = runTest {
        record("telegram", at = 1_000)
        record("firefox", at = 2_000)
        record("telegram", at = 3_000)

        assertThat(repository.recent().first()).containsExactly("telegram", "firefox").inOrder()
    }

    @Test
    fun `what is kept is what was typed, trimmed but not normalised`() = runTest {
        // It goes back into the text field. Handing back a lowercased, accent-stripped version of
        // somebody's own words would be the app correcting them — and it is why the stored form is
        // deliberately not the one the search itself normalises.
        record("  Firefox Focus  ", at = 1_000)

        assertThat(repository.recent().first()).containsExactly("Firefox Focus")
    }

    @Test
    fun `an empty search is not a search`() = runTest {
        record("   ", at = 1_000)

        assertThat(repository.recent().first()).isEmpty()
    }

    @Test
    fun `the ceiling holds on write, not only on read`() = runTest {
        repeat(SearchHistoryRepository.LIMIT + 5) { index -> record("q$index", at = 1_000L + index) }

        val kept = repository.recent().first()
        assertThat(kept).hasSize(SearchHistoryRepository.LIMIT)
        // The newest survive: a ceiling that dropped the recent ones would keep exactly the queries
        // nobody is coming back to.
        assertThat(kept.first()).isEqualTo("q${SearchHistoryRepository.LIMIT + 4}")
        // And the table itself is pruned, not merely the page that is read: a record kept beyond the
        // list is a record kept for nobody.
        assertThat(db.searchHistoryDao().observeRecent(limit = 1_000).first()).hasSize(
            SearchHistoryRepository.LIMIT,
        )
    }

    @Test
    fun `with the record switched off nothing is written`() = runTest {
        // The setting is read **here** rather than by the caller, which is the same choice as the
        // adult filter in `SearchRepositoryImpl` and for the same reason: a preference passed in as
        // a parameter is one somebody eventually forgets to pass, and forgetting produces no error —
        // only a record the user asked not to have.
        settings.setKeepSearchHistory(false)

        record("telegram", at = 1_000)

        assertThat(repository.recent().first()).isEmpty()
    }

    @Test
    fun `the setting is re-read on every write, not captured once`() = runTest {
        settings.setKeepSearchHistory(false)
        record("telegram", at = 1_000)

        settings.setKeepSearchHistory(true)
        record("firefox", at = 2_000)

        // Captured at construction, turning it back on would have no effect until the next launch —
        // the defect already corrected in M3 on the update scheduling and in M4 on the challenge
        // strategy.
        assertThat(repository.recent().first()).containsExactly("firefox")
    }

    @Test
    fun `forgetting one leaves the others, and clearing leaves none`() = runTest {
        record("telegram", at = 1_000)
        record("firefox", at = 2_000)

        repository.forget("telegram")
        assertThat(repository.recent().first()).containsExactly("firefox")

        repository.clear()
        assertThat(repository.recent().first()).isEmpty()
    }

    private suspend fun record(query: String, at: Long) {
        now = Instant.fromEpochMilliseconds(at)
        repository.record(query)
    }
}

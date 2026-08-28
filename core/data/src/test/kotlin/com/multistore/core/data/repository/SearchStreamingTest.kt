package com.multistore.core.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.multistore.core.data.FakeIndexedStoreAdapter
import com.multistore.core.data.store.EnabledStores
import com.multistore.core.data.store.SearchGroupMemory
import com.multistore.core.data.store.StoreRegistry
import com.multistore.core.database.MultiStoreDatabase
import com.multistore.core.model.SearchSettings
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.model.StoreListingSummary
import com.multistore.store.api.PagedResult
import com.multistore.store.api.SearchSource
import com.multistore.store.api.StoreError
import com.multistore.store.api.StoreResult
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The streaming of partials: what is seen **before** everyone has answered.
 *
 * The dispatcher is an [UnconfinedTestDispatcher] hooked to `runTest`'s scheduler, and not
 * `Dispatchers.Unconfined` as in this module's other tests. The difference is the tests' very
 * subject: here time counts — an eight-second timeout and a store answering in three — and on a real
 * dispatcher those seconds would be real seconds. No `Thread.sleep` in tests: clocks and dispatchers
 * are injected.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SearchStreamingTest {

    private lateinit var db: MultiStoreDatabase

    private var currentTime = Instant.fromEpochMilliseconds(1_787_316_712_615L)
    private val clock = object : Clock {
        override fun now(): Instant = currentTime
    }

    /**
     * Room **on the same thread**, and it is not a convenience detail.
     *
     * Time here is virtual, and its rule is that the scheduler advances as soon as it has nothing left
     * to run. With normal executors Room's queries run on real threads: while one of them is in flight
     * the scheduler **does not see it**, concludes there is nothing to do and jumps to the timeout's
     * eight seconds. The result is that every store comes out timed out — i.e. the opposite of what we
     * meant to measure, and for a reason that has nothing to do with the search.
     */
    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MultiStoreDatabase::class.java,
        )
            .setQueryExecutor(Runnable::run)
            .setTransactionExecutor(Runnable::run)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    private fun TestScope.build(
        adapters: Set<FakeIndexedStoreAdapter>,
        storeTimeout: Duration = SearchSettings.DEFAULT_STORE_TIMEOUT,
    ): Pair<SearchRepositoryImpl, StoreHealthRepositoryImpl> {
        val dispatcher: CoroutineDispatcher = UnconfinedTestDispatcher(testScheduler)
        val registry = StoreRegistry(adapters)
        val settings = LocalSettings(storeTimeout = storeTimeout)
        val health = StoreHealthRepositoryImpl(registry, db.storeDao(), clock, dispatcher)
        return SearchRepositoryImpl(
            registry = registry,
            enabledStores = EnabledStores(registry, db.storeDao()),
            settings = settings,
            memory = SearchGroupMemory(),
            catalogDao = db.catalogDao(),
            indexDao = db.indexDao(),
            health = health,
            clock = clock,
            io = dispatcher,
        ) to health
    }

    private fun store(id: StoreId, title: String, delaySeconds: Int = 0) =
        FakeIndexedStoreAdapter(id, source = SearchSource.REMOTE).apply {
            searchDelay = delaySeconds.seconds
            searchResults = StoreResult.Success(
                PagedResult.single(
                    listOf(
                        StoreListingSummary(
                            storeId = id,
                            ref = StoreAppRef("$title-on-${id.wireName}"),
                            title = title,
                            packageName = "com.example.${title.lowercase()}",
                        ),
                    ),
                ),
            )
        }

    @Test
    fun `the fast one's results are seen while the slow one is still answering`() = runTest {
        val fast = store(StoreId.APKCOMBO, "Telegram")
        val slow = store(StoreId.APKMIRROR, "Signal", delaySeconds = 3)
        val (repository, _) = build(setOf(fast, slow))

        val emissions = repository.searchStreaming("chat").toList()

        // Three emissions: who is being queried, the fast one, the slow one. A search waiting for
        // everyone would give one, and with nine stores this distinction is the whole point.
        assertThat(emissions).hasSize(3)
        assertThat(emissions.first().answered).isEmpty()
        assertThat(emissions.first().pending).containsExactly(StoreId.APKCOMBO, StoreId.APKMIRROR)
        assertThat(emissions.first().complete).isFalse()

        assertThat(emissions[1].page.apps).hasSize(1)
        assertThat(emissions[1].pending).containsExactly(StoreId.APKMIRROR)

        assertThat(emissions.last().page.apps).hasSize(2)
        assertThat(emissions.last().complete).isTrue()
    }

    @Test
    fun `a group can only grow, and what has been seen does not disappear`() = runTest {
        val fast = store(StoreId.APKCOMBO, "Telegram")
        val slow = store(StoreId.APKMIRROR, "Telegram", delaySeconds = 2)
        val (repository, _) = build(setOf(fast, slow))

        val emissions = repository.searchStreaming("telegram").toList()

        // The same package on the two stores: the group exists from the second emission and at the
        // third gains a store instead of being replaced by another group. It is the guarantee that
        // makes reordering during streaming acceptable.
        val afterFast = emissions[1].page.apps.single()
        val afterSlow = emissions[2].page.apps.single()
        assertThat(afterFast.storeCount).isEqualTo(1)
        assertThat(afterSlow.storeCount).isEqualTo(2)
        assertThat(afterSlow.appKey).isEqualTo(afterFast.appKey)
    }

    @Test
    fun `a store that does not answer becomes a shortfall after eight seconds, not a wait`() =
        runTest {
            val fast = store(StoreId.APKCOMBO, "Telegram")
            val stuck = store(StoreId.APKMIRROR, "Signal", delaySeconds = 60)
            val (repository, _) = build(setOf(fast, stuck))

            val started = currentTime
            val page = repository.search("chat")
            val elapsed = currentTime - started

            // The timeout is **per store**: the eight seconds are paid by whoever does not answer, not
            // by the whole search, and what the others found stays.
            assertThat(elapsed).isLessThan(9_000)
            assertThat(page.apps).hasSize(1)
            assertThat(page.shortfalls.single().storeId).isEqualTo(StoreId.APKMIRROR)
        }

    /**
     * The timeout is decided by the user, and both directions have to be tested.
     *
     * One alone is not enough, and it is the defect this pair exists to catch: with the value back to
     * being the eight-second constant, a test raising the timeout to thirty fails, but one lowering it
     * to four would fail with a timeout of two or one as well — i.e. it would pass for any value
     * tighter than the default, a wrong one included.
     */
    @Test
    fun `a shorter timeout cuts before the default`() = runTest {
        val fast = store(StoreId.APKCOMBO, "Telegram")
        val slow = store(StoreId.APKMIRROR, "Signal", delaySeconds = 6)
        val (repository, _) = build(setOf(fast, slow), storeTimeout = 4.seconds)

        val started = currentTime
        val page = repository.search("chat")
        val elapsed = currentTime - started

        // Six seconds are **below** the default of eight: with the constant in place of the setting
        // this store would answer, and the search would have two results.
        assertThat(elapsed).isLessThan(5_000)
        assertThat(page.apps).hasSize(1)
        assertThat(page.shortfalls.single().storeId).isEqualTo(StoreId.APKMIRROR)
    }

    @Test
    fun `a longer timeout waits beyond the default`() = runTest {
        val slow = store(StoreId.APKMIRROR, "Signal", delaySeconds = 12)
        val (repository, _) = build(setOf(slow), storeTimeout = 30.seconds)

        val page = repository.search("signal")

        // Twelve seconds are **beyond** the default: with the constant this store would come out
        // absent, and whoever chose thirty seconds would see their value silently ignored.
        assertThat(page.apps).hasSize(1)
        assertThat(page.shortfalls).isEmpty()
    }

    @Test
    fun `the open breaker says how long until the retry`() = runTest {
        val open = store(StoreId.APKMIRROR, "Signal")
        val (repository, health) = build(setOf(open))
        health.recordFailure(StoreId.APKMIRROR, StoreError.RateLimited(retryAfter = null))

        val shortfall = repository.search("signal").shortfalls.single()

        // "apkmirror unavailable" leaves the user wondering whether it is worth insisting; "retrying
        // in 5 minutes" does not. The second sentence is the one required.
        assertThat(shortfall.circuitOpen).isTrue()
        assertThat(shortfall.retryIn).isNotNull()
        assertThat(shortfall.retryIn!!.inWholeMinutes).isEqualTo(5)
    }
}

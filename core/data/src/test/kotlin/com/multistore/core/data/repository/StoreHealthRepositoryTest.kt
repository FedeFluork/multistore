package com.multistore.core.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.multistore.core.common.net.CircuitBreakerPolicy
import com.multistore.core.data.FakeIndexedStoreAdapter
import com.multistore.core.data.store.StoreRegistry
import com.multistore.core.database.MultiStoreDatabase
import com.multistore.core.model.StoreHealthState
import com.multistore.core.model.StoreId
import com.multistore.store.api.StoreError
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The circuit breaker, from the side `CircuitBreakerPolicy`'s pure functions cannot test: **what
 * stays written**.
 *
 * The state lives in Room precisely so that it survives the process dying. A test on the pure
 * functions alone would pass even with a persistence layer losing half the fields on the way, or
 * rewriting the columns that do not belong to it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class StoreHealthRepositoryTest {

    private lateinit var db: MultiStoreDatabase
    private lateinit var repository: StoreHealthRepositoryImpl

    private var currentTime = Instant.fromEpochMilliseconds(1_787_316_712_615L)
    private val clock = object : Clock {
        override fun now(): Instant = currentTime
    }

    private val store = StoreId.FDROID

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MultiStoreDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = StoreHealthRepositoryImpl(
            registry = StoreRegistry(setOf(FakeIndexedStoreAdapter(store))),
            storeDao = db.storeDao(),
            clock = clock,
            io = Dispatchers.Unconfined,
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `a 429 opens immediately, and the opening survives a re-read`() = runTest {
        repository.recordFailure(store, StoreError.RateLimited(retryAfter = 10.minutes))

        val health = repository.health(store)
        assertThat(health.state).isEqualTo(StoreHealthState.OPEN)
        assertThat(health.openUntil).isEqualTo(currentTime + 10.minutes)
        assertThat(repository.canAttempt(store)).isFalse()
    }

    @Test
    fun `once the opening expires we move to HALF_OPEN, and the state records it`() = runTest {
        repository.recordFailure(store, StoreError.RateLimited(retryAfter = 5.minutes))
        currentTime += 5.minutes + 1.seconds

        assertThat(repository.canAttempt(store)).isTrue()
        // `canAttempt` has a deliberate effect: without somebody expiring the opening, the state would
        // stay OPEN until the first call somebody decides to make anyway.
        assertThat(repository.health(store).state).isEqualTo(StoreHealthState.HALF_OPEN)
    }

    @Test
    fun `three different selectors degrade the store, the same one a hundred times does not`() = runTest {
        repeat(100) {
            repository.recordFailure(store, StoreError.ParseFailure("div.title", "hash"))
        }
        assertThat(repository.health(store).state).isEqualTo(StoreHealthState.CLOSED)

        repository.recordFailure(store, StoreError.ParseFailure("a.download", "hash"))
        repository.recordFailure(store, StoreError.ParseFailure("span.version", "hash"))

        // A malformed page can present itself a hundred times for the same selector without the parser
        // being broken. Three *different* selectors say something else: the markup has changed.
        assertThat(repository.health(store).state).isEqualTo(StoreHealthState.DEGRADED)
    }

    @Test
    fun `a success closes it again and zeroes the window`() = runTest {
        repository.recordFailure(store, StoreError.Network(cause = null))
        repository.recordSuccess(store)

        val health = repository.health(store)
        assertThat(health.state).isEqualTo(StoreHealthState.CLOSED)
        assertThat(health.windowFailures).isEqualTo(0)
        assertThat(health.lastSuccessAt).isEqualTo(currentTime)
    }

    @Test
    fun `registering the stores does not zero what is already there`() = runTest {
        repository.registerKnownStores()
        repository.setEnabled(store, enabled = false)
        repository.recordFailure(store, StoreError.RateLimited(retryAfter = 10.minutes))

        // The real case is the app restarting: the adapters re-announce themselves, and an upsert
        // would reset both the breaker and the user's choice at every launch.
        repository.registerKnownStores()

        assertThat(repository.health(store).state).isEqualTo(StoreHealthState.OPEN)
        assertThat(db.storeDao().get(store)?.enabled).isFalse()
    }

    @Test
    fun `a network failure does not switch back on a store the user switched off`() = runTest {
        repository.registerKnownStores()
        repository.setEnabled(store, enabled = false)

        repository.recordFailure(store, StoreError.Network(cause = null))

        assertThat(db.storeDao().get(store)?.enabled).isFalse()
    }

    @Test
    fun `a 404 is not a store fault`() = runTest {
        repository.recordFailure(store, StoreError.NotFound)

        assertThat(repository.health(store).state).isEqualTo(StoreHealthState.CLOSED)
        assertThat(repository.health(store).windowFailures).isEqualTo(0)
    }

    @Test
    fun `the backoff grows at every reopening`() = runTest {
        repository.recordFailure(store, StoreError.Blocked(com.multistore.core.model.BlockKind.CAPTCHA))
        val first = repository.health(store).openUntil

        currentTime += CircuitBreakerPolicy.INITIAL_OPEN + 1.seconds
        repository.canAttempt(store)
        repository.recordFailure(store, StoreError.Blocked(com.multistore.core.model.BlockKind.CAPTCHA))
        val second = repository.health(store).openUntil

        assertThat(first).isEqualTo(
            Instant.fromEpochMilliseconds(1_787_316_712_615L) + CircuitBreakerPolicy.INITIAL_OPEN,
        )
        assertThat(second!! - currentTime).isEqualTo(CircuitBreakerPolicy.INITIAL_OPEN * 2)
    }

    @Test
    fun `every failure leaves a diagnostic trace`() = runTest {
        repository.recordFailure(store, StoreError.ParseFailure("div.title", "abc123"))

        val events = db.storeDao().recentEvents(limit = 10)
        assertThat(events).hasSize(1)
        assertThat(events.single().selector).isEqualTo("div.title")
        assertThat(events.single().snippetHash).isEqualTo("abc123")
    }

    // --- The diagnosis: how long, and what kind -----------------------------------------------

    @Test
    fun `the run of faults is measured from the last success, not from the oldest row`() = runTest {
        // An old fault, then a success, then two recent ones. The oldest row is a week old and is
        // still in the table; the **run** is minutes long, and telling the two apart is the whole
        // point — one reading has somebody wait, the other has them switch off a store that works.
        at(0) { repository.recordFailure(store, StoreError.Network(null)) }
        at(7 * 24 * 60) { repository.recordSuccess(store) }
        at(7 * 24 * 60 + 10) { repository.recordFailure(store, StoreError.Network(null)) }
        at(7 * 24 * 60 + 20) { repository.recordFailure(store, StoreError.Network(null)) }

        val diagnosis = repository.diagnosis(store)

        assertThat(diagnosis.failingSince).isEqualTo(minutesFromStart(7 * 24 * 60 + 10))
        assertThat(diagnosis.lastSuccessAt).isEqualTo(minutesFromStart(7 * 24 * 60))
        assertThat(diagnosis.lastFailure?.at).isEqualTo(minutesFromStart(7 * 24 * 60 + 20))
    }

    @Test
    fun `a store that has only ever answered has nothing to explain`() = runTest {
        at(0) { repository.recordSuccess(store) }

        val diagnosis = repository.diagnosis(store)

        assertThat(diagnosis.failingSince).isNull()
        assertThat(diagnosis.lastFailure).isNull()
        // And the dialog knows there is nothing to open: a healthy store's would be an empty page.
        assertThat(diagnosis.hasFaults).isFalse()
    }

    @Test
    fun `not-found is not a fault of the store`() = runTest {
        // The circuit breaker already treats it that way — it does not count towards opening — and
        // this has to agree: "that app is not on this store" is the store answering correctly, and
        // counting it would make a working store look broken for having been asked about something
        // it does not have.
        at(0) { repository.recordFailure(store, StoreError.NotFound) }

        val diagnosis = repository.diagnosis(store)

        assertThat(diagnosis.lastFailure).isNull()
        assertThat(diagnosis.failingSince).isNull()
    }

    @Test
    fun `the diagnostic log's own rows are not faults`() = runTest {
        // With `diagnostics_log_enabled` on, every request writes a `request` row into the same
        // table. A filter written as "anything that is not a success" would look right and start
        // reporting those as the store's last fault the moment somebody turned that switch on.
        at(0) { repository.recordEvent(store, kind = "request", detail = "GET / → 200") }

        assertThat(repository.diagnosis(store).lastFailure).isNull()
    }

    @Test
    fun `the kind and its selector reach the diagnosis`() = runTest {
        at(0) {
            repository.recordFailure(store, StoreError.ParseFailure("#content .listWidget", "abc123"))
        }

        val fault = repository.diagnosis(store).lastFailure
        assertThat(fault?.kind).isEqualTo(com.multistore.core.common.net.FailureKind.PARSE)
        // The selector, because "the markup changed" and "the network is down" are two different
        // jobs and only one of them is ours.
        assertThat(fault?.selector).isEqualTo("#content .listWidget")
    }

    private inline fun at(minutes: Int, block: () -> Unit) {
        currentTime = minutesFromStart(minutes)
        block()
    }

    private fun minutesFromStart(minutes: Int): Instant =
        Instant.fromEpochMilliseconds(1_787_316_712_615L) + minutes.minutes
}

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
}

package com.multistore.core.data.repository

import com.multistore.core.common.coroutine.IoDispatcher
import com.multistore.core.common.net.CircuitBreakerPolicy
import com.multistore.core.common.net.FailureKind
import com.multistore.core.common.net.StoreDiagnosis
import com.multistore.core.common.net.StoreFault
import kotlin.time.Instant
import com.multistore.core.common.net.StoreHealth
import com.multistore.core.data.mapper.parseSelector
import com.multistore.core.data.mapper.toFailureKind
import com.multistore.core.data.store.StoreRegistry
import com.multistore.core.database.dao.StoreDao
import com.multistore.core.database.entity.HealthEventEntity
import com.multistore.core.database.entity.StoreEntity
import com.multistore.core.model.StoreCategory
import com.multistore.core.model.StoreId
import com.multistore.store.api.StoreError
import javax.inject.Inject
import kotlin.time.Duration.Companion.days
import javax.inject.Singleton
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
internal class StoreHealthRepositoryImpl @Inject constructor(
    private val registry: StoreRegistry,
    private val storeDao: StoreDao,
    private val clock: Clock,
    @IoDispatcher private val io: CoroutineDispatcher,
) : StoreHealthRepository {

    /**
     * Serialises read-modify-write on the breaker's state.
     *
     * Nine stores queried in parallel produce failures in parallel, and without this lock two
     * concurrent `onFailure`s would read the same window and write two versions of it: the failure
     * count would drop beats precisely when many arrive, i.e. exactly when it has to open.
     */
    private val mutex = Mutex()

    override suspend fun registerKnownStores() = withContext(io) {
        registry.all.forEachIndexed { order, adapter ->
            // `registerIfAbsent` and not `upsert`: an upsert would reset the circuit breaker and the
            // user's choice of which stores to query at every launch.
            storeDao.registerIfAbsent(StoreEntity(storeId = adapter.id, displayOrder = order))
        }
    }

    override fun observeAll(): Flow<List<StoreHealth>> =
        storeDao.observeAll().map { stores -> stores.map { it.toHealth() } }

    override fun observeStores(): Flow<List<StoreEntry>> =
        storeDao.observeAll().map { rows ->
            val byId = rows.associateBy { it.storeId }
            // The order is `registry.all`'s, i.e. `StoreId`'s declaration order. Ordering by
            // `display_order` read from the table would give the same result today and no order at
            // all on first launch, when the rows are not there yet.
            registry.all.map { adapter ->
                val row = byId[adapter.id]
                StoreEntry(
                    storeId = adapter.id,
                    displayName = adapter.metadata.displayName,
                    host = adapter.metadata.host,
                    enabled = row?.enabled ?: true,
                    // Composed **here** and not in the screen that groups by it: the two
                    // declarations live on the adapter, which a `:feature:*` cannot see, and a
                    // second place computing the same `when` would be a second place to diverge.
                    category = StoreCategory.of(
                        openSourceOnly = adapter.capabilities.openSourceOnly,
                        redistributesModifiedBuilds = adapter.capabilities.redistributesModifiedBuilds,
                    ),
                    health = row?.toHealth() ?: StoreHealth(adapter.id),
                )
            }
        }

    override suspend fun health(storeId: StoreId): StoreHealth = withContext(io) {
        storeDao.get(storeId)?.toHealth() ?: StoreHealth(storeId)
    }

    override suspend fun diagnosis(storeId: StoreId): StoreDiagnosis = withContext(io) {
        val health = storeDao.get(storeId)?.toHealth() ?: StoreHealth(storeId)
        // The kinds come from the enum rather than being spelled into the SQL, so a kind added later
        // is included without anybody having to remember two places. `NOT_FOUND` is left out on
        // purpose and for the reason the circuit breaker leaves it out: "that app is not on this
        // store" is the store answering correctly, and counting it would make a working store look
        // broken for having been asked about something it does not have.
        val kinds = FailureKind.entries.filter { it != FailureKind.NOT_FOUND }.map { it.name }
        val last = storeDao.lastFailure(storeId, kinds)
        StoreDiagnosis(
            storeId = storeId,
            state = health.state,
            lastSuccessAt = health.lastSuccessAt,
            lastFailure = last?.let { event ->
                StoreFault(
                    // A kind this build does not know — a row written by a later version — is not a
                    // reason to show nothing: it falls back to the vaguest of the five, which is
                    // also the only one that promises nothing about what to do.
                    kind = FailureKind.entries.firstOrNull { it.name == event.kind }
                        ?: FailureKind.TRANSIENT,
                    at = event.at,
                    selector = event.selector,
                )
            },
            // Anchored on the **last success**, so this is the length of the current run rather than
            // the age of the oldest row still in the table. `EPOCH` where there has never been a
            // success: then every recorded fault belongs to the run, which is the truth.
            failingSince = storeDao.failingSince(
                storeId = storeId,
                kinds = kinds,
                since = health.lastSuccessAt ?: Instant.fromEpochMilliseconds(0),
            ),
            openUntil = health.openUntil,
            parseFailureSelectors = health.parseFailureSelectors,
        )
    }

    override suspend fun canAttempt(storeId: StoreId): Boolean = withContext(io) {
        mutex.withLock {
            val now = clock.now()
            val current = storeDao.get(storeId)?.toHealth() ?: StoreHealth(storeId)
            val refreshed = CircuitBreakerPolicy.refreshed(current, now)
            if (refreshed != current) persist(refreshed)
            CircuitBreakerPolicy.canAttempt(refreshed, now)
        }
    }

    override suspend fun recordSuccess(storeId: StoreId) = withContext(io) {
        mutex.withLock {
            val now = clock.now()
            val current = storeDao.get(storeId)?.toHealth() ?: StoreHealth(storeId)
            persist(CircuitBreakerPolicy.onSuccess(current, now))
        }
    }

    override suspend fun recordFailure(storeId: StoreId, error: StoreError) = withContext(io) {
        val kind = error.toFailureKind()
        val selector = error.parseSelector()
        mutex.withLock {
            val now = clock.now()
            val current = storeDao.get(storeId)?.toHealth() ?: StoreHealth(storeId)
            persist(
                CircuitBreakerPolicy.onFailure(
                    health = current,
                    kind = kind,
                    now = now,
                    retryAfter = (error as? StoreError.RateLimited)?.retryAfter,
                    selector = selector,
                ),
            )
        }
        storeDao.recordEvent(
            HealthEventEntity(
                storeId = storeId,
                kind = kind.name,
                selector = selector,
                snippetHash = (error as? StoreError.ParseFailure)?.snippetHash,
                at = clock.now(),
            ),
        )
    }

    override suspend fun pruneOldEvents() {
        withContext(io) { storeDao.pruneEventsBefore(clock.now() - EVENT_RETENTION) }
    }

    override suspend fun recordEvent(
        storeId: StoreId,
        kind: String,
        selector: String?,
        tier: Int?,
        detail: String?,
        durationMillis: Long?,
    ) {
        withContext(io) {
            storeDao.recordEvent(
                HealthEventEntity(
                    storeId = storeId,
                    kind = kind,
                    selector = selector,
                    resolverTier = tier,
                    detail = detail,
                    durationMillis = durationMillis,
                    at = clock.now(),
                ),
            )
        }
    }

    override suspend fun recentEvents(limit: Int): List<HealthEvent> = withContext(io) {
        storeDao.recentEvents(limit).map { row ->
            HealthEvent(
                storeId = row.storeId,
                kind = row.kind,
                selector = row.selector,
                resolverTier = row.resolverTier,
                detail = row.detail,
                durationMillis = row.durationMillis,
                at = row.at,
            )
        }
    }

    override suspend fun setEnabled(storeId: StoreId, enabled: Boolean) = withContext(io) {
        storeDao.setEnabled(storeId, enabled)
    }

    /**
     * Writes the breaker's state **without touching** the columns that do not belong to it.
     *
     * `enabled`, `display_order` and `base_url_override` are the user's or the remote config's
     * choices: an upsert built from [StoreHealth] alone would rewrite them to their defaults, and a
     * network failure would end up re-enabling a store the user had switched off.
     */
    private suspend fun persist(health: StoreHealth) {
        val existing = storeDao.get(health.storeId) ?: StoreEntity(storeId = health.storeId)
        storeDao.upsert(
            existing.copy(
                healthState = health.state,
                healthOpenUntil = health.openUntil,
                consecutiveOpenCycles = health.consecutiveOpenCycles,
                windowStart = health.windowStart,
                windowCalls = health.windowCalls,
                windowFailures = health.windowFailures,
                parseFailureSelectors = health.parseFailureSelectors.sorted(),
                lastSuccessAt = health.lastSuccessAt,
            ),
        )
    }

    private fun StoreEntity.toHealth() = StoreHealth(
        storeId = storeId,
        state = healthState,
        openUntil = healthOpenUntil,
        consecutiveOpenCycles = consecutiveOpenCycles,
        windowStart = windowStart,
        windowCalls = windowCalls,
        windowFailures = windowFailures,
        parseFailureSelectors = parseFailureSelectors.toSet(),
        lastSuccessAt = lastSuccessAt,
    )

    private companion object {
        /**
         * Thirty days.
         *
         * Diagnostics serves to answer "what broke lately": a month comfortably covers a store's
         * maintenance cycle, and beyond that there is nothing left to read that has not been read
         * already. It is not a space limit, it is a usefulness limit.
         */
        val EVENT_RETENTION = 30.days
    }
}

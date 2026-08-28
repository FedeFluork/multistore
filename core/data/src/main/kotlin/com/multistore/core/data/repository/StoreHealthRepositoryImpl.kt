package com.multistore.core.data.repository

import com.multistore.core.common.coroutine.IoDispatcher
import com.multistore.core.common.net.CircuitBreakerPolicy
import com.multistore.core.common.net.StoreHealth
import com.multistore.core.data.mapper.parseSelector
import com.multistore.core.data.mapper.toFailureKind
import com.multistore.core.data.store.StoreRegistry
import com.multistore.core.database.dao.StoreDao
import com.multistore.core.database.entity.HealthEventEntity
import com.multistore.core.database.entity.StoreEntity
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
                    health = row?.toHealth() ?: StoreHealth(adapter.id),
                )
            }
        }

    override suspend fun health(storeId: StoreId): StoreHealth = withContext(io) {
        storeDao.get(storeId)?.toHealth() ?: StoreHealth(storeId)
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

package com.multistore.core.data.repository

import com.multistore.core.common.net.StoreHealth
import com.multistore.core.model.StoreId
import kotlin.time.Instant
import com.multistore.store.api.StoreError
import kotlinx.coroutines.flow.Flow

/**
 * The stores' health state, persisted.
 *
 * The circuit breaker lives in Room and not in memory, for a reason only visible in the worst case:
 * an app restarting after being killed is an app about to remake every request at once, and a
 * breaker zeroed by the restart would open precisely when it was needed most.
 *
 * The state machine lives elsewhere, in `CircuitBreakerPolicy`, as pure functions. Here there is only
 * the bridge between those functions and the database.
 */
/**
 * A row of the diagnostic log, in the form the export reads it.
 *
 * A domain type and not Room's entity, because its consumer is `:feature:settings` through a
 * repository — and a `:feature:*` does not see Room, by the dependency rule `checkDependencyRules`
 * verifies.
 */
data class HealthEvent(
    val storeId: StoreId,
    val kind: String,
    val selector: String? = null,
    val resolverTier: Int? = null,
    val detail: String? = null,
    val durationMillis: Long? = null,
    val at: Instant,
)

/**
 * A store as the Settings screen sees it: identity, user's choice, health.
 *
 * It puts together two sources living in different places that have to stay distinct: [displayName]
 * and [host] come from the **adapter**, which is the only one that knows them; [enabled] and
 * [health] come from **Room**, because they are state and have to survive a restart.
 *
 * [displayName] is not translated text and rightly so: "APKMirror" is written the same in all five
 * languages. What is translated is the store's **description**, which lives in `:feature:settings`'s
 * `strings.xml` with one key per [storeId].
 */
data class StoreEntry(
    val storeId: StoreId,
    val displayName: String,
    val host: String,
    val enabled: Boolean,
    val health: StoreHealth,
)

interface StoreHealthRepository {

    /** Registers the stores the adapters declare, **without** resetting what is already there. */
    suspend fun registerKnownStores()

    fun observeAll(): Flow<List<StoreHealth>>

    /**
     * The stores to show in Settings, with the user's choice and the breaker's state.
     *
     * It starts from [com.multistore.core.data.store.StoreRegistry] and not from the table: on first
     * launch the rows are not there yet, and an empty "Stores" screen would make it look as though
     * the app knew none. A store not yet registered comes out **on**, which is the chosen default and
     * coincides with what the search does.
     */
    fun observeStores(): Flow<List<StoreEntry>>

    suspend fun health(storeId: StoreId): StoreHealth

    /**
     * `true` if a call can be attempted now.
     *
     * It has a deliberate side effect: it expires a matured opening, taking the state from `OPEN` to
     * `HALF_OPEN`. Without it, the breaker would stay open until the first call somebody decides to
     * make anyway — i.e. never.
     */
    suspend fun canAttempt(storeId: StoreId): Boolean

    suspend fun recordSuccess(storeId: StoreId)

    suspend fun recordFailure(storeId: StoreId, error: StoreError)

    /** Records a diagnostic event. Local and exportable: it never leaves the device. */
    suspend fun recordEvent(
        storeId: StoreId,
        kind: String,
        selector: String? = null,
        tier: Int? = null,
        detail: String? = null,
        durationMillis: Long? = null,
    )

    /** The most recent diagnostic events, newest first. The export reads them. */
    suspend fun recentEvents(limit: Int = 200): List<HealthEvent>

    /**
     * Prunes the diagnostic events older than the retention window.
     *
     * The DAO had the method from day one and **nobody called it**: `health_events` grew without
     * limit. It is not much — a few tens of bytes per event — but it is a table nobody ever deletes,
     * and diagnostics serves to read what went wrong *recently*: a thousand rows from six months ago
     * make it less useful, not more.
     */
    suspend fun pruneOldEvents()

    suspend fun setEnabled(storeId: StoreId, enabled: Boolean)
}

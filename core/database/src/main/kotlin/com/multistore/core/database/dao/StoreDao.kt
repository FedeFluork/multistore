package com.multistore.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.multistore.core.database.entity.HealthEventEntity
import com.multistore.core.database.entity.StoreEntity
import com.multistore.core.model.StoreId
import kotlinx.coroutines.flow.Flow

@Dao
interface StoreDao {

    @Query("SELECT * FROM stores ORDER BY display_order, store_id")
    fun observeAll(): Flow<List<StoreEntity>>

    @Query("SELECT * FROM stores WHERE store_id = :storeId")
    suspend fun get(storeId: StoreId): StoreEntity?

    @Query("SELECT * FROM stores WHERE enabled = 1 ORDER BY display_order, store_id")
    suspend fun enabled(): List<StoreEntity>

    /**
     * The stores that have a row, enabled or not.
     *
     * It exists to tell "the user switched it off" from "it has not been registered yet". That
     * looks like a nuance and is not: deriving enablement from the list of enabled ones alone
     * means that on first launch, with the table empty, either no store is queried — and search
     * never finds anything — or all of them are, in which case a store the user disabled comes
     * back on as soon as it is the only one left.
     */
    @Query("SELECT store_id FROM stores")
    suspend fun registeredIds(): List<StoreId>

    @Upsert
    suspend fun upsert(store: StoreEntity)

    /**
     * Registers a store without overwriting its state if it is already there.
     *
     * Needed at startup, when the adapters announce themselves: an upsert would reset the circuit
     * breaker and the user's choice of which stores to query on every launch.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun registerIfAbsent(store: StoreEntity)

    @Query("UPDATE stores SET enabled = :enabled WHERE store_id = :storeId")
    suspend fun setEnabled(storeId: StoreId, enabled: Boolean)

    @Insert
    suspend fun recordEvent(event: HealthEventEntity)

    @Query("SELECT * FROM health_events ORDER BY at DESC LIMIT :limit")
    suspend fun recentEvents(limit: Int = 200): List<HealthEventEntity>

    /**
     * The most recent fault recorded for one store, if there is one.
     *
     * ### The kinds are a parameter, and that is not indirection
     *
     * `health_events.kind` is not one vocabulary but three: the `FailureKind` names, the
     * `"request"` rows the diagnostic log writes when it is switched on, and the odd bespoke marker
     * like `"index_stale"`. A `!= 'success'` filter would look right and be wrong — there is no
     * `success` kind — and would start returning the log's own rows the day somebody turned
     * diagnostics on. Passing the list from the caller means the caller's enum decides, so a kind
     * added later is included without anybody having to remember this query.
     */
    @Query(
        """
        SELECT * FROM health_events
        WHERE store_id = :storeId AND kind IN (:kinds)
        ORDER BY at DESC LIMIT 1
        """,
    )
    suspend fun lastFailure(storeId: StoreId, kinds: List<String>): HealthEventEntity?

    /**
     * When the current run of failures began: the **earliest** failure since the last success.
     *
     * It is what turns "this store is not answering" into something a person can act on. "It has
     * been failing for ten minutes" is worth waiting out; "since last Tuesday" is worth switching
     * the store off — and until now the screen could say neither, only `OPEN` or `DEGRADED`.
     *
     * `:since` is the last success, or zero where there has never been one. Anchoring on the last
     * success rather than counting backwards from now is what makes it a **run**: a store that
     * answered an hour ago and broke since is not a store that has been broken all week, even
     * though a week-old failure is still in the table.
     */
    @Query(
        """
        SELECT MIN(at) FROM health_events
        WHERE store_id = :storeId AND kind IN (:kinds) AND at > :since
        """,
    )
    suspend fun failingSince(
        storeId: StoreId,
        kinds: List<String>,
        since: kotlin.time.Instant,
    ): kotlin.time.Instant?

    /** Old events serve nobody and the diagnostics are local: they are pruned. */
    @Query("DELETE FROM health_events WHERE at < :before")
    suspend fun pruneEventsBefore(before: kotlin.time.Instant)
}

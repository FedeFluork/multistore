package com.multistore.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.multistore.core.model.Sha256
import com.multistore.core.model.StoreHealthState
import com.multistore.core.model.StoreId
import kotlin.time.Instant

/**
 * A store, with its health state.
 *
 * The circuit breaker's state lives here and not in memory for a precise reason: an app
 * restarting after being killed is an app about to redo every request at once, and a breaker
 * reset by the restart would trip exactly when it was needed most.
 */
@Entity(tableName = "stores")
data class StoreEntity(
    @PrimaryKey @ColumnInfo(name = "store_id") val storeId: StoreId,
    val enabled: Boolean = true,
    @ColumnInfo(name = "display_order") val displayOrder: Int = 0,
    @ColumnInfo(name = "health_state") val healthState: StoreHealthState = StoreHealthState.CLOSED,
    @ColumnInfo(name = "health_open_until") val healthOpenUntil: Instant? = null,
    @ColumnInfo(name = "consecutive_open_cycles") val consecutiveOpenCycles: Int = 0,
    @ColumnInfo(name = "window_start") val windowStart: Instant? = null,
    @ColumnInfo(name = "window_calls") val windowCalls: Int = 0,
    @ColumnInfo(name = "window_failures") val windowFailures: Int = 0,
    /** The distinct selectors that failed to parse: three different ones = store degraded. */
    @ColumnInfo(name = "parse_failure_selectors") val parseFailureSelectors: List<String> = emptyList(),
    @ColumnInfo(name = "last_success_at") val lastSuccessAt: Instant? = null,
    @ColumnInfo(name = "parser_version") val parserVersion: Int = 0,
    @ColumnInfo(name = "base_url_override") val baseUrlOverride: String? = null,
)

/**
 * The state of a store's index synchronisation.
 *
 * Three fields, three problems solved:
 *
 *  - **[indexToken]** is what makes an incremental update possible at all. With no home for the
 *    token, diffs cannot even be requested.
 *  - **[pruningProfile]** records *which languages* the index was pruned to. If the app ever gains
 *    a sixth language, the profile no longer matches and the next sync starts over, instead of
 *    leaving that language empty forever on every already-downloaded app.
 *  - **[catalogPayload]** is the raw `repo` block. It has to be kept too, because it also arrives
 *    as a merge patch in a diff: replacing it wholesale would erase whatever that diff does not
 *    name.
 *
 * The token is written **only** after the stream has been consumed in full and the transaction has
 * committed. Writing it earlier would mean, after a process killed halfway, believing we hold an
 * index we do not — and from then on applying diffs to a wrong base, silently and forever.
 */
@Entity(tableName = "store_index_state")
data class StoreIndexStateEntity(
    @PrimaryKey @ColumnInfo(name = "store_id") val storeId: StoreId,
    @ColumnInfo(name = "index_token") val indexToken: String,
    @ColumnInfo(name = "synced_at") val syncedAt: Instant,
    @ColumnInfo(name = "pruning_profile") val pruningProfile: String,
    @ColumnInfo(name = "entry_count") val entryCount: Int = 0,
    @ColumnInfo(name = "catalog_payload") val catalogPayload: String? = null,
)

/**
 * The raw payload of an index entry, as the store published it (already pruned).
 *
 * It serves something that would otherwise be impossible: **applying a merge patch**. A patch
 * describes the differences from the previous document, so the previous document has to exist
 * somewhere. The projected tables are not enough — they are a reading of the payload, not the
 * payload — and rebuilding it from them would be guesswork.
 *
 * It is also what makes changing language free: the projection is redone from here, with nothing
 * re-downloaded.
 */
@Entity(tableName = "store_index_entries", primaryKeys = ["store_id", "entry_id"])
data class StoreIndexEntryEntity(
    @ColumnInfo(name = "store_id") val storeId: StoreId,
    @ColumnInfo(name = "entry_id") val entryId: String,
    val payload: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant,
)

/** A category published by the store, with the name already localised by the store itself. */
@Entity(tableName = "store_categories", primaryKeys = ["store_id", "category_id"])
data class StoreCategoryEntity(
    @ColumnInfo(name = "store_id") val storeId: StoreId,
    @ColumnInfo(name = "category_id") val categoryId: String,
    @ColumnInfo(name = "name_json") val name: com.multistore.core.model.LocalizedText,
    @ColumnInfo(name = "app_count") val appCount: Int = 0,
)

/** An anti-feature published by the store, with name and description already localised. */
@Entity(tableName = "store_anti_features", primaryKeys = ["store_id", "anti_feature_id"])
data class StoreAntiFeatureEntity(
    @ColumnInfo(name = "store_id") val storeId: StoreId,
    @ColumnInfo(name = "anti_feature_id") val antiFeatureId: String,
    @ColumnInfo(name = "name_json") val name: com.multistore.core.model.LocalizedText,
    @ColumnInfo(name = "description_json") val description: com.multistore.core.model.LocalizedText,
)

/**
 * A health event, for local, exportable diagnostics.
 *
 * The table holds two species of row. The first is the original one: what **goes wrong**, always
 * written. The second is **successful** requests, written only with `diagnostics_log_enabled` on —
 * because the most common questions ("why is search so slow") have the shape where the failure
 * log is empty precisely because nothing failed.
 *
 * [detail] and [durationMillis] serve the second kind and stay `null` for the first. They are
 * nullable and without a `DEFAULT`, the only form in which `ALTER TABLE ADD COLUMN` does not ask
 * Room to validate a default declared only in the migration — see the note in `Migrations.kt`.
 */
@Entity(tableName = "health_events")
data class HealthEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "store_id") val storeId: StoreId,
    val kind: String,
    val selector: String? = null,
    @ColumnInfo(name = "snippet_hash") val snippetHash: String? = null,
    /** The escalation rung the request got through on, where relevant. */
    @ColumnInfo(name = "resolver_tier") val resolverTier: Int? = null,
    /** Free text: for a recorded request, the address and the response code. */
    val detail: String? = null,
    /** How long the request took to reach the headers, in milliseconds. */
    @ColumnInfo(name = "duration_ms") val durationMillis: Long? = null,
    val at: Instant,
)

/** Official signers published by the store, where it publishes them (F-Droid: `signer-index.json`). */
@Entity(tableName = "store_official_signers", primaryKeys = ["store_id", "package_name"])
data class StoreOfficialSignerEntity(
    @ColumnInfo(name = "store_id") val storeId: StoreId,
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "signer_sha256") val signerSha256: Sha256,
)

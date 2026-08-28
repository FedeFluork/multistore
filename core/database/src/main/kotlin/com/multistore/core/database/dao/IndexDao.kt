package com.multistore.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.multistore.core.database.entity.StoreAntiFeatureEntity
import com.multistore.core.database.entity.StoreCategoryEntity
import com.multistore.core.database.entity.StoreIndexEntryEntity
import com.multistore.core.database.entity.StoreIndexStateEntity
import com.multistore.core.model.StoreId
import kotlinx.coroutines.flow.Flow

@Dao
interface IndexDao {

    @Query("SELECT * FROM store_index_state WHERE store_id = :storeId")
    suspend fun state(storeId: StoreId): StoreIndexStateEntity?

    @Query("SELECT * FROM store_index_state WHERE store_id = :storeId")
    fun observeState(storeId: StoreId): Flow<StoreIndexStateEntity?>

    @Upsert
    suspend fun upsertState(state: StoreIndexStateEntity)

    @Query("SELECT payload FROM store_index_entries WHERE store_id = :storeId AND entry_id = :entryId")
    suspend fun payload(storeId: StoreId, entryId: String): String?

    /**
     * The payloads of a batch of entries, in a single read.
     *
     * An incremental update has to re-read the "before" of every entry it touches. Doing that with
     * one query per entry means a round trip per package; here it is one per batch.
     */
    @Query("SELECT * FROM store_index_entries WHERE store_id = :storeId AND entry_id IN (:entryIds)")
    suspend fun entries(storeId: StoreId, entryIds: List<String>): List<StoreIndexEntryEntity>

    @Upsert
    suspend fun upsertEntries(entries: List<StoreIndexEntryEntity>)

    @Query("DELETE FROM store_index_entries WHERE store_id = :storeId AND entry_id IN (:entryIds)")
    suspend fun deleteEntries(storeId: StoreId, entryIds: List<String>)

    /**
     * Empties one store's index.
     *
     * Needed before a full sync: in `FULL` mode whatever does not appear in the document **no
     * longer exists**, and without this deletion packages withdrawn from F-Droid would stay in
     * the list forever.
     */
    @Query("DELETE FROM store_index_entries WHERE store_id = :storeId")
    suspend fun clearEntries(storeId: StoreId)

    @Query("SELECT COUNT(*) FROM store_index_entries WHERE store_id = :storeId")
    suspend fun entryCount(storeId: StoreId): Int

    @Query("SELECT entry_id FROM store_index_entries WHERE store_id = :storeId")
    suspend fun entryIds(storeId: StoreId): List<String>

    @Upsert
    suspend fun upsertCategories(categories: List<StoreCategoryEntity>)

    @Query("DELETE FROM store_categories WHERE store_id = :storeId")
    suspend fun clearCategories(storeId: StoreId)

    /**
     * The categories, **most populated first**.
     *
     * The order is part of the contract and not a detail of the query: whoever shows them shows a
     * subset — there are 108 on F-Droid — and alphabetically that subset starts with "AI Chat"
     * and "Alarm Clock" instead of "System" and "Internet". `category_id` as the second criterion
     * makes the order stable between two reads at equal counts.
     */
    @Query("SELECT * FROM store_categories WHERE store_id = :storeId ORDER BY app_count DESC, category_id")
    fun observeCategories(storeId: StoreId): Flow<List<StoreCategoryEntity>>

    @Query("SELECT * FROM store_categories WHERE store_id = :storeId ORDER BY app_count DESC, category_id")
    suspend fun categories(storeId: StoreId): List<StoreCategoryEntity>

    @Upsert
    suspend fun upsertAntiFeatures(antiFeatures: List<StoreAntiFeatureEntity>)

    @Query("DELETE FROM store_anti_features WHERE store_id = :storeId")
    suspend fun clearAntiFeatures(storeId: StoreId)

    /**
     * Throws away **every** store's index: the "clear the downloaded catalogue" button.
     *
     * The three operations go together, and the third is the one nobody sees. Without
     * [clearAllState] the next sync would find a valid `index_token` and ask the store for a
     * **diff** against a document that no longer exists here: it would apply the differences to
     * nothing and declare itself up to date, leaving the catalogue incomplete with no error
     * anywhere.
     */
    @Query("DELETE FROM store_index_entries")
    suspend fun clearAllEntries(): Int

    @Query("DELETE FROM store_index_state")
    suspend fun clearAllState(): Int

    @Transaction
    suspend fun clearAllTaxonomy() {
        clearAllCategories()
        clearAllAntiFeatures()
    }

    @Query("DELETE FROM store_categories")
    suspend fun clearAllCategories()

    @Query("DELETE FROM store_anti_features")
    suspend fun clearAllAntiFeatures()

    @Query("SELECT * FROM store_anti_features WHERE store_id = :storeId")
    suspend fun antiFeatures(storeId: StoreId): List<StoreAntiFeatureEntity>
}

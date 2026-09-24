package com.multistore.core.database.dao

import androidx.room.Dao
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.multistore.core.database.entity.SearchHistoryEntity
import kotlinx.coroutines.flow.Flow

/**
 * The last searches, newest first.
 *
 * Small on purpose. What this exists to remove is retyping a query that costs up to nine requests to
 * other people's sites, and that is answered by the handful somebody actually comes back to; a long
 * record would be a list to scroll rather than a list to tap.
 */
@Dao
interface SearchHistoryDao {

    /**
     * The most recent [limit] searches.
     *
     * A `Flow`, so deleting one entry updates the suggestions under the finger without the screen
     * asking again — and so does the switch in Settings, which clears the table.
     */
    @Query("SELECT * FROM search_history ORDER BY at DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<SearchHistoryEntity>>

    /**
     * Records a search, or moves it back to the top.
     *
     * `REPLACE` on a table whose primary key is the query itself: running the same search twice is
     * one entry with a newer timestamp, not two rows. Without that, the list would fill with the
     * word being refined — `f`, `fi`, `fir` — which is the opposite of recalling anything.
     */
    @Upsert(entity = SearchHistoryEntity::class)
    suspend fun record(entry: SearchHistoryEntity)

    @Query("DELETE FROM search_history WHERE `query` = :query")
    suspend fun delete(query: String)

    @Query("DELETE FROM search_history")
    suspend fun clear()

    /**
     * Drops everything past the ceiling, keeping the newest.
     *
     * The ceiling is enforced on write rather than on read: a table that grew without limit would be
     * a record of everything ever typed, kept for nobody, in a list that only ever shows ten.
     */
    @Query(
        """
        DELETE FROM search_history WHERE `query` NOT IN (
            SELECT `query` FROM search_history ORDER BY at DESC LIMIT :keep
        )
        """,
    )
    suspend fun prune(keep: Int)
}

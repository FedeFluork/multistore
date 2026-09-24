package com.multistore.core.data.repository

import com.multistore.core.common.coroutine.IoDispatcher
import com.multistore.core.database.dao.SearchHistoryDao
import com.multistore.core.database.entity.SearchHistoryEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * The last searches, offered back when the field is empty.
 *
 * ### What it is for, and it is not convenience
 *
 * One aggregated search is up to nine requests to third-party sites. Retyping a query because there
 * was no way to recall it pays that twice, which is the opposite of the courtesy towards the stores
 * this project practises everywhere else — the rate limits honoured, the prefetch refused, the
 * `Crawl-delay` waited out.
 */
interface SearchHistoryRepository {

    /** The most recent searches, newest first. Empty while the record is switched off. */
    fun recent(): Flow<List<String>>

    /**
     * Records a search, unless the user asked for none to be kept.
     *
     * The setting is read **here** and not by the caller, which is the same choice as the adult
     * filter in `SearchRepositoryImpl` and for the same reason: a preference passed in as a
     * parameter is a preference somebody eventually forgets to pass, and forgetting produces no
     * error — only a record the user asked not to have.
     */
    suspend fun record(query: String)

    suspend fun forget(query: String)

    suspend fun clear()

    companion object {
        /**
         * How many to keep, and therefore how many are shown.
         *
         * One number and not two, because a record longer than the list is a record kept for
         * nobody: what this exists to remove is retyping the handful of queries somebody comes back
         * to, and beyond that it becomes a list to scroll rather than a list to tap.
         */
        const val LIMIT: Int = 10
    }
}

@Singleton
internal class SearchHistoryRepositoryImpl @Inject constructor(
    private val dao: SearchHistoryDao,
    private val settings: SettingsRepository,
    private val clock: Clock,
    @IoDispatcher private val io: CoroutineDispatcher,
) : SearchHistoryRepository {

    override fun recent(): Flow<List<String>> =
        dao.observeRecent(SearchHistoryRepository.LIMIT).map { rows -> rows.map { it.query } }

    override suspend fun record(query: String) = withContext(io) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext
        // Read at the moment of writing, not captured when the graph was built: a value taken at
        // startup would make the switch inert until the next launch — the defect already corrected
        // in M3 on the update scheduling and in M4 on the challenge strategy.
        if (!settings.search.first().keepSearchHistory) return@withContext

        // What was **typed**, not the normalised form: it goes back into the text field, and handing
        // back a lowercased, accent-stripped version of somebody's own words would be the app
        // correcting them.
        dao.record(SearchHistoryEntity(query = trimmed, at = clock.now()))
        dao.prune(SearchHistoryRepository.LIMIT)
    }

    override suspend fun forget(query: String) = withContext(io) { dao.delete(query) }

    override suspend fun clear() = withContext(io) { dao.clear() }
}

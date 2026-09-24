package com.multistore.core.data.repository

import com.multistore.core.common.coroutine.IoDispatcher
import com.multistore.core.common.identity.AppKeys
import com.multistore.core.common.identity.IdentityMatch
import com.multistore.core.common.identity.IdentityMatcher
import com.multistore.core.common.result.Outcome
import com.multistore.core.common.text.TextNormalizer
import com.multistore.core.data.mapper.toDiscoveredRows
import com.multistore.core.data.mapper.toSummary
import com.multistore.core.data.store.EnabledStores
import com.multistore.core.data.store.SearchGroupMemory
import com.multistore.core.data.store.StoreRegistry
import com.multistore.core.database.dao.CatalogDao
import com.multistore.core.database.dao.ComparableListing
import com.multistore.core.database.dao.ListingRow
import com.multistore.core.database.entity.IdentityOverrideEntity
import com.multistore.core.model.AggregatedApp
import com.multistore.core.model.AggregatedListing
import com.multistore.core.model.MatchMethod
import com.multistore.core.model.ResultOrigin
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.model.StoreListingSummary
import com.multistore.store.api.HashAvailability
import com.multistore.store.api.SearchFilters
import com.multistore.store.api.StoreAdapter
import com.multistore.store.api.StoreResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * "Available on N stores", and "perhaps here too".
 *
 * ### The line separating the two lists
 *
 * It is not a threshold chosen today: `store_listings.match_confidence` already carries it written
 * from the moment the row was born. A listing enters [CrossStoreAvailability.availableOn] if that
 * confidence reaches [IdentityMatcher.MERGE_THRESHOLD] or if it has been **confirmed by a person**;
 * otherwise it sits among the possibilities.
 *
 * The case making the distinction necessary is already in the database: `AppKeys.inferred` builds an
 * app's key from its normalised title and developer, so two listings from different stores with the
 * same title and **no declared developer** share an `app_key` — while `IdentityMatcher` keeps them at
 * `0.80`, i.e. below the threshold. Trusting the `app_key` alone would mean silently merging exactly
 * what must never be silently merged. The confidence column says the truth already: `0.6` when the
 * key is inferred. It only has to be read.
 *
 * ### The candidates Room already knows
 *
 * Besides the "siblings" there are the listings with a similar title but a different `app_key`. The
 * SQL filter is crude — the normalised title's prefix — and it does not decide: it is
 * [IdentityMatcher] that scores the few rows that come back, in memory.
 */
@Singleton
internal class CrossStoreRepositoryImpl @Inject constructor(
    private val catalogDao: CatalogDao,
    private val details: AppDetailRepository,
    private val registry: StoreRegistry,
    private val enabledStores: EnabledStores,
    private val memory: SearchGroupMemory,
    private val health: StoreHealthRepository,
    private val clock: Clock,
    @IoDispatcher private val io: CoroutineDispatcher,
) : CrossStoreRepository {

    private val lookups = MutableStateFlow<Map<Key, CrossStoreLookup>>(emptyMap())

    /**
     * Which unread listings the comparison is fetching, and which refused.
     *
     * Keyed on `(store, ref)` and **not** on the store: apkmirror publishes one page per variant, so
     * two rows of one comparison can be the same store. Successes are removed rather than recorded —
     * the row's own `listingRead` becomes `true` through Room and says it better than a second copy
     * could.
     */
    private val reads = MutableStateFlow<Map<Key, ListingRead>>(emptyMap())

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observe(storeId: StoreId, ref: StoreAppRef): Flow<CrossStoreAvailability> =
        catalogDao.observeListing(storeId, ref.value)
            .flatMapLatest { rows ->
                val anchor = rows?.listing
                combine(
                    if (anchor != null) {
                        catalogDao.observeSiblings(anchor.appKey, anchor.id)
                    } else {
                        flowOf(emptyList())
                    },
                    if (anchor != null) {
                        catalogDao.observeIdentityOverrides(anchor.appKey)
                    } else {
                        flowOf(emptyList())
                    },
                    memory.observe(storeId, ref),
                    lookups,
                ) { siblings, overrides, remembered, states ->
                    compose(
                        storeId = storeId,
                        ref = ref,
                        anchorSummary = anchor?.toSummary()
                            ?: remembered?.listingFor(storeId, ref)?.summary,
                        anchorAppKey = anchor?.appKey,
                        siblings = siblings,
                        overrides = overrides,
                        remembered = remembered,
                        lookup = states[Key(storeId, ref.value)] ?: CrossStoreLookup.IDLE,
                    )
                }
            }
            .flowOn(io)

    /**
     * The comparison table, built from [observe] and two reads Room already holds.
     *
     * ### Why it is derived from [observe] rather than querying alongside it
     *
     * The set of rows **is** `availableOn`, and `availableOn` is where the 0.85 threshold and the
     * `identity_overrides` exceptions are applied. Re-deriving them here would be a second answer to
     * "is this the same app", and the day the two disagreed the table would invite installing from a
     * listing the rest of the app refuses to merge.
     *
     * The possible matches stay out. A comparison invites picking a row and installing from it, and
     * a row that might be a different app is precisely what this project has decided must never be
     * offered that way.
     *
     * ### The anchor is a row, not a heading
     *
     * "Is this store behind the others" cannot be answered by a table that omits this store. It is
     * marked rather than hidden, and it is the only row whose data does not go through
     * `availableOn` — it comes from `observeListing`, which is also where its versions are.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun compare(storeId: StoreId, ref: StoreAppRef): Flow<StoreComparison> =
        combine(
            catalogDao.observeListing(storeId, ref.value),
            observe(storeId, ref),
            reads,
        ) { anchorRows, availability, readStates ->
            val anchorSummary = anchorRows?.listing?.toSummary()
                ?: return@combine StoreComparison(
                    otherStoresUnexplored = availability.unexploredStores,
                )

            // One query for every row rather than one per row: nine listings would otherwise be nine
            // round trips to answer a screen that draws in one frame.
            //
            // Keyed on `(store, ref)` and not on the listing id, because a row cross-store matching
            // found in the **last search's results** has no id — and that row is exactly the one
            // this table now reads. See `comparableListings`.
            val refs = (listOf(ref.value) + availability.availableOn.map { it.ref.value }).distinct()
            val extras = catalogDao.comparableListings(refs).associateBy { Key(it.storeId, it.ref) }

            val anchor = row(
                summary = anchorSummary,
                current = true,
                extra = extras[Key(storeId, ref.value)],
                read = readStates[Key(storeId, ref.value)] ?: ListingRead.IDLE,
            )
            val others = availability.availableOn.map { other ->
                row(
                    summary = other.listing.summary,
                    current = false,
                    extra = extras[Key(other.storeId, other.ref.value)],
                    read = readStates[Key(other.storeId, other.ref.value)] ?: ListingRead.IDLE,
                )
            }

            StoreComparison(
                rows = listOf(anchor) + others.sortedBy { it.storeId.ordinal },
                otherStoresUnexplored = availability.unexploredStores,
                title = anchorSummary.title,
            )
        }.flowOn(io)

    /**
     * One row, from what the listing says and what the adapter declares.
     *
     * [extra] is `null` for a listing that only the last search has seen: it has no id, so it has no
     * row in `store_listings` to read a size off. That is exactly the case [StoreComparisonRow
     * .listingRead] exists to keep separate from "this store publishes no size", and it is why the
     * `false` here is a default and not an oversight.
     */
    private fun row(
        summary: StoreListingSummary,
        current: Boolean,
        extra: ComparableListing?,
        read: ListingRead = ListingRead.IDLE,
    ): StoreComparisonRow = StoreComparisonRow(
        storeId = summary.storeId,
        ref = summary.ref,
        current = current,
        versionName = summary.latestVersionName,
        versionCode = summary.latestVersionCode,
        lastUpdated = summary.lastUpdated,
        sizeBytes = extra?.sizeBytes,
        // The adapter's own declaration, and the only cell that is known even for a listing nobody
        // has opened. It is also verified: the contract test compares it against how many hashes the
        // real fixtures carry.
        hashAvailability = registry.adapter(summary.storeId)?.capabilities?.providesHash
            ?: HashAvailability.NONE,
        packageName = summary.packageName,
        modifiedBuild = registry.modifiedBuildOf(summary.storeId, summary.declaredModified),
        listingRead = extra?.listingRead == true,
        read = read,
    )

    override suspend fun lookUp(storeId: StoreId, ref: StoreAppRef) {
        val key = Key(storeId, ref.value)
        if (lookups.value[key] == CrossStoreLookup.RUNNING) return
        lookups.update { it + (key to CrossStoreLookup.RUNNING) }
        try {
            withContext(io) { probeOtherStores(storeId, ref) }
        } finally {
            lookups.update { it + (key to CrossStoreLookup.DONE) }
        }
    }

    override suspend fun readListings(storeId: StoreId, ref: StoreAppRef) {
        // The rows are taken from the comparison itself rather than re-derived, for the reason
        // written on `compare`: `availableOn` is where the 0.85 threshold and the overrides are
        // applied, and a second answer to "is this the same app" is one that can disagree.
        val comparison = compare(storeId, ref).first()
        val targets = comparison.rows
            .filterNot { it.listingRead }
            .map { Key(it.storeId, it.ref.value) }
            // A row whose read is already in flight — a second subscriber, a rotation — is not asked
            // again. A row that already failed is not retried on its own either: the reader has the
            // card's own button for that, and a screen that silently re-knocked on a door that just
            // said no would be doing it once per recomposition.
            .filter { reads.value[it] == null }
        if (targets.isEmpty()) return

        reads.update { current -> current + targets.associateWith { ListingRead.RUNNING } }
        withContext(io) {
            coroutineScope {
                targets.map { key ->
                    async {
                        // `force = false`: a row can be unread here and still be inside its TTL —
                        // discovered rows carry `ttl_seconds = 0` and are therefore always stale, so
                        // in practice this fetches exactly the rows that need it, and `refresh`
                        // remains the one place that decides what stale means.
                        val outcome = details.refresh(key.storeId, StoreAppRef(key.ref))
                        reads.update { current ->
                            // Success **removes** the entry instead of writing a third state: the row
                            // now has versions, so `listingRead` says so through Room. Two facts, one
                            // of them derived, cannot then drift apart.
                            if (outcome is Outcome.Success) {
                                current - key
                            } else {
                                current + (key to ListingRead.FAILED)
                            }
                        }
                    }
                }.awaitAll()
            }
        }
    }

    override suspend fun confirm(
        anchor: StoreId,
        anchorRef: StoreAppRef,
        candidateListingId: Long,
    ) = withContext(io) {
        val appKey = catalogDao.listingIdentity(anchor, anchorRef.value)?.appKey ?: return@withContext
        // The identity rewrite first, then recording the choice: if the process dies in between, the
        // worst that remains is a correct match with no trace of who decided it — the opposite order
        // would leave a recorded choice that moved nothing, i.e. a button the user pressed with no
        // effect.
        catalogDao.reassignListing(
            listingId = candidateListingId,
            appKey = appKey,
            confidence = IdentityMatch.CONFIRMED.confidence,
            method = MatchMethod.USER_CONFIRMED,
        )
        catalogDao.upsertIdentityOverride(
            IdentityOverrideEntity(candidateListingId, appKey, ACTION_CONFIRM),
        )
    }

    override suspend fun reject(
        anchor: StoreId,
        anchorRef: StoreAppRef,
        candidateListingId: Long,
    ) = withContext(io) {
        val appKey = catalogDao.listingIdentity(anchor, anchorRef.value)?.appKey ?: return@withContext
        // A rejection **does not** rewrite `app_key`, and must not: the rejected listing stays what
        // it was — if it shared the key by inference, that inference is still worth what it was
        // worth. All that changes is that it will not be proposed for this app again.
        catalogDao.upsertIdentityOverride(
            IdentityOverrideEntity(candidateListingId, appKey, ACTION_REJECT),
        )
    }

    private suspend fun compose(
        storeId: StoreId,
        ref: StoreAppRef,
        anchorSummary: StoreListingSummary?,
        anchorAppKey: String?,
        siblings: List<ListingRow>,
        overrides: List<IdentityOverrideEntity>,
        remembered: AggregatedApp?,
        lookup: CrossStoreLookup,
    ): CrossStoreAvailability {
        if (anchorSummary == null) return CrossStoreAvailability(lookup = lookup)

        val rejected = overrides.filter { it.action == ACTION_REJECT }.mapTo(mutableSetOf()) { it.listingId }
        val appKey = anchorAppKey
            ?: AppKeys.of(anchorSummary.packageName, anchorSummary.title, anchorSummary.developer)

        val merged = mutableMapOf<Key, StoreAvailability>()
        val possible = mutableMapOf<Key, StoreAvailability>()

        for (row in siblings) {
            val entry = row.toAvailability()
            val target = if (entry.listing.confidence >= IdentityMatcher.MERGE_THRESHOLD) merged else possible
            if (row.listing.id in rejected && target === possible) continue
            target[entry.key] = entry
        }

        // What the last search saw: no request, and it covers the main path — one almost always
        // arrives at the listing from a search result.
        remembered?.listings.orEmpty()
            .filter { it.storeId != storeId || it.ref != ref }
            .forEach { listing ->
                val entry = StoreAvailability(listing)
                val target = if (listing.confidence >= IdentityMatcher.MERGE_THRESHOLD) merged else possible
                if (entry.key !in merged && entry.key !in possible) target[entry.key] = entry
            }

        val prefix = titlePrefix(anchorSummary.title)
        if (prefix != null) {
            catalogDao.listingsWithSimilarTitle(prefix, appKey)
                .asSequence()
                // Another listing from the **same** store is not a cross-store match: it is another
                // page, and offering it as "perhaps it is the same app" would help nobody choose
                // where to install from.
                .filter { it.listing.storeId != storeId }
                .filter { it.listing.id !in rejected }
                .map { it to IdentityMatcher.compare(anchorSummary, it.toSummary()) }
                .filter { (_, match) -> match.isCandidate || match.merges }
                .sortedByDescending { (_, match) -> match.confidence }
                .forEach { (row, match) ->
                    val entry = row.toAvailability(match)
                    if (entry.key in merged || entry.key in possible) return@forEach
                    if (possible.size >= CrossStoreRepository.MAX_CANDIDATES) return@forEach
                    possible[entry.key] = entry
                }
        }

        val represented = (merged.keys + possible.keys).mapTo(mutableSetOf()) { it.storeId } + storeId
        val unexplored = enabledStores.adapters().count { it.id !in represented }

        return CrossStoreAvailability(
            availableOn = merged.values.sortedBy { it.storeId.ordinal },
            possibleMatches = possible.values.sortedByDescending { it.listing.confidence },
            lookup = lookup,
            unexploredStores = unexplored,
        )
    }

    /**
     * Asks the stores that have not yet spoken, and writes what it finds.
     *
     * Discovered listings are born **already expired** (`ttl_seconds = 0`): what was read is a list
     * of results, not a listing, and opening it must re-read it from the store. The existing
     * stale-while-revalidate notices by itself.
     */
    private suspend fun probeOtherStores(storeId: StoreId, ref: StoreAppRef) {
        val rows = catalogDao.listing(storeId, ref.value)
        val anchorSummary = rows?.listing?.toSummary()
            ?: memory.snapshot(storeId, ref)?.listingFor(storeId, ref)?.summary
            ?: return
        val appKey = rows?.listing?.appKey
            ?: AppKeys.of(anchorSummary.packageName, anchorSummary.title, anchorSummary.developer)

        val known = buildSet {
            add(storeId)
            rows?.let { catalogDao.siblings(it.listing.appKey, it.listing.id) }
                ?.forEach { add(it.listing.storeId) }
        }
        val targets = enabledStores.adapters().filter { it.id !in known }
        if (targets.isEmpty()) return

        val found = coroutineScope {
            targets.map { adapter -> async { probe(adapter, anchorSummary) } }.awaitAll()
        }.filterNotNull()

        val now = clock.now()
        for ((summary, match) in found) {
            val key = if (match.merges) {
                appKey
            } else {
                AppKeys.of(summary.packageName, summary.title, summary.developer)
            }
            val discovered = summary.toDiscoveredRows(key, match.confidence, match.method, now)
            catalogDao.upsertApps(listOf(discovered.app))
            catalogDao.insertListingIfAbsent(discovered.listing)
        }
    }

    /**
     * The best result **one** store has to offer for this app, if it has one.
     *
     * The search uses the title exactly as the originating store writes it, not the normalised form:
     * normalisation serves to **compare** titles, not to search for them, and stripping the capitals
     * or the punctuation from a query makes it worse on every measured store.
     */
    private suspend fun probe(
        adapter: StoreAdapter,
        anchor: StoreListingSummary,
    ): Pair<StoreListingSummary, IdentityMatch>? {
        if (!health.canAttempt(adapter.id)) return null
        val result = withTimeoutOrNull(STORE_TIMEOUT) {
            adapter.search(anchor.title, SearchFilters.NONE, page = 0)
        } ?: return null

        return when (result) {
            is StoreResult.Success -> {
                health.recordSuccess(adapter.id)
                result.value.items
                    .map { it to IdentityMatcher.compare(anchor, it) }
                    .filter { (_, match) -> match.isCandidate || match.merges }
                    .maxByOrNull { (_, match) -> match.confidence }
            }

            is StoreResult.Failure -> {
                health.recordFailure(adapter.id, result.error)
                null
            }

            StoreResult.Unsupported -> null
        }
    }

    private fun ListingRow.toAvailability(match: IdentityMatch? = null) = StoreAvailability(
        listing = AggregatedListing(
            summary = toSummary(),
            origin = ResultOrigin.REMOTE,
            confidence = match?.confidence ?: listing.matchConfidence,
            method = match?.method ?: listing.matchMethod,
        ),
        listingId = listing.id,
    )

    private fun AggregatedApp.listingFor(storeId: StoreId, ref: StoreAppRef): AggregatedListing? =
        listings.firstOrNull { it.storeId == storeId && it.ref == ref }

    private val StoreAvailability.key: Key get() = Key(storeId, ref.value)

    private data class Key(val storeId: StoreId, val ref: String)

    private companion object {
        const val ACTION_CONFIRM = "CONFIRM"
        const val ACTION_REJECT = "REJECT"

        /**
         * How many characters of title are enough to fish the candidates out of Room.
         *
         * Four, and deliberately few: `duckduckgo` and `duck duck go` both fall under `duck`, and
         * that is exactly the pair a comparison of whole titles would miss. The decision is not made
         * by this filter but by the matcher, on the few rows that come back.
         */
        const val TITLE_PREFIX_CHARS = 4

        val STORE_TIMEOUT: Duration = 8.seconds

        fun titlePrefix(title: String): String? =
            TextNormalizer.normalizeTitle(title).take(TITLE_PREFIX_CHARS).takeIf { it.isNotBlank() }
    }
}

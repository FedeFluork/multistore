package com.multistore.core.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.multistore.core.data.FakeIndexedStoreAdapter
import com.multistore.core.data.FakeIndexedStoreAdapter.Companion.FIELD_CONTENT_KIND
import com.multistore.core.data.FakeIndexedStoreAdapter.Companion.FIELD_TITLE
import com.multistore.core.data.FakeSnapshot
import com.multistore.core.data.store.EnabledStores
import com.multistore.core.data.store.SearchGroupMemory
import com.multistore.core.data.store.StoreRegistry
import com.multistore.core.database.MultiStoreDatabase
import com.multistore.core.model.ContentKind
import com.multistore.core.model.SearchSort
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.model.StoreListingSummary
import com.multistore.store.api.FilterCapability
import com.multistore.store.api.IndexRecord
import com.multistore.store.api.IndexSyncMode
import com.multistore.store.api.IndexToken
import com.multistore.store.api.PagedResult
import com.multistore.store.api.SearchFilters
import com.multistore.store.api.SearchSource
import com.multistore.store.api.StoreResult
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The filters through the fan-out: who applies them, who stays out, and who says so.
 *
 * It is the part `FilterPlanTest` cannot prove: that one checks the **decision** on a capability,
 * this checks what happens to the search — which stores go out, which rows come back, what appears in
 * the sign. The difference shows in the first test: a plan classifying correctly and querying the
 * store anyway would pass there and fail here.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SearchFilterTest {

    private lateinit var db: MultiStoreDatabase

    private val clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(1_787_316_712_615L)
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MultiStoreDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    // --- The third rung: not queried, and it is written down --------------------------------

    /**
     * A store that cannot apply the filter **receives no request**.
     *
     * The assert on the requests is the one that counts: discarding its rows after receiving them
     * would give the same list plus a request to a third-party site for nothing. Across nine stores,
     * one active filter would become eight useless requests per search.
     */
    @Test
    fun `a store that cannot filter is not queried`() = runTest {
        val incapable = remoteStore(StoreId.APKMIRROR, "Solitario")
        val repository = build(setOf(incapable))

        val page = repository.search("solitario", filters = SearchFilters(contentKind = ContentKind.GAME))

        assertThat(incapable.searchedFor).isEmpty()
        assertThat(page.apps).isEmpty()
        assertThat(page.shortfalls.single().unsupportedFilters)
            .containsExactly(FilterCapability.CONTENT_KIND)
    }

    /**
     * And it writes it **from the first emission**, not at the end.
     *
     * It is the moment the user wonders where a store has gone, and it is also the only one in which
     * the search has nothing to show yet. A sign appearing together with the results would arrive
     * after the question.
     */
    @Test
    fun `the exclusion appears already in the first emission`() = runTest {
        val capable = remoteStore(StoreId.APKMODY, "Solitario", kind = ContentKind.GAME)
            .withClientFilters(FilterCapability.CONTENT_KIND)
        val incapable = remoteStore(StoreId.APKMIRROR, "Solitario")
        val repository = build(setOf(capable, incapable))

        val firstEmission = repository
            .searchStreaming("solitario", filters = SearchFilters(contentKind = ContentKind.GAME))
            .first()

        assertThat(firstEmission.answered).isEmpty()
        // The excluded one does not count among the "queried" stores: nothing was asked of it, and
        // counting it would make the screen write "1 store out of 2" to somebody waiting for one.
        assertThat(firstEmission.pending).containsExactly(StoreId.APKMODY)
        assertThat(firstEmission.page.shortfalls.single().storeId).isEqualTo(StoreId.APKMIRROR)
    }

    @Test
    fun `with no active filters nobody is excluded`() = runTest {
        val incapable = remoteStore(StoreId.APKMIRROR, "Solitario")
        val repository = build(setOf(incapable))

        val page = repository.search("solitario")

        assertThat(incapable.searchedFor).containsExactly("solitario")
        assertThat(page.shortfalls).isEmpty()
    }

    /**
     * The adult filter **does not** exclude anybody, and it is the exception that needs proving.
     *
     * Its active value is also the default: governing it like the others would mean that every normal
     * search, by anybody who has never opened Settings, queries the only store that labels adult
     * content.
     */
    @Test
    fun `the adult filter removes no store from the search`() = runTest {
        val incapable = remoteStore(StoreId.APKMIRROR, "Solitario")
        val repository = build(setOf(incapable))

        val page = repository.search("solitario", filters = SearchFilters(includeNsfw = false))

        assertThat(incapable.searchedFor).containsExactly("solitario")
        assertThat(page.shortfalls).isEmpty()
    }

    // --- The second tier: query it, and discard here -----------------------------------------

    @Test
    fun `whoever carries the field on every row is queried and filtered here`() = runTest {
        val store = FakeIndexedStoreAdapter(
            StoreId.APKMODY,
            source = SearchSource.REMOTE,
            clientFilters = setOf(FilterCapability.CONTENT_KIND),
        ).apply {
            searchResults = StoreResult.Success(
                PagedResult.single(
                    listOf(
                        summary(StoreId.APKMODY, "Solitario", kind = ContentKind.GAME),
                        summary(StoreId.APKMODY, "Calculator", kind = ContentKind.APP),
                    ),
                ),
            )
        }
        val repository = build(setOf(store))

        val page = repository.search("a", filters = SearchFilters(contentKind = ContentKind.GAME))

        assertThat(store.searchedFor).containsExactly("a")
        assertThat(page.apps.map { it.displaySummary.title }).containsExactly("Solitario")
        assertThat(page.shortfalls).isEmpty()
    }

    // --- The first rung: the local index, which finally applies what it declares -------------

    /**
     * F-Droid has declared `CONTENT_KIND` from the start and until recently nobody applied it.
     *
     * The capability said "the local index applies them", and `localSearch` received the filters
     * without using them: the SQL query did not even have them as arguments. It was the third side of
     * a triangle with two sides — capability declared, UI adapting to it, and nothing in between.
     */
    @Test
    fun `the local index applies the filter in the query`() = runTest {
        val indexed = FakeIndexedStoreAdapter(
            StoreId.FDROID,
            supportedFilters = setOf(FilterCapability.CONTENT_KIND),
        )
        val repository = build(setOf(indexed))
        populate(
            indexed,
            "org.example.solitario" to ContentKind.GAME,
            "org.example.calculator" to ContentKind.APP,
        )

        val games = repository.search("org", filters = SearchFilters(contentKind = ContentKind.GAME))

        assertThat(games.apps.map { it.displaySummary.title }).containsExactly("org.example.solitario")
        assertThat(games.shortfalls).isEmpty()
        // The index answers with no network: the fallback search must not have been used.
        assertThat(indexed.searchedFor).isEmpty()
    }

    /**
     * The same store, **with no index**, ends up in the third rung.
     *
     * The capability does not change; what answers is the fallback search, which accepts no filters.
     * Trusting the capability alone would announce here a filter nobody applies.
     */
    @Test
    fun `the same store with no index cannot filter, and says so`() = runTest {
        val indexed = FakeIndexedStoreAdapter(
            StoreId.FDROID,
            supportedFilters = setOf(FilterCapability.CONTENT_KIND),
        )
        val repository = build(setOf(indexed))

        val page = repository.search("org", filters = SearchFilters(contentKind = ContentKind.GAME))

        assertThat(indexed.searchedFor).isEmpty()
        assertThat(page.shortfalls.single().unsupportedFilters)
            .containsExactly(FilterCapability.CONTENT_KIND)
    }

    /** The pagination count has to see the same filters as the page. */
    @Test
    fun `the index's filter applies to the count too`() = runTest {
        val indexed = FakeIndexedStoreAdapter(
            StoreId.FDROID,
            supportedFilters = setOf(FilterCapability.CONTENT_KIND),
        )
        val repository = build(setOf(indexed))
        populate(indexed, *(1..25).map { "org.example.app$it" to ContentKind.APP }.toTypedArray())

        val games = repository.search("org", filters = SearchFilters(contentKind = ContentKind.GAME))

        // Zero results and **no** "more results": with an unfiltered count, `hasMore` would say yes
        // and the screen would offer an empty second page.
        assertThat(games.apps).isEmpty()
        assertThat(games.hasMore).isFalse()
    }

    // --- The ordering, which applies to the aggregate ---------------------------------------

    /**
     * Ordering by name, tested **against** the order the aggregator would give by itself.
     *
     * The two packages are chosen precisely so that the natural order is the wrong one: without that,
     * the test would stay green even with the ordering removed. `AppAggregator` puts groups with equal
     * scores in `appKey` order, so with packages derived from the titles the list would come out
     * alphabetical already and there would be nothing to prove — exactly the case in which a test
     * becomes a caption. A green injection said so.
     */
    @Test
    fun `ordering by name applies across all the stores together`() = runTest {
        val one = remoteStore(StoreId.APKMIRROR, "Zebra", packageName = "com.example.aaa")
        val two = remoteStore(StoreId.UPTODOWN, "Ancora", packageName = "com.example.zzz")
        val repository = build(setOf(one, two))

        val unsorted = repository.search("a")
        // The natural order is the opposite: it is what makes the following assert a proof.
        assertThat(unsorted.apps.map { it.displaySummary.title }).containsExactly("Zebra", "Ancora").inOrder()

        val byName = repository.search("a", filters = SearchFilters(sort = SearchSort.NAME))

        assertThat(byName.apps.map { it.displaySummary.title }).containsExactly("Ancora", "Zebra").inOrder()
    }

    /**
     * Whoever has no rating goes **to the bottom**, not to zero.
     *
     * Five stores out of nine do not publish a rating: treating the absence as "zero stars" would say
     * something about those apps that nobody said, and would put them below an app judged terrible by
     * whoever does publish ratings.
     */
    @Test
    fun `ordering by rating puts whoever has no rating at the bottom`() = runTest {
        val rated = remoteStore(StoreId.AN1, "Media", rating = 3.0f)
        val better = remoteStore(StoreId.PDALIFE, "Ottima", rating = 4.8f)
        // A store that does publish the rating and says **zero**, and one that does not publish it at
        // all. They are the two cases a `?: 0f` would confuse, and it is the only configuration in
        // which the difference between the two is visible: without the zero row, any fallback value
        // between minus infinity and zero would give the same order.
        //
        // The packages are chosen so that the natural order puts "Without rating" **before** "Bocciata":
        // `sortedByDescending` is stable, so with a fallback of zero the two rows would tie and stay
        // as they are — i.e. the test would pass anyway. This too was said by a green injection.
        val zero = remoteStore(StoreId.LITEAPKS, "Bocciata", rating = 0f, packageName = "com.example.zzz")
        val unrated = remoteStore(StoreId.APKMIRROR, "Without rating", packageName = "com.example.aaa")
        val repository = build(setOf(rated, better, zero, unrated))

        val byRating = repository.search("a", filters = SearchFilters(sort = SearchSort.RATING))

        assertThat(byRating.apps.map { it.displaySummary.title })
            .containsExactly("Ottima", "Media", "Bocciata", "Without rating").inOrder()
    }

    /**
     * A row read from the catalogue carries the type the store had declared.
     *
     * Before migration 3 → 4 the type lived only in `apps`, which has one row per aggregated app and
     * is written with an `@Upsert`: the listing of one of the eight stores that do not publish the
     * type deleted the one F-Droid had written for the same package. And
     * `StoreListingEntity.toSummary()` did not read it at all, so every catalogue row came out
     * `UNKNOWN` — the Home, "browse" and F-Droid's search included.
     */
    @Test
    fun `a catalogue row carries the type the store declared`() = runTest {
        val indexed = FakeIndexedStoreAdapter(StoreId.FDROID)
        val repository = build(setOf(indexed))
        populate(indexed, "org.example.solitario" to ContentKind.GAME)

        val page = repository.search("solitario")

        assertThat(page.apps.single().displaySummary.contentKind).isEqualTo(ContentKind.GAME)
    }

    // --- Scaffolding -------------------------------------------------------------------------

    private fun build(adapters: Set<FakeIndexedStoreAdapter>): SearchRepositoryImpl {
        val registry = StoreRegistry(adapters)
        val health = StoreHealthRepositoryImpl(registry, db.storeDao(), clock, Dispatchers.Unconfined)
        return SearchRepositoryImpl(
            registry = registry,
            enabledStores = EnabledStores(registry, db.storeDao()),
            settings = LocalSettings(),
            memory = SearchGroupMemory(),
            catalogDao = db.catalogDao(),
            indexDao = db.indexDao(),
            health = health,
            clock = clock,
            io = Dispatchers.Unconfined,
        )
    }

    private suspend fun populate(
        adapter: FakeIndexedStoreAdapter,
        vararg entries: Pair<String, ContentKind>,
    ) {
        val registry = StoreRegistry(setOf(adapter))
        val health = StoreHealthRepositoryImpl(registry, db.storeDao(), clock, Dispatchers.Unconfined)
        val index = StoreIndexRepositoryImpl(
            registry = registry,
            indexDao = db.indexDao(),
            catalogDao = db.catalogDao(),
            health = health,
            clock = clock,
            io = Dispatchers.Unconfined,
        )
        val records = entries.map { (id, kind) ->
            val payload = FakeIndexedStoreAdapter.payload(
                id,
                FIELD_TITLE to id,
                FIELD_CONTENT_KIND to kind.name,
            )
            IndexRecord.Full(StoreAppRef(id), payload, adapter.projectEntry(payload))
        }
        adapter.nextSnapshot = {
            StoreResult.Success(FakeSnapshot(IndexToken("1"), IndexSyncMode.FULL, records))
        }
        index.sync(adapter.id)
    }

    private fun remoteStore(
        id: StoreId,
        title: String,
        kind: ContentKind = ContentKind.UNKNOWN,
        rating: Float? = null,
        packageName: String? = null,
    ) = FakeIndexedStoreAdapter(id, source = SearchSource.REMOTE).apply {
        searchResults = StoreResult.Success(
            PagedResult.single(listOf(summary(id, title, kind, rating, packageName))),
        )
    }

    private fun FakeIndexedStoreAdapter.withClientFilters(
        vararg filters: FilterCapability,
    ): FakeIndexedStoreAdapter {
        val replacement = FakeIndexedStoreAdapter(
            id,
            source = SearchSource.REMOTE,
            clientFilters = filters.toSet(),
        )
        replacement.searchResults = searchResults
        return replacement
    }

    private fun summary(
        storeId: StoreId,
        title: String,
        kind: ContentKind = ContentKind.UNKNOWN,
        rating: Float? = null,
        packageName: String? = null,
    ) = StoreListingSummary(
        storeId = storeId,
        ref = StoreAppRef("$title-${storeId.wireName}"),
        title = title,
        // Distinct packages: without them, two listings with the same title and no publisher would end
        // up in the same group and the ordering tests would have a single row. Whoever tests an
        // ordering passes it explicitly, to decide the starting order: `AppAggregator` puts groups
        // with equal scores in `appKey` order.
        packageName = packageName
            ?: "com.example.${title.lowercase().replace(" ", "")}.${storeId.wireName}",
        contentKind = kind,
        rating = rating,
    )
}

package com.multistore.core.data.repository

import androidx.paging.PagingData
import androidx.paging.testing.asSnapshot
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.multistore.core.common.net.CircuitBreakerPolicy
import com.multistore.core.data.FakeIndexedStoreAdapter
import com.multistore.core.data.FakeIndexedStoreAdapter.Companion.FIELD_TITLE
import com.multistore.core.data.FakeSnapshot
import com.multistore.core.data.store.EnabledStores
import com.multistore.core.data.store.SearchGroupMemory
import com.multistore.core.data.store.StoreRegistry
import com.multistore.core.database.MultiStoreDatabase
import com.multistore.core.model.ResultOrigin
import com.multistore.core.data.mapper.toRows
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.model.StoreListingDetail
import com.multistore.core.model.StoreListingSummary
import com.multistore.store.api.IndexRecord
import com.multistore.store.api.IndexSyncMode
import com.multistore.store.api.IndexToken
import com.multistore.store.api.PagedResult
import com.multistore.store.api.SearchFilters
import com.multistore.store.api.SearchSource
import com.multistore.store.api.StoreError
import com.multistore.store.api.StoreResult
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The aggregated search and its three cases.
 *
 * The case that really deserves a test is the third: a local-index store whose index **has not yet
 * arrived**. It is the window between the first launch and the end of the first sync, it lasts
 * minutes, and treating it as "no results" would tell the user the app they are looking for does not
 * exist.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SearchRepositoryTest {

    private lateinit var db: MultiStoreDatabase
    private lateinit var indexed: FakeIndexedStoreAdapter
    private lateinit var health: StoreHealthRepositoryImpl
    private lateinit var index: StoreIndexRepositoryImpl
    private lateinit var repository: SearchRepositoryImpl
    private var settings = LocalSettings()

    private var currentTime = Instant.fromEpochMilliseconds(1_787_316_712_615L)
    private val clock = object : Clock {
        override fun now(): Instant = currentTime
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MultiStoreDatabase::class.java,
        ).allowMainThreadQueries().build()
        indexed = FakeIndexedStoreAdapter(StoreId.FDROID)
        build(setOf(indexed))
    }

    private fun build(adapters: Set<FakeIndexedStoreAdapter>) {
        val registry = StoreRegistry(adapters)
        health = StoreHealthRepositoryImpl(registry, db.storeDao(), clock, Dispatchers.Unconfined)
        index = StoreIndexRepositoryImpl(
            registry = registry,
            indexDao = db.indexDao(),
            catalogDao = db.catalogDao(),
            health = health,
            clock = clock,
            io = Dispatchers.Unconfined,
        )
        repository = SearchRepositoryImpl(
            registry = registry,
            enabledStores = EnabledStores(registry, db.storeDao()),
            settings = settings,
            memory = SearchGroupMemory(),
            catalogDao = db.catalogDao(),
            indexDao = db.indexDao(),
            health = health,
            clock = clock,
            io = Dispatchers.Unconfined,
        )
    }

    @After
    fun tearDown() = db.close()

    private suspend fun populateIndex() {
        val records = listOf("org.example.tor" to "Tor Browser", "org.example.calc" to "Calculator")
            .map { (id, title) ->
                val payload = FakeIndexedStoreAdapter.payload(id, FIELD_TITLE to title)
                IndexRecord.Full(StoreAppRef(id), payload, indexed.projectEntry(payload))
            }
        indexed.nextSnapshot = {
            StoreResult.Success(FakeSnapshot(IndexToken("1"), IndexSyncMode.FULL, records))
        }
        index.sync(StoreId.FDROID)
    }

    @Test
    fun `index populated - the results come from Room and the network is untouched`() = runTest {
        populateIndex()

        val page = repository.search("tor")

        // "Calculator" contains "tor" as much as "Tor Browser" does: the search is by substring, and
        // finding both is correct. What has to be right is the order, and it has a test of its own.
        assertThat(page.apps.map { it.primary.summary.title })
            .containsExactly("Tor Browser", "Calculator")
        assertThat(page.apps.flatMap { app -> app.listings.map { it.origin } }).containsExactly(
            ResultOrigin.LOCAL_INDEX, ResultOrigin.LOCAL_INDEX,
        )
        assertThat(page.shortfalls).isEmpty()
        assertThat(indexed.searchedFor).isEmpty()
    }

    @Test
    fun `whoever starts with the searched term comes first`() = runTest {
        populateIndex()

        // "Calculator" contains "tor"; "Tor Browser" starts with "tor". Whoever types "tor" wants the
        // second, and a LIKE with no ordering would give it to them last.
        val titles = repository.search("tor").apps.map { it.primary.summary.title }

        assertThat(titles.first()).isEqualTo("Tor Browser")
    }

    @Test
    fun `index not yet downloaded - it falls back on the remote search, and says so`() = runTest {
        indexed.searchResults = StoreResult.Success(
            PagedResult.single(
                listOf(
                    StoreListingSummary(
                        storeId = StoreId.FDROID,
                        ref = StoreAppRef("org.example.tor"),
                        title = "Tor Browser",
                    ),
                ),
            ),
        )

        val page = repository.search("tor")

        assertThat(indexed.searchedFor).containsExactly("tor")
        assertThat(page.apps.single().primary.origin).isEqualTo(ResultOrigin.BOOTSTRAP)
        // Ten truncated results with no version are not the catalogue: the screen has to be able to
        // say so, so the shortfall is declared even when the results are there.
        assertThat(page.shortfalls.single().partial).isTrue()
    }

    @Test
    fun `a row with no icon takes it from the catalogue, if the catalogue has it`() = runTest {
        // The real case is apkmody, which publishes **no** icon in its results: its card image is a
        // cover, eighteen times out of twenty a placeholder. Opening the listing does write the
        // catalogue, icon included — and until 27/08/2026 that row went on showing the placeholder
        // afterwards too, measured on the device.
        seedListing(ref = "apps/spotify-x", title = "Spotify X", iconUrl = ICON)
        indexed.searchResults = StoreResult.Success(
            PagedResult.single(
                listOf(
                    StoreListingSummary(
                        storeId = StoreId.FDROID,
                        ref = StoreAppRef("apps/spotify-x"),
                        title = "Spotify X",
                        iconUrl = null,
                    ),
                ),
            ),
        )

        val page = repository.search("spotify")

        assertThat(page.apps.single().primary.summary.iconUrl).isEqualTo(ICON)
    }

    @Test
    fun `with nothing in the catalogue the row stays icon-less, and nobody goes to ask for it`() =
        runTest {
            // The other half, and it is not symmetry: the defence is **not** making a request. Going
            // to fetch the real icon would mean opening every icon-less row's page on a third-party
            // site, i.e. the prefetch this project forbids.
            indexed.searchResults = StoreResult.Success(
                PagedResult.single(
                    listOf(
                        StoreListingSummary(
                            storeId = StoreId.FDROID,
                            ref = StoreAppRef("org.example.mai.visto"),
                            title = "Mai visto",
                            iconUrl = null,
                        ),
                    ),
                ),
            )

            val page = repository.search("mai")

            assertThat(page.apps.single().primary.summary.iconUrl).isNull()
        }

    @Test
    fun `breaker open - the remote store is not even queried`() = runTest {
        val remote = FakeIndexedStoreAdapter(StoreId.APKMIRROR, source = SearchSource.REMOTE)
        build(setOf(remote))
        health.recordFailure(StoreId.APKMIRROR, StoreError.RateLimited(retryAfter = null))

        val page = repository.search("tor")

        assertThat(remote.searchedFor).isEmpty()
        assertThat(page.shortfalls.single().circuitOpen).isTrue()
    }

    @Test
    fun `once the opening has passed, the store is retried`() = runTest {
        val remote = FakeIndexedStoreAdapter(StoreId.APKMIRROR, source = SearchSource.REMOTE)
        build(setOf(remote))
        health.recordFailure(StoreId.APKMIRROR, StoreError.RateLimited(retryAfter = null))

        currentTime += CircuitBreakerPolicy.INITIAL_OPEN + kotlin.time.Duration.parse("1s")
        repository.search("tor")

        assertThat(remote.searchedFor).containsExactly("tor")
    }

    @Test
    fun `a store that fails becomes a declared shortfall, not an exception`() = runTest {
        val remote = FakeIndexedStoreAdapter(StoreId.APKMIRROR, source = SearchSource.REMOTE)
        remote.searchResults = StoreResult.Failure(StoreError.Network(cause = null, httpCode = 503))
        build(setOf(remote))

        val page = repository.search("tor")

        assertThat(page.apps).isEmpty()
        assertThat(page.shortfalls.single().error).isNotNull()
        // And the failure is counted by the breaker: the next search knows that store is limping.
        assertThat(health.health(StoreId.APKMIRROR).windowFailures).isEqualTo(1)
    }

    @Test
    fun `an empty query queries nobody`() = runTest {
        val page = repository.search("   ")

        assertThat(page.apps).isEmpty()
        assertThat(indexed.searchedFor).isEmpty()
    }

    @Test
    fun `a store switched off by the user stays out`() = runTest {
        populateIndex()
        health.registerKnownStores()
        health.setEnabled(StoreId.FDROID, enabled = false)

        assertThat(repository.search("tor").apps).isEmpty()
    }

    /**
     * "Show NSFW content" reaches the adapter **without the caller having to know**.
     *
     * It is the setting's point of application, and it is chosen for that: passing it in
     * [SearchFilters] from the caller would have worked until somebody wrote a second screen calling
     * the search, and forgetting produces no error at all — only content the user asked not to see.
     *
     * Both halves of the test count: without the second, an `includeNsfw` hardcoded to `false` would
     * pass the first and look correct.
     */
    @Test
    fun `the adult-content setting reaches the adapter`() = runTest {
        val remote = FakeIndexedStoreAdapter(StoreId.APKMIRROR, source = SearchSource.REMOTE)
        settings = LocalSettings(showNsfwContent = false)
        build(setOf(remote))
        health.registerKnownStores()

        repository.search("tor")
        assertThat(remote.searchedWith.map { it.includeNsfw }).containsExactly(false)

        settings = LocalSettings(showNsfwContent = true)
        build(setOf(remote))
        health.registerKnownStores()
        remote.searchedWith.clear()

        repository.search("tor")
        assertThat(remote.searchedWith.map { it.includeNsfw }).containsExactly(true)
    }

    /**
     * The paged catalogue **respects the category**, and without it filters the whole store.
     *
     * It is the only thing `browsePaged` decides: which of Room's two queries to use. The pagination
     * is done by Paging and by Room, and testing it here would mean testing them; the branch between
     * `listingsPaged` and `byCategoryPaged` is ours, though, and without this test getting it wrong
     * makes nothing fail — an injection said so, staying green with `listingsPaged` hardcoded.
     */
    @Test
    fun `the paged catalogue filters by category`() = runTest {
        populateCategorisedIndex()

        assertThat(repository.browsePaged(StoreId.FDROID, null).titles())
            .containsExactly("Calculator", "Tor Browser")
        assertThat(repository.browsePaged(StoreId.FDROID, "Internet").titles())
            .containsExactly("Tor Browser")
        assertThat(repository.browsePaged(StoreId.FDROID, "Nonexistent").titles()).isEmpty()
    }

    private suspend fun Flow<PagingData<StoreListingSummary>>.titles(): List<String> =
        asSnapshot().map { it.title }

    private suspend fun populateCategorisedIndex() {
        val records = listOf(
            Triple("org.example.tor", "Tor Browser", "Internet"),
            Triple("org.example.calc", "Calculator", "Science"),
        ).map { (id, title, category) ->
            val payload = FakeIndexedStoreAdapter.payload(
                id,
                FIELD_TITLE to title,
                FakeIndexedStoreAdapter.FIELD_CATEGORY to category,
            )
            IndexRecord.Full(StoreAppRef(id), payload, indexed.projectEntry(payload))
        }
        indexed.nextSnapshot = {
            StoreResult.Success(FakeSnapshot(IndexToken("1"), IndexSyncMode.FULL, records))
        }
        index.sync(StoreId.FDROID)
    }

    /**
     * The caller's value cannot override the setting.
     *
     * `SearchFilters` is a public parameter of `SearchRepository`: anyone can pass it
     * `includeNsfw = true`. If that value won, the switch in Settings would be a suggestion instead of
     * a decision.
     */
    @Test
    fun `a caller cannot override the setting`() = runTest {
        val remote = FakeIndexedStoreAdapter(StoreId.APKMIRROR, source = SearchSource.REMOTE)
        settings = LocalSettings(showNsfwContent = false)
        build(setOf(remote))
        health.registerKnownStores()

        repository.search("tor", filters = SearchFilters(includeNsfw = true))

        assertThat(remote.searchedWith.map { it.includeNsfw }).containsExactly(false)
    }

    /** A listing already in the catalogue: it is what remains after the user has opened it once. */
    private suspend fun seedListing(ref: String, title: String, iconUrl: String?) {
        val detail = StoreListingDetail(
            summary = StoreListingSummary(
                storeId = StoreId.FDROID,
                ref = StoreAppRef(ref),
                title = title,
                iconUrl = iconUrl,
            ),
        )
        db.catalogDao().saveListings(listOf(detail.toRows(currentTime, 6.hours)))
    }

    private companion object {
        const val ICON = "https://cdn.example.test/icon.png"
    }
}

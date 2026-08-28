package com.multistore.core.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.multistore.core.data.FakeIndexedStoreAdapter
import com.multistore.core.data.FakeIndexedStoreAdapter.Companion.FIELD_CATEGORY
import com.multistore.core.data.FakeIndexedStoreAdapter.Companion.FIELD_KIND
import com.multistore.core.data.FakeIndexedStoreAdapter.Companion.FIELD_SUMMARY
import com.multistore.core.data.FakeIndexedStoreAdapter.Companion.FIELD_TITLE
import com.multistore.core.data.FakeIndexedStoreAdapter.Companion.FIELD_UPDATED
import com.multistore.core.data.FakeIndexedStoreAdapter.Companion.FIELD_VERSION_CODE
import com.multistore.core.data.FakeIndexedStoreAdapter.Companion.KIND_NOT_INSTALLABLE
import com.multistore.core.data.FakeSnapshot
import com.multistore.core.data.store.StoreRegistry
import com.multistore.core.database.MultiStoreDatabase
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.common.result.Outcome
import com.multistore.store.api.IndexRecord
import com.multistore.store.api.IndexStaleness
import com.multistore.store.api.IndexSyncMode
import com.multistore.store.api.IndexToken
import com.multistore.store.api.StoreError
import com.multistore.store.api.StoreResult
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The index sync, against a real database.
 *
 * Room in memory and not a fake DAO: what this class has to demonstrate — that the token arrives
 * last, that a full mode deletes the residue, that a patch finds its "before" — are all properties of
 * **what stayed written**, and a DAO double would take them for granted instead of verifying them.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class StoreIndexRepositoryTest {

    private lateinit var db: MultiStoreDatabase
    private lateinit var adapter: FakeIndexedStoreAdapter
    private lateinit var repository: StoreIndexRepositoryImpl
    private lateinit var health: StoreHealthRepositoryImpl

    private var currentTime = Instant.fromEpochMilliseconds(1_787_316_712_615L)
    private val clock = object : Clock {
        override fun now(): Instant = currentTime
    }

    private val store = StoreId.FDROID

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MultiStoreDatabase::class.java,
        ).allowMainThreadQueries().build()
        adapter = FakeIndexedStoreAdapter(store)
        val registry = StoreRegistry(setOf(adapter))
        health = StoreHealthRepositoryImpl(registry, db.storeDao(), clock, Dispatchers.Unconfined)
        repository = StoreIndexRepositoryImpl(
            registry = registry,
            indexDao = db.indexDao(),
            catalogDao = db.catalogDao(),
            health = health,
            clock = clock,
            io = Dispatchers.Unconfined,
        )
    }

    @After
    fun tearDown() = db.close()

    // --- Helpers -------------------------------------------------------------------------

    /** The title is passed like the other fields: `FIELD_TITLE to "Uno"`. If missing, it is the id. */
    private fun full(id: String, vararg extra: Pair<String, String?>): IndexRecord {
        val fields = if (extra.none { it.first == FIELD_TITLE }) {
            arrayOf(FIELD_TITLE to id as String?, *extra)
        } else {
            arrayOf(*extra)
        }
        val payload = FakeIndexedStoreAdapter.payload(id, *fields)
        return IndexRecord.Full(
            ref = StoreAppRef(id),
            payload = payload,
            detail = adapter.projectEntry(payload),
        )
    }

    private fun serve(
        mode: IndexSyncMode,
        token: String,
        records: List<IndexRecord>,
        staleness: IndexStaleness? = null,
    ): FakeSnapshot {
        val snapshot = FakeSnapshot(IndexToken(token), mode, records, staleness = staleness)
        adapter.nextSnapshot = { StoreResult.Success(snapshot) }
        return snapshot
    }

    private suspend fun sync(force: Boolean = false) = repository.sync(store, force = force)

    // --- Full synchronisation --------------------------------------------------------------

    @Test
    fun `full sync - it writes payload, catalogue and token`() = runTest {
        serve(
            IndexSyncMode.FULL,
            token = "100",
            records = listOf(
                IndexRecord.Catalog(
                    payload = FakeIndexedStoreAdapter.payload(
                        "repo",
                        "cat:Internet" to "Internet",
                        "af:Tracking" to "Tracking",
                    ),
                    info = null,
                ),
                full("org.example.one", FIELD_TITLE to "Uno", FIELD_VERSION_CODE to "5"),
                full("org.example.two", FIELD_TITLE to "Due", FIELD_VERSION_CODE to "9"),
            ),
        )

        val outcome = sync()

        assertThat(outcome).isInstanceOf(Outcome.Success::class.java)
        val report = (outcome as Outcome.Success).value
        assertThat(report.mode).isEqualTo(IndexSyncMode.FULL)
        assertThat(report.written).isEqualTo(2)

        val state = repository.state(store)
        assertThat(state?.token).isEqualTo("100")
        assertThat(state?.entryCount).isEqualTo(2)
        assertThat(state?.pruningProfile)
            .isEqualTo(StoreIndexRepository.currentPruningProfile())

        val taxonomy = repository.taxonomy(store)
        assertThat(taxonomy.categories.map { it.id }).containsExactly("Internet")
        assertThat(taxonomy.antiFeatures.map { it.id }).containsExactly("Tracking")

        val listing = db.catalogDao().listing(store, "org.example.one")
        assertThat(listing?.listing?.title).isEqualTo("Uno")
        assertThat(listing?.versions).hasSize(1)
    }

    @Test
    fun `full sync - it deletes what did not appear again`() = runTest {
        serve(IndexSyncMode.FULL, "100", listOf(full("a"), full("b"), full("c")))
        sync()

        // The second complete document no longer names `b`: on a complete index the absence *is* a
        // deletion. Without this step, a withdrawn package would stay in the list forever, installable
        // and without updates.
        serve(IndexSyncMode.FULL, "200", listOf(full("a"), full("c")))
        val report = (sync() as Outcome.Success).value

        assertThat(report.removed).isEqualTo(1)
        assertThat(db.indexDao().entryIds(store)).containsExactly("a", "c")
        assertThat(db.catalogDao().listing(store, "b")).isNull()
        // And with the listing goes the aggregated app left with nobody naming it.
        assertThat(db.catalogDao().app("pkg:b")).isNull()
    }

    @Test
    fun `a non-installable entry is kept but does not appear in the catalogue`() = runTest {
        serve(
            IndexSyncMode.FULL,
            "100",
            listOf(
                full("org.example.app", FIELD_VERSION_CODE to "1"),
                full("org.example.ota", FIELD_KIND to KIND_NOT_INSTALLABLE),
            ),
        )

        sync()

        // The payload is still needed: without it, the next merge patch on that entry would have no
        // "before" to apply itself to. But a `.zip` `PackageInstaller` cannot install must not appear
        // in a search.
        assertThat(db.indexDao().entryIds(store)).containsExactly("org.example.app", "org.example.ota")
        assertThat(db.catalogDao().listing(store, "org.example.ota")).isNull()
        assertThat(db.catalogDao().listingCount(store)).isEqualTo(1)
    }

    // --- Incremental update -------------------------------------------------------------------

    @Test
    fun `patch - it merges with the stored payload instead of replacing it`() = runTest {
        serve(
            IndexSyncMode.FULL,
            "100",
            listOf(full("org.example.one", FIELD_TITLE to "Uno", FIELD_SUMMARY to "vecchio", FIELD_VERSION_CODE to "5")),
        )
        sync()

        serve(
            IndexSyncMode.INCREMENTAL,
            "200",
            listOf(
                IndexRecord.Patch(
                    ref = StoreAppRef("org.example.one"),
                    payload = FakeIndexedStoreAdapter.patch("org.example.one", FIELD_VERSION_CODE to "6"),
                ),
            ),
        )
        sync()

        val listing = db.catalogDao().listing(store, "org.example.one")
        // The patch names only the version code: the title and the summary it does not name have to
        // survive. Replacing the payload instead of merging it would have deleted them.
        assertThat(listing?.listing?.title).isEqualTo("Uno")
        assertThat(listing?.listing?.summary?.byTag?.values).contains("vecchio")
        assertThat(listing?.versions?.single()?.versionCode).isEqualTo(6)
        assertThat(repository.state(store)?.token).isEqualTo("200")
    }

    @Test
    fun `a patch that empties an entry - it counts as a deletion`() = runTest {
        serve(IndexSyncMode.FULL, "100", listOf(full("a", FIELD_VERSION_CODE to "1"), full("b")))
        sync()

        serve(
            IndexSyncMode.INCREMENTAL,
            "200",
            listOf(
                IndexRecord.Patch(
                    ref = StoreAppRef("a"),
                    payload = FakeIndexedStoreAdapter.tombstone("a", listOf(FIELD_TITLE, FIELD_VERSION_CODE)),
                ),
            ),
        )
        val report = (sync() as Outcome.Success).value

        assertThat(report.removed).isEqualTo(1)
        assertThat(db.indexDao().entryIds(store)).containsExactly("b")
        assertThat(db.catalogDao().listing(store, "a")).isNull()
    }

    @Test
    fun `incremental - it does not delete what the diff does not name`() = runTest {
        serve(IndexSyncMode.FULL, "100", listOf(full("a"), full("b"), full("c")))
        sync()

        serve(
            IndexSyncMode.INCREMENTAL,
            "200",
            listOf(IndexRecord.Remove(StoreAppRef("b"))),
        )
        sync()

        // The difference between the two modes lies entirely here: in incremental, silence means
        // "unchanged"; in full, it means "no longer exists".
        assertThat(db.indexDao().entryIds(store)).containsExactly("a", "c")
    }

    @Test
    fun `incremental - the taxonomy merges, it is not replaced`() = runTest {
        serve(
            IndexSyncMode.FULL,
            "100",
            listOf(
                IndexRecord.Catalog(
                    FakeIndexedStoreAdapter.payload("repo", "cat:Internet" to "Internet", "cat:Games" to "Games"),
                    info = null,
                ),
            ),
        )
        sync()

        serve(
            IndexSyncMode.INCREMENTAL,
            "200",
            listOf(
                IndexRecord.Catalog(
                    FakeIndexedStoreAdapter.patch("repo", "cat:Internet" to "Rete"),
                    info = null,
                ),
            ),
        )
        sync()

        val categories = repository.taxonomy(store).categories.associate { it.id to it.name.byTag.values.first() }
        // A diff of the `repo` block is a merge patch like any other: replacing it wholesale would
        // delete every category that diff does not name — here, "Games".
        assertThat(categories).containsEntry("Internet", "Rete")
        assertThat(categories).containsKey("Games")
    }

    // --- Token and pruning profile -----------------------------------------------------------

    @Test
    fun `the stored token is offered to the store at the next sync`() = runTest {
        serve(IndexSyncMode.FULL, "100", listOf(full("a")))
        sync()
        serve(IndexSyncMode.INCREMENTAL, "200", emptyList())
        sync()

        assertThat(adapter.openedWith).containsExactly(null, IndexToken("100")).inOrder()
    }

    @Test
    fun `a different pruning profile forces a restart from scratch`() = runTest {
        serve(IndexSyncMode.FULL, "100", listOf(full("a")))
        sync()

        // It simulates the situation created by adding a sixth language to the app: what is stored has
        // been pruned by another criterion, and a diff applied to that base would leave the new
        // language empty forever, with nothing signalling the problem.
        db.indexDao().upsertState(
            db.indexDao().state(store)!!.copy(pruningProfile = "en"),
        )

        serve(IndexSyncMode.FULL, "200", listOf(full("a")))
        sync()

        assertThat(adapter.openedWith.last()).isNull()
        assertThat(repository.state(store)?.pruningProfile)
            .isEqualTo(StoreIndexRepository.currentPruningProfile())
    }

    @Test
    fun `force - it ignores the token even when it would be valid`() = runTest {
        serve(IndexSyncMode.FULL, "100", listOf(full("a")))
        sync()
        serve(IndexSyncMode.FULL, "200", listOf(full("a")))
        sync(force = true)

        assertThat(adapter.openedWith).containsExactly(null, null).inOrder()
    }

    @Test
    fun `a stalled mirror is recorded in health_events, but does not block the sync`() = runTest {
        serve(
            IndexSyncMode.FULL,
            token = "1",
            records = listOf(full("a")),
            // The store declares 14 days; this index is 30 old.
            staleness = IndexStaleness(age = 30.days, maxAge = 14.days),
        )

        val report = (sync() as Outcome.Success).value

        // It does not block: the data is authentic, and on a first sync an old index is still the only
        // thing the user has. But it stays written, because a stalled mirror is exactly how security
        // updates stop arriving without anything failing.
        assertThat(report.written).isEqualTo(1)
        assertThat(report.staleIndex).isTrue()
        assertThat(db.storeDao().recentEvents().map { event -> event.kind }).contains("index_stale")
    }

    @Test
    fun `a fresh index leaves no event`() = runTest {
        serve(
            IndexSyncMode.FULL,
            token = "1",
            records = listOf(full("a")),
            staleness = IndexStaleness(age = 2.days, maxAge = 14.days),
        )

        val report = (sync() as Outcome.Success).value

        // Recording on every successful sync would make `health_events` unreadable exactly when it
        // needs reading.
        assertThat(report.staleIndex).isFalse()
        assertThat(db.storeDao().recentEvents().map { event -> event.kind }).doesNotContain("index_stale")
    }

    @Test
    fun `a successful sync is visible in the health log`() = runTest {
        serve(IndexSyncMode.FULL, token = "1", records = listOf(full("a")))

        sync()

        // For a local-index store the sync **is** the only request made: without this line its health
        // counter never moves, and a diagnostic report would say `lastSuccess=never` with four thousand
        // apps in the catalogue. It happened, and it is the first defect the export found by reading
        // itself.
        assertThat(health.health(StoreId.FDROID).lastSuccessAt).isNotNull()
    }

    @Test
    fun `a mirror that does not answer leaves an event, not an open breaker`() = runTest {
        adapter.nextSnapshot = { StoreResult.Failure(StoreError.Network(cause = null, httpCode = 503)) }

        val outcome = sync()

        assertThat(outcome).isInstanceOf(Outcome.Failure::class.java)
        assertThat(db.storeDao().recentEvents().map { it.kind }).contains("index_sync_failed")
        // **Not** `recordFailure`: the breaker would govern the fallback search, which on F-Droid
        // talks to a host separate from the mirror's. Five mirror failures would switch off a search
        // that may well work.
        assertThat(health.health(StoreId.FDROID).windowFailures).isEqualTo(0)
    }

    @Test
    fun `a sync that fails halfway does not write the token`() = runTest {
        serve(IndexSyncMode.FULL, "100", listOf(full("a")))
        sync()

        // The stream breaks after emitting one entry: it is the case of the process killed halfway.
        val broken = object : com.multistore.store.api.StoreIndexSnapshot {
            override val token = IndexToken("999")
            override val mode = IndexSyncMode.FULL
            override val expectedRecords: Int? = null
            override val expectedBytes: Long? = null
            override fun records() = kotlinx.coroutines.flow.flow {
                emit(full("b"))
                throw java.io.IOException("connection dropped")
            }
            override fun close() = Unit
        }
        adapter.nextSnapshot = { StoreResult.Success(broken) }

        val outcome = sync()

        assertThat(outcome).isInstanceOf(Outcome.Failure::class.java)
        // The token stays the old one. It is the important half: with the new token, the next sync
        // would ask for a diff starting from a state we do not have, and would apply it to a wrong
        // base, silently and forever.
        assertThat(repository.state(store)?.token).isEqualTo("100")
    }

    @Test
    fun `the snapshot is closed even when the sync succeeds`() = runTest {
        val snapshot = serve(IndexSyncMode.FULL, "100", listOf(full("a")))
        sync()

        // Behind it is an 18 MB temporary file in `cacheDir`: not closing it means filling the device's
        // cache at every sync.
        assertThat(snapshot.closed).isTrue()
    }

    @Test
    fun `a store with no index does not sync, and says so`() = runTest {
        adapter.nextSnapshot = { StoreResult.Unsupported }

        val outcome = repository.sync(StoreId.APKMIRROR)

        assertThat(outcome).isInstanceOf(Outcome.Failure::class.java)
        assertThat((outcome as Outcome.Failure).error).isEqualTo(StoreIndexRepository.NOT_INDEXED)
    }

    @Test
    fun `syncing the same content twice duplicates nothing`() = runTest {
        serve(IndexSyncMode.FULL, "100", listOf(full("a", FIELD_VERSION_CODE to "1")))
        sync()
        serve(IndexSyncMode.FULL, "200", listOf(full("a", FIELD_VERSION_CODE to "1")))
        sync()

        assertThat(db.indexDao().entryCount(store)).isEqualTo(1)
        assertThat(db.catalogDao().listingCount(store)).isEqualTo(1)
        assertThat(db.catalogDao().listing(store, "a")?.versions).hasSize(1)
    }

    @Test
    fun `the listing's id survives a resync`() = runTest {
        serve(IndexSyncMode.FULL, "100", listOf(full("a", FIELD_VERSION_CODE to "1")))
        sync()
        val firstId = db.catalogDao().listingId(store, "a")

        serve(IndexSyncMode.FULL, "200", listOf(full("a", FIELD_VERSION_CODE to "2")))
        sync()

        // `installed_apps.update_channel_listing_id` points at this id: recreating it on every sync
        // would detach every installed app from its own update channel.
        assertThat(db.catalogDao().listingId(store, "a")).isEqualTo(firstId)
    }

    @Test
    fun `the update date reaches the Home screen`() = runTest {
        serve(
            IndexSyncMode.FULL,
            "100",
            listOf(
                full("vecchia", FIELD_UPDATED to "1000", FIELD_CATEGORY to "Internet"),
                full("nuova", FIELD_UPDATED to "9000", FIELD_CATEGORY to "Internet"),
            ),
        )
        sync()

        val recent = db.catalogDao().recentlyUpdated(store, limit = 10, offset = 0)
        assertThat(recent.map { it.listing.storeAppRef }).containsExactly("nuova", "vecchia").inOrder()
        assertThat(db.catalogDao().byCategory(store, "Internet", 10, 0)).hasSize(2)
    }
}

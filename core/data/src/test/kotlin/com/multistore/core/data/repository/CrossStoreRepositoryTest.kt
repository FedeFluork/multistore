package com.multistore.core.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.multistore.core.common.identity.AppKeys
import com.multistore.core.common.result.Outcome
import com.multistore.core.common.result.AppError
import com.multistore.core.data.FakeIndexedStoreAdapter
import com.multistore.core.data.mapper.toDiscoveredRows
import com.multistore.core.data.mapper.toRows
import com.multistore.core.data.store.EnabledStores
import com.multistore.core.data.store.SearchGroupMemory
import com.multistore.core.data.store.StoreRegistry
import com.multistore.core.database.MultiStoreDatabase
import com.multistore.core.model.AggregatedApp
import com.multistore.core.model.AggregatedListing
import com.multistore.core.model.AppVersion
import com.multistore.core.model.MatchMethod
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.model.StoreListingDetail
import com.multistore.core.model.StoreListingSummary
import com.multistore.core.model.UsesPermission
import com.multistore.core.model.VersionRef
import com.multistore.store.api.HashAvailability
import com.multistore.store.api.PagedResult
import com.multistore.store.api.SearchSource
import com.multistore.store.api.StoreResult
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Cross-store identity, from the side that decides **what merges and what gets asked about**.
 *
 * The test carrying the weight is the second: `AppKeys.inferred` builds an app's key from its
 * normalised title and developer, so two listings with no `packageName` can share an `app_key`
 * **without** the matcher merging them. Trusting the key alone would be the silent merge the rules
 * forbid; reading `match_confidence`, which has said the truth from the start, is what prevents it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CrossStoreRepositoryTest {

    private lateinit var db: MultiStoreDatabase
    private lateinit var health: StoreHealthRepositoryImpl
    private lateinit var repository: CrossStoreRepositoryImpl
    private lateinit var memory: SearchGroupMemory
    private lateinit var details: RecordingAppDetails
    private lateinit var apkcombo: FakeIndexedStoreAdapter
    private lateinit var apkmody: FakeIndexedStoreAdapter

    private val now = Instant.fromEpochMilliseconds(1_787_316_712_615L)
    private val clock = object : Clock {
        override fun now(): Instant = now
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MultiStoreDatabase::class.java,
        ).allowMainThreadQueries().build()
        apkcombo = FakeIndexedStoreAdapter(StoreId.APKCOMBO, source = SearchSource.REMOTE)
        apkmody = FakeIndexedStoreAdapter(StoreId.APKMODY, source = SearchSource.REMOTE)
        val registry = StoreRegistry(setOf(FakeIndexedStoreAdapter(StoreId.FDROID), apkcombo, apkmody))
        health = StoreHealthRepositoryImpl(registry, db.storeDao(), clock, Dispatchers.Unconfined)
        memory = SearchGroupMemory()
        details = RecordingAppDetails()
        repository = CrossStoreRepositoryImpl(
            catalogDao = db.catalogDao(),
            details = details,
            registry = registry,
            enabledStores = EnabledStores(registry, db.storeDao()),
            memory = memory,
            health = health,
            clock = clock,
            io = Dispatchers.Unconfined,
        )
    }

    @After
    fun tearDown() = db.close()

    // --- What Room already knows --------------------------------------------------------------

    @Test
    fun `two listings with the same package are availability, not hypotheses`() = runTest {
        saveListing(StoreId.FDROID, "org.fdroid.fdroid", "F-Droid", packageName = "org.fdroid.fdroid")
        saveListing(StoreId.APKCOMBO, "f-droid/org.fdroid.fdroid", "F-Droid", packageName = "org.fdroid.fdroid")

        val availability = repository.observe(StoreId.FDROID, StoreAppRef("org.fdroid.fdroid")).first()

        assertThat(availability.availableOn.map { it.storeId }).containsExactly(StoreId.APKCOMBO)
        assertThat(availability.possibleMatches).isEmpty()
    }

    @Test
    fun `a key shared by inference is not enough to declare availability`() = runTest {
        // Neither store publishes the package: `AppKeys.inferred` gives both the same key — same
        // title, unknown developer — and the row already carries the fact that the confidence is
        // `0.6`. It is below the merge threshold, so it is asked about, not merged.
        saveListing(StoreId.APKMODY, "apps/spotify", "Spotify")
        saveListing(StoreId.APKCOMBO, "spotify", "Spotify")
        val sharedKey = AppKeys.inferred("Spotify", null)
        assertThat(db.catalogDao().listingIdentity(StoreId.APKMODY, "apps/spotify")?.appKey)
            .isEqualTo(sharedKey)

        val availability = repository.observe(StoreId.APKMODY, StoreAppRef("apps/spotify")).first()

        assertThat(availability.availableOn).isEmpty()
        assertThat(availability.possibleMatches.map { it.storeId }).containsExactly(StoreId.APKCOMBO)
    }

    @Test
    fun `a listing with a similar title but another key is a possibility`() = runTest {
        saveListing(StoreId.FDROID, "org.telegram.messenger", "Telegram", packageName = "org.telegram.messenger")
        saveListing(StoreId.APKMODY, "apps/telegram-x", "Telegram X")

        val availability = repository.observe(StoreId.FDROID, StoreAppRef("org.telegram.messenger")).first()

        assertThat(availability.availableOn).isEmpty()
        assertThat(availability.possibleMatches.map { it.storeId }).containsExactly(StoreId.APKMODY)
    }

    @Test
    fun `another listing from the same store is not a cross-store match`() = runTest {
        saveListing(StoreId.APKMODY, "apps/telegram", "Telegram")
        saveListing(StoreId.APKMODY, "apps/telegram-x", "Telegram X")

        val availability = repository.observe(StoreId.APKMODY, StoreAppRef("apps/telegram")).first()

        // Two pages of the same site are not "also available elsewhere": offering them as a possible
        // match would help nobody choose where to install from.
        assertThat(availability.possibleMatches).isEmpty()
    }

    // --- What the search has just seen -------------------------------------------------------

    @Test
    fun `the group found by the search appears with no request`() = runTest {
        saveListing(StoreId.FDROID, "org.fdroid.fdroid", "F-Droid", packageName = "org.fdroid.fdroid")
        memory.remember(
            listOf(
                AggregatedApp(
                    appKey = "pkg:org.fdroid.fdroid",
                    listings = listOf(
                        listing(StoreId.FDROID, "org.fdroid.fdroid", "F-Droid"),
                        listing(StoreId.APKMIRROR, "f-droid-limited/f-droid", "F-Droid"),
                    ),
                ),
            ),
        )

        val availability = repository.observe(StoreId.FDROID, StoreAppRef("org.fdroid.fdroid")).first()

        assertThat(availability.availableOn.map { it.storeId }).containsExactly(StoreId.APKMIRROR)
        // The reason the memory exists: speculative prefetch is forbidden, and opening a listing must
        // query nobody.
        assertThat(apkcombo.searchedFor).isEmpty()
        assertThat(apkmody.searchedFor).isEmpty()
    }

    @Test
    fun `observing queries no store`() = runTest {
        saveListing(StoreId.FDROID, "org.fdroid.fdroid", "F-Droid", packageName = "org.fdroid.fdroid")

        repository.observe(StoreId.FDROID, StoreAppRef("org.fdroid.fdroid")).first()

        assertThat(apkcombo.searchedFor).isEmpty()
        assertThat(apkmody.searchedFor).isEmpty()
    }

    // --- The explicit lookup ------------------------------------------------------------------

    @Test
    fun `searching the other stores writes what it finds, and only where it is missing`() = runTest {
        saveListing(StoreId.FDROID, "org.fdroid.fdroid", "F-Droid", packageName = "org.fdroid.fdroid")
        apkcombo.searchResults = StoreResult.Success(
            PagedResult.single(
                listOf(
                    StoreListingSummary(
                        storeId = StoreId.APKCOMBO,
                        ref = StoreAppRef("f-droid/org.fdroid.fdroid"),
                        title = "F-Droid",
                        packageName = "org.fdroid.fdroid",
                    ),
                ),
            ),
        )

        repository.lookUp(StoreId.FDROID, StoreAppRef("org.fdroid.fdroid"))

        assertThat(apkcombo.searchedFor).containsExactly("F-Droid")
        val availability = repository.observe(StoreId.FDROID, StoreAppRef("org.fdroid.fdroid")).first()
        assertThat(availability.availableOn.map { it.storeId }).containsExactly(StoreId.APKCOMBO)
        assertThat(availability.lookup).isEqualTo(CrossStoreLookup.DONE)
    }

    @Test
    fun `a discovered listing is born already expired, because a list is not a listing`() = runTest {
        saveListing(StoreId.FDROID, "org.fdroid.fdroid", "F-Droid", packageName = "org.fdroid.fdroid")
        apkcombo.searchResults = StoreResult.Success(
            PagedResult.single(
                listOf(
                    StoreListingSummary(
                        storeId = StoreId.APKCOMBO,
                        ref = StoreAppRef("f-droid/org.fdroid.fdroid"),
                        title = "F-Droid",
                        packageName = "org.fdroid.fdroid",
                    ),
                ),
            ),
        )

        repository.lookUp(StoreId.FDROID, StoreAppRef("org.fdroid.fdroid"))

        // What was read is a page of results: no versions, no screenshots. With `ttl_seconds = 0`
        // opening it forces the re-read, and the listing never declares "this store publishes no
        // installable package" about something it has not looked at.
        val row = db.catalogDao().listing(StoreId.APKCOMBO, "f-droid/org.fdroid.fdroid")
        assertThat(row?.listing?.ttlSeconds).isEqualTo(0)
        assertThat(row?.versions).isEmpty()
    }

    @Test
    fun `rediscovering an already read listing does not delete its versions`() = runTest {
        saveListing(
            StoreId.FDROID,
            "de.danoeh.antennapod",
            "AntennaPod",
            packageName = "de.danoeh.antennapod",
        )
        // apkcombo's row is already there, **with a version**, but with no `packageName`: its
        // `app_key` is inferred, so it is not a sibling of the anchor and the store is queried all the
        // same. It is the only way the rewrite can really happen — with the wrong premise the test
        // would pass without proving anything.
        saveListing(
            storeId = StoreId.APKCOMBO,
            ref = APKCOMBO_REF,
            title = "AntennaPod",
            versions = listOf(
                AppVersion(versionName = "3.5.0", versionCode = 3_050_095, ref = VersionRef("v1")),
            ),
        )
        assertThat(db.catalogDao().listingIdentity(StoreId.APKCOMBO, APKCOMBO_REF)?.appKey)
            .isNotEqualTo(AppKeys.forPackage("de.danoeh.antennapod"))
        apkcombo.searchResults = StoreResult.Success(
            PagedResult.single(
                listOf(
                    StoreListingSummary(
                        storeId = StoreId.APKCOMBO,
                        ref = StoreAppRef(APKCOMBO_REF),
                        title = "AntennaPod",
                        packageName = "de.danoeh.antennapod",
                    ),
                ),
            ),
        )

        repository.lookUp(StoreId.FDROID, StoreAppRef("de.danoeh.antennapod"))

        assertThat(apkcombo.searchedFor).containsExactly("AntennaPod")
        // `saveListing` does `clearVersions` before writing: using it here would empty a listing
        // already read in full, and the listing would say "this store publishes no installable
        // package" for an app that has one. Hence `insertListingIfAbsent`.
        val row = db.catalogDao().listing(StoreId.APKCOMBO, APKCOMBO_REF)
        assertThat(row?.versions?.map { it.versionRef }).containsExactly("v1")
    }

    @Test
    fun `whoever has already answered is not asked`() = runTest {
        saveListing(StoreId.FDROID, "org.fdroid.fdroid", "F-Droid", packageName = "org.fdroid.fdroid")
        saveListing(StoreId.APKCOMBO, "f-droid/org.fdroid.fdroid", "F-Droid", packageName = "org.fdroid.fdroid")

        repository.lookUp(StoreId.FDROID, StoreAppRef("org.fdroid.fdroid"))

        assertThat(apkcombo.searchedFor).isEmpty()
        assertThat(apkmody.searchedFor).containsExactly("F-Droid")
    }

    @Test
    fun `an open breaker saves the request`() = runTest {
        saveListing(StoreId.FDROID, "org.fdroid.fdroid", "F-Droid", packageName = "org.fdroid.fdroid")
        health.recordFailure(StoreId.APKCOMBO, com.multistore.store.api.StoreError.RateLimited(null))

        repository.lookUp(StoreId.FDROID, StoreAppRef("org.fdroid.fdroid"))

        assertThat(apkcombo.searchedFor).isEmpty()
    }

    // --- The user's word ----------------------------------------------------------------------

    @Test
    fun `confirming moves the listing into the group, and it stays`() = runTest {
        saveListing(StoreId.APKMODY, "apps/spotify", "Spotify")
        saveListing(StoreId.APKCOMBO, "spotify-music", "Spotify Music")
        val candidate = requireNotNull(db.catalogDao().listingId(StoreId.APKCOMBO, "spotify-music"))

        repository.confirm(StoreId.APKMODY, StoreAppRef("apps/spotify"), candidate)

        val availability = repository.observe(StoreId.APKMODY, StoreAppRef("apps/spotify")).first()
        assertThat(availability.availableOn.map { it.storeId }).containsExactly(StoreId.APKCOMBO)
        assertThat(availability.availableOn.single().listing.method).isEqualTo(MatchMethod.USER_CONFIRMED)
        assertThat(availability.possibleMatches).isEmpty()
    }

    @Test
    fun `rejecting makes it disappear, and does not bring it back`() = runTest {
        saveListing(StoreId.FDROID, "org.telegram.messenger", "Telegram", packageName = "org.telegram.messenger")
        saveListing(StoreId.APKMODY, "apps/telegram-x", "Telegram X")
        val candidate = requireNotNull(db.catalogDao().listingId(StoreId.APKMODY, "apps/telegram-x"))
        val anchor = StoreAppRef("org.telegram.messenger")
        assertThat(repository.observe(StoreId.FDROID, anchor).first().possibleMatches).hasSize(1)

        repository.reject(StoreId.FDROID, anchor, candidate)

        assertThat(repository.observe(StoreId.FDROID, anchor).first().possibleMatches).isEmpty()
    }

    // --- Helpers ------------------------------------------------------------------------------

    private fun listing(storeId: StoreId, ref: String, title: String) = AggregatedListing(
        summary = StoreListingSummary(storeId = storeId, ref = StoreAppRef(ref), title = title),
    )

    // --- The comparison table ------------------------------------------------------------------

    @Test
    fun `the comparison puts the anchor first and marks it, with the others behind`() = runTest {
        saveListing(StoreId.FDROID, "org.fdroid.fdroid", "F-Droid", packageName = "org.fdroid.fdroid")
        saveListing(
            StoreId.APKCOMBO,
            "f-droid/org.fdroid.fdroid",
            "F-Droid on APKCombo",
            packageName = "org.fdroid.fdroid",
        )

        val comparison = repository.compare(StoreId.FDROID, StoreAppRef("org.fdroid.fdroid")).first()

        // The anchor is a **row**, not a heading: "is this store behind the others" cannot be
        // answered by a table that leaves this store out.
        assertThat(comparison.rows.map { it.storeId })
            .containsExactly(StoreId.FDROID, StoreId.APKCOMBO).inOrder()
        assertThat(comparison.rows.single { it.current }.storeId).isEqualTo(StoreId.FDROID)
        // The title comes from the anchor and not from a vote: nine stores write nine names for one
        // app, and a majority would rename the page depending on who answered.
        assertThat(comparison.title).isEqualTo("F-Droid")
        assertThat(comparison.isComparable).isTrue()
    }

    @Test
    fun `an uncertain match stays out of the comparison`() = runTest {
        // Below 0.85 nothing merges silently, and a comparison is an invitation to pick a row and
        // install from it: a row that might be another app is exactly what must never be offered
        // that way. It keeps its own section on the listing, where the question is different.
        saveListing(StoreId.APKMODY, "apps/spotify", "Spotify")
        saveListing(StoreId.APKCOMBO, "spotify", "Spotify")

        val comparison = repository.compare(StoreId.APKMODY, StoreAppRef("apps/spotify")).first()

        assertThat(comparison.rows.map { it.storeId }).containsExactly(StoreId.APKMODY)
        // One row is the listing already being read: a table of it alone is the header, as a grid.
        assertThat(comparison.isComparable).isFalse()
    }

    @Test
    fun `a listing nobody has opened is 'not read', not 'publishes nothing'`() = runTest {
        saveListing(
            StoreId.FDROID,
            "org.fdroid.fdroid",
            "F-Droid",
            packageName = "org.fdroid.fdroid",
            versions = listOf(
                AppVersion(
                    versionName = "1.23.2",
                    versionCode = 1_023_052,
                    ref = VersionRef("v"),
                    sizeBytes = 9_400_000,
                ),
            ),
        )
        // Written the way cross-store matching writes what a **result list** produced: `ttl_seconds
        // = 0`, born already expired, with no versions and therefore no size. It is the row whose
        // empty cells are about us and not about the store.
        discoverListing(StoreId.APKCOMBO, "f-droid/org.fdroid.fdroid", "F-Droid", "org.fdroid.fdroid")

        val comparison = repository.compare(StoreId.FDROID, StoreAppRef("org.fdroid.fdroid")).first()

        val anchor = comparison.rows.single { it.storeId == StoreId.FDROID }
        assertThat(anchor.listingRead).isTrue()
        assertThat(anchor.sizeBytes).isEqualTo(9_400_000)

        val discovered = comparison.rows.single { it.storeId == StoreId.APKCOMBO }
        // Both halves: a size that is absent **and** the flag saying why. Without the flag the
        // screen would print "this store does not publish it" about a store nobody has asked.
        assertThat(discovered.sizeBytes).isNull()
        assertThat(discovered.listingRead).isFalse()
    }

    @Test
    fun `the size is the one of the highest version, and an unnumbered one does not win`() = runTest {
        saveListing(
            StoreId.APKCOMBO,
            "telegram",
            "Telegram",
            packageName = "org.telegram.messenger",
            versions = listOf(
                AppVersion("11.0.0", 61_000, VersionRef("a"), sizeBytes = 10),
                AppVersion("11.13.2", 61_082, VersionRef("b"), sizeBytes = 20),
                // Four stores of nine publish no version code. SQLite sorts NULL **below**
                // everything under `DESC`, so without the `IS NULL` clause in the query this row
                // would win the "highest" comparison against every numbered one.
                AppVersion("unknown", null, VersionRef("c"), sizeBytes = 99),
            ),
        )

        val comparison = repository.compare(StoreId.APKCOMBO, StoreAppRef("telegram")).first()

        assertThat(comparison.rows.single().sizeBytes).isEqualTo(20)
    }

    @Test
    fun `a newest version with no size shows nothing, not an older version's size`() = runTest {
        // Real and not hypothetical: apkmody rounds its sizes — 150.98 MB declared against
        // 158,310,989 actual bytes — so its adapter leaves the field null on purpose. A sub-query
        // that skipped the empty ones would print the older file's number beside the newer file's
        // version name, which is a wrong answer wearing the shape of a right one.
        saveListing(
            StoreId.APKCOMBO,
            "telegram",
            "Telegram",
            packageName = "org.telegram.messenger",
            versions = listOf(
                AppVersion("11.0.0", 61_000, VersionRef("a"), sizeBytes = 10),
                AppVersion("11.13.2", 61_082, VersionRef("b"), sizeBytes = null),
            ),
        )

        val row = repository.compare(StoreId.APKCOMBO, StoreAppRef("telegram")).first().rows.single()

        assertThat(row.sizeBytes).isNull()
        // And it is `listingRead`, so the screen says "does not publish it" and not "not read yet":
        // the two empty cells look identical and mean opposite things.
        assertThat(row.listingRead).isTrue()
    }

    @Test
    fun `the hash column is the adapter's declaration, known even without a version`() = runTest {
        // A registry of its own: the declaration is fixed at construction, which is the point —
        // it is a fact about the store and not about this listing.
        val sometimes = FakeIndexedStoreAdapter(
            StoreId.APKCOMBO,
            source = SearchSource.REMOTE,
            hashAvailability = HashAvailability.SOMETIMES,
        )
        val scoped = CrossStoreRepositoryImpl(
            catalogDao = db.catalogDao(),
            details = RecordingAppDetails(),
            registry = StoreRegistry(setOf(sometimes)),
            enabledStores = EnabledStores(StoreRegistry(setOf(sometimes)), db.storeDao()),
            memory = memory,
            health = health,
            clock = clock,
            io = Dispatchers.Unconfined,
        )
        saveListing(StoreId.APKCOMBO, "telegram", "Telegram", packageName = "org.telegram.messenger")

        val comparison = scoped.compare(StoreId.APKCOMBO, StoreAppRef("telegram")).first()

        // The one column that does not come off this listing: it is what the store does in general,
        // so it is the only thing the table can say about a row nobody has opened.
        assertThat(comparison.rows.single().hashAvailability).isEqualTo(HashAvailability.SOMETIMES)
    }

    // --- Reading the unread rows when the table opens -----------------------------------------

    @Test
    fun `opening the comparison reads the listings nobody has opened`() = runTest {
        saveListing(StoreId.FDROID, "org.telegram.messenger", "Telegram", packageName = "org.telegram.messenger")
        discoverListing(StoreId.APKCOMBO, "telegram", "Telegram", "org.telegram.messenger")

        repository.readListings(StoreId.FDROID, StoreAppRef("org.telegram.messenger"))

        // The discovered row and **only** it: the anchor was saved as a listing, so asking for it
        // again would be a request for something already on disk. That half is the one an injection
        // removing the `filterNot` would break.
        assertThat(details.refreshed).containsExactly(
            StoreId.APKCOMBO to "telegram",
        )
    }

    @Test
    fun `a store that refuses marks its own row and leaves the others alone`() = runTest {
        saveListing(StoreId.FDROID, "org.telegram.messenger", "Telegram", packageName = "org.telegram.messenger")
        discoverListing(StoreId.APKCOMBO, "telegram", "Telegram", "org.telegram.messenger")
        discoverListing(StoreId.APKMODY, "telegram-mod", "Telegram", "org.telegram.messenger")
        details.failing += StoreId.APKCOMBO

        repository.readListings(StoreId.FDROID, StoreAppRef("org.telegram.messenger"))
        val rows = repository.compare(StoreId.FDROID, StoreAppRef("org.telegram.messenger"))
            .first().rows.associateBy { it.storeId }

        // The refusal is on the card that refused, and the screen is not failed: a comparison that
        // blanked itself because one source of eight said no would lose the seven that answered.
        assertThat(rows.getValue(StoreId.APKCOMBO).read).isEqualTo(ListingRead.FAILED)
        assertThat(rows.getValue(StoreId.APKMODY).read).isEqualTo(ListingRead.IDLE)
        assertThat(rows.getValue(StoreId.FDROID).read).isEqualTo(ListingRead.IDLE)
    }

    @Test
    fun `a success leaves no state behind, because the row itself now says it`() = runTest {
        saveListing(StoreId.FDROID, "org.telegram.messenger", "Telegram", packageName = "org.telegram.messenger")
        discoverListing(StoreId.APKCOMBO, "telegram", "Telegram", "org.telegram.messenger")

        repository.readListings(StoreId.FDROID, StoreAppRef("org.telegram.messenger"))
        val row = repository.compare(StoreId.FDROID, StoreAppRef("org.telegram.messenger"))
            .first().rows.single { it.storeId == StoreId.APKCOMBO }

        // No `DONE`: success is expressed by the data, and a second copy of it here would be free to
        // disagree with `listingRead` the day one of the two stopped being updated.
        assertThat(row.read).isEqualTo(ListingRead.IDLE)
    }

    @Test
    fun `asking twice does not ask the store twice`() = runTest {
        saveListing(StoreId.FDROID, "org.telegram.messenger", "Telegram", packageName = "org.telegram.messenger")
        discoverListing(StoreId.APKCOMBO, "telegram", "Telegram", "org.telegram.messenger")
        details.failing += StoreId.APKCOMBO

        repository.readListings(StoreId.FDROID, StoreAppRef("org.telegram.messenger"))
        repository.readListings(StoreId.FDROID, StoreAppRef("org.telegram.messenger"))

        // A row that failed is not re-knocked on by a second arrival: without this the screen would
        // remake the same refused request on every rotation, and the card's state would flicker
        // between "could not be read" and "reading" with nobody having asked for either.
        assertThat(details.refreshed).hasSize(1)
    }

    @Test
    fun `a row the search remembered is read, and then stops saying "not read yet"`() = runTest {
        saveListing(StoreId.FDROID, "org.fdroid.fdroid", "F-Droid", packageName = "org.fdroid.fdroid")
        // The case the id-keyed lookup could not express: a row cross-store matching found in the
        // **last search's results** and not in `store_listings`, so `compose` builds it from the
        // remembered summary and it has no listing id. On AN1 — no package name anywhere on the
        // site, so its saved listing rarely shares the anchor's `app_key` — this is the ordinary
        // shape rather than an edge case.
        memory.remember(
            listOf(
                AggregatedApp(
                    appKey = "pkg:org.fdroid.fdroid",
                    listings = listOf(
                        listing(StoreId.FDROID, "org.fdroid.fdroid", "F-Droid"),
                        listing(StoreId.AN1, "f-droid", "F-Droid"),
                    ),
                ),
            ),
        )
        // What a successful `refresh` leaves behind, **with no package name** — which is AN1's whole
        // point and the reason this row stays id-less. Its saved listing gets a `sig:` key from the
        // title, not the anchor's `pkg:` one, so it never becomes a sibling and the only place it is
        // known from is still the search's memory.
        details.onRefresh = { storeId, ref -> saveListing(storeId, ref.value, "F-Droid") }

        repository.readListings(StoreId.FDROID, StoreAppRef("org.fdroid.fdroid"))
        val row = repository.compare(StoreId.FDROID, StoreAppRef("org.fdroid.fdroid"))
            .first().rows.single { it.storeId == StoreId.AN1 }

        // Both halves. Before the fix the read happened and the cell reverted: the row had been
        // saved and had a size, and the lookup asking for it had no id to ask with.
        assertThat(details.refreshed).contains(StoreId.AN1 to "f-droid")
        assertThat(row.listingRead).isTrue()
    }

    /**
     * An [AppDetailRepository] that records what was asked of it and can be told to refuse.
     *
     * Local to this test rather than in `:core:testing`, because `:core:data` does not depend on it —
     * and what is needed here is two fields, not a double of the whole surface.
     */
    private class RecordingAppDetails : AppDetailRepository {
        val refreshed = mutableListOf<Pair<StoreId, String>>()
        val failing = mutableSetOf<StoreId>()

        /**
         * What a real `refresh` leaves behind, for the tests that need the **effect** and not only
         * the call: without it a success writes nothing, and a row can never stop being unread.
         */
        var onRefresh: (suspend (StoreId, StoreAppRef) -> Unit)? = null

        override suspend fun refresh(storeId: StoreId, ref: StoreAppRef, force: Boolean): Outcome<Unit> {
            refreshed += storeId to ref.value
            return if (storeId in failing) {
                Outcome.Failure(AppError.Network(cause = null))
            } else {
                onRefresh?.invoke(storeId, ref)
                Outcome.Success(Unit)
            }
        }

        override fun observe(storeId: StoreId, ref: StoreAppRef): Flow<AppDetail?> = flowOf(null)

        override suspend fun detail(storeId: StoreId, ref: StoreAppRef): AppDetail? = null

        override suspend fun loadVersionHistory(storeId: StoreId, ref: StoreAppRef): Outcome<Unit> =
            Outcome.Success(Unit)

        override suspend fun recordPermissions(
            storeId: StoreId,
            ref: StoreAppRef,
            versionRef: VersionRef,
            permissions: List<UsesPermission>,
        ) = Unit
    }

    /** Writes a row the way a **result list** produces one: no versions, and born already expired. */
    private suspend fun discoverListing(
        storeId: StoreId,
        ref: String,
        title: String,
        packageName: String?,
    ) {
        val summary = StoreListingSummary(
            storeId = storeId,
            ref = StoreAppRef(ref),
            title = title,
            packageName = packageName,
        )
        val rows = summary.toDiscoveredRows(
            appKey = AppKeys.of(packageName, title, null),
            confidence = 1.0f,
            method = MatchMethod.PACKAGE_NAME,
            now = now,
        )
        db.catalogDao().upsertApps(listOf(rows.app))
        db.catalogDao().insertListingIfAbsent(rows.listing)
    }

    private suspend fun saveListing(
        storeId: StoreId,
        ref: String,
        title: String,
        packageName: String? = null,
        versions: List<AppVersion> = emptyList(),
    ) {
        val detail = StoreListingDetail(
            summary = StoreListingSummary(
                storeId = storeId,
                ref = StoreAppRef(ref),
                title = title,
                packageName = packageName,
            ),
            versions = versions,
        )
        db.catalogDao().saveListings(listOf(detail.toRows(now, 6.hours)))
    }

    private companion object {
        const val APKCOMBO_REF = "antennapod/de.danoeh.antennapod"
    }
}

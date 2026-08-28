package com.multistore.core.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.multistore.core.common.identity.AppKeys
import com.multistore.core.data.FakeIndexedStoreAdapter
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
import com.multistore.core.model.VersionRef
import com.multistore.store.api.PagedResult
import com.multistore.store.api.SearchSource
import com.multistore.store.api.StoreResult
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
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
        repository = CrossStoreRepositoryImpl(
            catalogDao = db.catalogDao(),
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

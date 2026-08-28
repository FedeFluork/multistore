package com.multistore.core.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.multistore.core.common.result.Outcome
import com.multistore.core.common.version.VersionSelection
import com.multistore.core.data.FakeIndexedStoreAdapter
import com.multistore.core.data.store.StoreRegistry
import com.multistore.core.data.system.PackageEvents
import com.multistore.core.database.MultiStoreDatabase
import com.multistore.core.database.entity.AppEntity
import com.multistore.core.database.entity.AppVersionEntity
import com.multistore.core.database.entity.StoreListingEntity
import com.multistore.core.model.AppVersion
import com.multistore.core.model.ContentKind
import com.multistore.core.model.DeviceProfile
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.model.VersionRef
import com.multistore.store.api.SearchSource
import com.multistore.store.api.StoreError
import com.multistore.store.api.StoreResult
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The version history, and the preview-channels switch.
 *
 * Two things that existed from the start and nobody used, and which are the same defect seen from two
 * sides: the `versionHistory` capability was declared by eight adapters out of nine and read by
 * nobody; `VersionSelection.Request.allowNonDefaultChannels` existed and nobody switched it on.
 *
 * A class of its own and not inside `AppDetailRepositoryTest`, which is wired to a **local-index**
 * store: there `loadVersionHistory` exits immediately by construction, so every test in this file
 * would have passed without demonstrating anything.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class VersionHistoryRepositoryTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val store = StoreId.APKCOMBO
    private val ref = StoreAppRef("org.example.app")

    private val clock = Clock.System
    private lateinit var db: MultiStoreDatabase
    private lateinit var adapter: FakeIndexedStoreAdapter
    private val settings = LocalSettings()
    private lateinit var repository: AppDetailRepositoryImpl

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(context, MultiStoreDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        wire(FakeIndexedStoreAdapter(store, source = SearchSource.REMOTE))
        seedListing()
    }

    private fun wire(fake: FakeIndexedStoreAdapter) {
        adapter = fake
        val registry = StoreRegistry(setOf(fake))
        repository = AppDetailRepositoryImpl(
            registry = registry,
            catalogDao = db.catalogDao(),
            installedApps = InstalledAppsRepositoryImpl(
                context,
                db.installedAppDao(),
                db.catalogDao(),
                clock,
                Dispatchers.Unconfined,
            ),
            settings = settings,
            packageEvents = PackageEvents(context, CoroutineScope(Dispatchers.Unconfined)),
            health = StoreHealthRepositoryImpl(registry, db.storeDao(), clock, Dispatchers.Unconfined),
            device = DeviceProfile(sdkInt = 34, supportedAbis = listOf("arm64-v8a")),
            clock = clock,
            io = Dispatchers.Unconfined,
        )
    }

    /** The listing as `getAppDetails` leaves it: **a single** version, the current one. */
    private suspend fun seedListing() {
        val appKey = "pkg:${ref.value}"
        db.catalogDao().upsertApps(
            listOf(
                AppEntity(
                    appKey = appKey,
                    packageName = ref.value,
                    title = "Example",
                    titleNorm = "example",
                    contentKind = ContentKind.APP,
                    updatedAt = clock.now(),
                ),
            ),
        )
        db.catalogDao().saveListing(
            StoreListingEntity(
                appKey = appKey,
                storeId = store,
                storeAppRef = ref.value,
                title = "Example",
                titleNorm = "example",
                fetchedAt = clock.now(),
                ttlSeconds = 86_400,
            ),
            listOf(versionRow(code = 100)),
            emptyList(),
        )
    }

    private fun versionRow(code: Long) = AppVersionEntity(
        listingId = 0,
        versionRef = "v$code",
        versionName = code.toString(),
        versionCode = code,
        fetchedAt = Instant.DISTANT_PAST,
    )

    private fun version(code: Long, channels: Set<String> = emptySet()) = AppVersion(
        versionName = code.toString(),
        versionCode = code,
        ref = VersionRef("v$code"),
        releaseChannels = channels,
    )

    @After
    fun tearDown() = db.close()

    // --- The version history -----------------------------------------------------------------

    @Test
    fun `the history adds to what the listing had, it does not replace it`() = runTest {
        adapter.versionsResult = StoreResult.Success(listOf(version(90), version(80)))

        assertThat(repository.loadVersionHistory(store, ref)).isEqualTo(Outcome.Success(Unit))

        val versions = repository.detail(store, ref)!!.listing.versions.map { it.versionCode }
        assertThat(versions).containsExactly(100L, 90L, 80L)
    }

    /**
     * The unique index `(listing_id, version_ref)` does the work: the version both sources name is
     * replaced, not duplicated. Without it, the listing would show the same version twice — and it
     * would be the kind of defect one only notices by looking at the list.
     */
    @Test
    fun `a version the listing already had does not appear twice`() = runTest {
        adapter.versionsResult = StoreResult.Success(listOf(version(100), version(90)))

        repository.loadVersionHistory(store, ref)

        val versions = repository.detail(store, ref)!!.listing.versions.map { it.versionCode }
        assertThat(versions).containsExactly(100L, 90L)
    }

    /**
     * No "version history" tab where it is not supported. On an1, which publishes a listing and a
     * file, asking would be a request to a third-party site for a page that does not exist.
     */
    @Test
    fun `a store that does not declare the capability is not queried at all`() = runTest {
        wire(FakeIndexedStoreAdapter(store, source = SearchSource.REMOTE, versionHistory = false))
        adapter.versionsResult = StoreResult.Success(listOf(version(90)))

        assertThat(repository.loadVersionHistory(store, ref)).isEqualTo(Outcome.Success(Unit))

        assertThat(adapter.versionsAskedFor).isEmpty()
    }

    /**
     * On an indexed store the index already carries every version, and `getVersions` answers
     * `Unsupported`. Asking it would not be wrong: it would be pointless.
     */
    @Test
    fun `a local-index store is not queried`() = runTest {
        wire(FakeIndexedStoreAdapter(store, source = SearchSource.LOCAL_INDEX))

        assertThat(repository.loadVersionHistory(store, ref)).isEqualTo(Outcome.Success(Unit))

        assertThat(adapter.versionsAskedFor).isEmpty()
    }

    /**
     * A failure **is declared**, and that is not obvious: the other two early exits return `Success`.
     * The section opened because somebody opened it, and answering `Success` to a page that did not
     * answer would leave the listing's only version on screen as though it were the whole truth.
     */
    @Test
    fun `if the store does not answer the outcome is a failure, not silence`() = runTest {
        adapter.versionsResult = StoreResult.Failure(StoreError.NotFound)

        assertThat(repository.loadVersionHistory(store, ref)).isInstanceOf(Outcome.Failure::class.java)
    }

    @Test
    fun `a listing not in the catalogue does not cause orphan versions to be written`() = runTest {
        adapter.versionsResult = StoreResult.Success(listOf(version(90)))

        val outcome = repository.loadVersionHistory(store, StoreAppRef("org.example.absent"))

        assertThat(outcome).isEqualTo(Outcome.Success(Unit))
        assertThat(repository.detail(store, ref)!!.listing.versions).hasSize(1)
    }

    // --- The per-row verdict -----------------------------------------------------------------

    @Test
    fun `the versions arrive newest first, with their verdict`() = runTest {
        adapter.versionsResult = StoreResult.Success(listOf(version(80), version(120), version(90)))
        repository.loadVersionHistory(store, ref)

        val offers = repository.detail(store, ref)!!.versions

        assertThat(offers.map { it.version.versionCode })
            .containsExactly(120L, 100L, 90L, 80L)
            .inOrder()
        // Nothing installed: all of them can be taken.
        assertThat(offers.map { it.installability }.toSet())
            .containsExactly(VersionSelection.Installability.INSTALLABLE)
    }

    // --- The preview channels ----------------------------------------------------------------

    /**
     * The switch is read by the **repository**, not by the caller: it is the same choice as the adult
     * filter in `SearchRepositoryImpl`. Passed as a parameter it would be a parameter somebody
     * eventually forgets, and forgetting produces no error — only a beta offered to whoever did not
     * ask for it, or not offered to whoever did.
     */
    @Test
    fun `with the switch off an app existing only in beta is not offered`() = runTest {
        adapter.versionsResult = StoreResult.Success(listOf(version(130, channels = setOf("Beta"))))
        repository.loadVersionHistory(store, ref)

        val selection = repository.detail(store, ref)!!.selection

        // 100 is in the default channel and stays the offer: the higher beta does not overtake it.
        assertThat((selection as VersionSelection.Outcome.Offer).version.versionCode).isEqualTo(100)
    }

    @Test
    fun `switched on, the beta becomes the one offered`() = runTest {
        adapter.versionsResult = StoreResult.Success(listOf(version(130, channels = setOf("Beta"))))
        repository.loadVersionHistory(store, ref)
        settings.setAllowPreviewChannels(true)

        val selection = repository.detail(store, ref)!!.selection

        assertThat((selection as VersionSelection.Outcome.Offer).version.versionCode).isEqualTo(130)
    }

    /**
     * The case `OnlyOtherChannels` exists to describe: **nothing** in the default channel. It is also
     * what makes the sign on the listing different from "this store has no package for this app".
     */
    @Test
    fun `beta only and switch off - the outcome names the channel instead of saying nothing`() =
        runTest {
            db.catalogDao().clearListings(store)
            db.catalogDao().saveListing(
                StoreListingEntity(
                    appKey = "pkg:${ref.value}",
                    storeId = store,
                    storeAppRef = ref.value,
                    title = "Example",
                    titleNorm = "example",
                    fetchedAt = clock.now(),
                    ttlSeconds = 86_400,
                ),
                listOf(versionRow(code = 130).copy(releaseChannels = listOf("Beta"))),
                emptyList(),
            )

            val selection = repository.detail(store, ref)!!.selection

            assertThat(selection).isInstanceOf(VersionSelection.Outcome.OnlyOtherChannels::class.java)
            assertThat((selection as VersionSelection.Outcome.OnlyOtherChannels).channels)
                .containsExactly("Beta")
        }
}

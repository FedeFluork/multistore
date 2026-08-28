package com.multistore.core.data.repository

import android.content.pm.PackageInfo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.multistore.core.common.version.VersionSelection
import com.multistore.core.data.FakeIndexedStoreAdapter
import com.multistore.core.data.FakeIndexedStoreAdapter.Companion.FIELD_TITLE
import com.multistore.core.data.FakeIndexedStoreAdapter.Companion.FIELD_VERSION_CODE
import com.multistore.core.data.FakeSnapshot
import com.multistore.core.data.store.StoreRegistry
import com.multistore.core.data.system.PackageEvents
import com.multistore.core.database.MultiStoreDatabase
import com.multistore.core.database.entity.AppEntity
import com.multistore.core.database.entity.AppVersionEntity
import com.multistore.core.database.entity.StoreListingEntity
import com.multistore.core.model.ContentKind
import com.multistore.core.model.InstallerKind
import com.multistore.core.model.DeviceProfile
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.common.result.Outcome
import com.multistore.store.api.IndexRecord
import com.multistore.store.api.IndexSyncMode
import com.multistore.store.api.IndexToken
import com.multistore.store.api.StoreResult
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The detail listing: the **second** point where `searchSource` forks the code.
 *
 * What has to be demonstrated here is above all a not-doing: on an indexed store, refreshing the
 * listing is not a network request, because a listing's freshness depends on the whole index. A
 * `refresh` trying to ask for the single page would find `Unsupported` and would show an error for an
 * operation that should never have begun.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AppDetailRepositoryTest {

    private lateinit var db: MultiStoreDatabase
    private lateinit var adapter: FakeIndexedStoreAdapter
    private lateinit var installed: InstalledAppsRepositoryImpl
    /**
     * The preview channels off, which is the default: the tests that want them on say so.
     */
    private val settings = LocalSettings()

    private lateinit var repository: AppDetailRepositoryImpl

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private var currentTime = Instant.fromEpochMilliseconds(1_787_316_712_615L)
    private val clock = object : Clock {
        override fun now(): Instant = currentTime
    }

    private val store = StoreId.FDROID
    private val ref = StoreAppRef("org.example.app")

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(context, MultiStoreDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        adapter = FakeIndexedStoreAdapter(store, ttl = 7.days)
        val registry = StoreRegistry(setOf(adapter))
        installed = InstalledAppsRepositoryImpl(context, db.installedAppDao(), db.catalogDao(), clock, Dispatchers.Unconfined)
        repository = AppDetailRepositoryImpl(
            registry = registry,
            catalogDao = db.catalogDao(),
            installedApps = installed,
            settings = settings,
            // A real receiver would need a Looper: here the packages flow has no events, and the
            // listing has to emit all the same thanks to the initial value.
            packageEvents = PackageEvents(context, CoroutineScope(Dispatchers.Unconfined)),
            health = StoreHealthRepositoryImpl(registry, db.storeDao(), clock, Dispatchers.Unconfined),
            device = DeviceProfile(sdkInt = 34, supportedAbis = listOf("arm64-v8a")),
            clock = clock,
            io = Dispatchers.Unconfined,
        )

        val index = StoreIndexRepositoryImpl(
            registry = registry,
            indexDao = db.indexDao(),
            catalogDao = db.catalogDao(),
            health = StoreHealthRepositoryImpl(registry, db.storeDao(), clock, Dispatchers.Unconfined),
            clock = clock,
            io = Dispatchers.Unconfined,
        )
        val payload = FakeIndexedStoreAdapter.payload(
            ref.value,
            FIELD_TITLE to "Example",
            FIELD_VERSION_CODE to "10",
        )
        adapter.nextSnapshot = {
            StoreResult.Success(
                FakeSnapshot(
                    IndexToken("1"),
                    IndexSyncMode.FULL,
                    listOf(IndexRecord.Full(ref, payload, adapter.projectEntry(payload))),
                ),
            )
        }
        index.sync(store)
    }

    @After
    fun tearDown() = db.close()

    /** A listing from a store that does not publish the `packageName`. */
    private suspend fun anonymousListing(anonymous: StoreAppRef, versionCode: Long) {
        val appKey = "sig:${anonymous.value}"
        db.catalogDao().upsertApps(
            listOf(
                AppEntity(
                    appKey = appKey,
                    packageName = null,
                    title = "Example MOD",
                    titleNorm = "example mod",
                    contentKind = ContentKind.APP,
                    updatedAt = clock.now(),
                ),
            ),
        )
        db.catalogDao().saveListing(
            StoreListingEntity(
                appKey = appKey,
                storeId = StoreId.APKMODY,
                storeAppRef = anonymous.value,
                title = "Example MOD",
                titleNorm = "example mod",
                fetchedAt = clock.now(),
                ttlSeconds = 86_400,
            ),
            listOf(
                AppVersionEntity(
                    listingId = 0,
                    versionRef = "v$versionCode",
                    versionName = versionCode.toString(),
                    versionCode = versionCode,
                    fetchedAt = clock.now(),
                ),
            ),
            emptyList(),
        )
    }

    private fun install(versionCode: Long) {
        shadowOf(context.packageManager).installPackage(
            PackageInfo().apply {
                packageName = ref.value
                versionName = versionCode.toString()
                longVersionCode = versionCode
            },
        )
    }

    @Test
    fun `app not installed - the version is offered, and it is not an update`() = runTest {
        val detail = repository.detail(store, ref)

        assertThat(detail?.listing?.summary?.title).isEqualTo("Example")
        val offer = detail?.selection as VersionSelection.Outcome.Offer
        assertThat(offer.isUpdate).isFalse()
        assertThat(offer.version.versionCode).isEqualTo(10)
    }

    @Test
    fun `installed version older - it is offered as an update`() = runTest {
        install(versionCode = 5)

        val detail = repository.detail(store, ref)

        assertThat(detail?.installed?.versionCode).isEqualTo(5)
        assertThat((detail?.selection as VersionSelection.Outcome.Offer).isUpdate).isTrue()
    }

    @Test
    fun `installed version already current - nothing to do`() = runTest {
        install(versionCode = 10)

        val detail = repository.detail(store, ref)

        assertThat(detail?.selection).isInstanceOf(VersionSelection.Outcome.UpToDate::class.java)
    }

    @Test
    fun `a store that does not publish the packageName still recognises the installed app`() = runTest {
        // apkmody, apkcombo, an1 and pdalife do not publish the `packageName`. Starting from their
        // listing there is no telling which package to query, and the listing would say "Install" to
        // somebody who already has the app — forever, because there is nothing that could make it
        // change its mind. But we do know the name: the APK told us at installation.
        val anonymous = StoreAppRef("example-mod")
        anonymousListing(anonymous, versionCode = 20)
        install(versionCode = 12)
        installed.record(
            packageName = ref.value,
            label = "Example",
            storeId = StoreId.APKMODY,
            ref = anonymous,
            listingId = null,
            apkSha256 = null,
            installerKind = InstallerKind.SESSION,
        )

        val detail = repository.detail(StoreId.APKMODY, anonymous)

        assertThat(detail?.listing?.summary?.packageName).isNull()
        assertThat(detail?.installed?.versionCode).isEqualTo(12)
        assertThat((detail?.selection as VersionSelection.Outcome.Offer).isUpdate).isTrue()
    }

    @Test
    fun `the user's pin is visible on the listing too, not only in the periodic check`() =
        runTest {
            install(versionCode = 5)
            installed.record(
                packageName = ref.value,
                label = "Example",
                storeId = store,
                ref = ref,
                listingId = null,
                apkSha256 = null,
                installerKind = InstallerKind.SESSION,
            )
            installed.setPinnedVersionCode(ref.value, 5)

            val detail = repository.detail(store, ref)

            // Without this read the listing would offer "Update" on the same app the periodic check
            // leaves alone: two different answers to the same question.
            val pinned = detail?.selection as VersionSelection.Outcome.Pinned
            assertThat(pinned.heldBack.versionCode).isEqualTo(10)
            assertThat(pinned.offer).isNull()
        }

    @Test
    fun `on an indexed store the refresh does not ask for the single page`() = runTest {
        val outcome = repository.refresh(store, ref, force = true)

        assertThat(outcome).isInstanceOf(Outcome.Success::class.java)
        // `getAppDetails` answers `Unsupported` by construction: if the branch called it, the screen
        // would show an error for an operation that should never have started.
        assertThat(adapter.detailResult).isEqualTo(StoreResult.Unsupported)
    }

    @Test
    fun `past the TTL the listing is shown all the same, flagged as stale`() = runTest {
        assertThat(repository.detail(store, ref)?.stale).isFalse()

        currentTime += 8.days

        // Stale-while-revalidate: expired data is shown immediately and flagged, not hidden.
        assertThat(repository.detail(store, ref)?.stale).isTrue()
    }

    @Test
    fun `a listing that does not exist is null, not an error`() = runTest {
        assertThat(repository.detail(store, StoreAppRef("org.example.absent"))).isNull()
    }
}

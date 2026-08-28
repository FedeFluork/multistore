package com.multistore.core.data.repository

import android.content.Context
import android.content.pm.PackageInfo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.multistore.core.common.result.AppError
import com.multistore.core.common.result.Outcome
import com.multistore.core.data.store.StoreRegistry
import com.multistore.core.data.system.PackageEvents
import com.multistore.core.database.MultiStoreDatabase
import com.multistore.core.database.entity.AppEntity
import com.multistore.core.database.entity.AppVersionEntity
import com.multistore.core.database.entity.StoreListingEntity
import com.multistore.core.model.ContentKind
import com.multistore.core.model.DeviceProfile
import com.multistore.core.model.InstallerKind
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.data.FakeIndexedStoreAdapter
import com.multistore.store.api.IndexSyncMode
import com.multistore.store.api.SearchSource
import com.multistore.store.api.StoreAdapter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The update check, and the two columns nobody used to read.
 *
 * `ignore_updates` and `pinned_version_code` were written from day one and entered no decision. It
 * was not a defect while there was no periodic check — there was nothing that could ignore them — and
 * it became one the moment there was. The tests that count here are those: removing either read, they
 * have to turn red.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class UpdateRepositoryTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(1_787_316_712_615L)
    }
    private val device = DeviceProfile(sdkInt = 36, supportedAbis = listOf("arm64-v8a"))
    private val settings = LocalSettings()

    private lateinit var db: MultiStoreDatabase
    private lateinit var installedApps: InstalledAppsRepositoryImpl
    private lateinit var details: RecordingDetails
    private lateinit var index: RecordingIndex

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, MultiStoreDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        installedApps = InstalledAppsRepositoryImpl(
            context,
            db.installedAppDao(),
            db.catalogDao(),
            clock,
            Dispatchers.Unconfined,
        )
        details = RecordingDetails()
        index = RecordingIndex()
    }

    @After
    fun tearDown() = db.close()

    // --- what comes out updatable -------------------------------------------------------------

    @Test
    fun `a higher versionCode on the channel is an update`() = runTest {
        install("org.example.app", versionCode = 100)
        listing(StoreId.FDROID, "org.example.app", packageName = "org.example.app", versions = listOf(100, 120))
        record("org.example.app", StoreId.FDROID, "org.example.app")

        val available = repository().observeAvailable().first()

        assertThat(available.map { it.app.packageName }).containsExactly("org.example.app")
        assertThat(available.single().available?.versionCode).isEqualTo(120)
    }

    @Test
    fun `with nothing newer there is nothing to offer`() = runTest {
        install("org.example.app", versionCode = 120)
        listing(StoreId.FDROID, "org.example.app", packageName = "org.example.app", versions = listOf(100, 120))
        record("org.example.app", StoreId.FDROID, "org.example.app")

        assertThat(repository().observeAvailable().first()).isEmpty()
    }

    // --- `ignore_updates`, finally read -------------------------------------------------------

    @Test
    fun `a paused app does not appear among the available updates`() = runTest {
        install("org.example.app", versionCode = 100)
        listing(StoreId.FDROID, "org.example.app", packageName = "org.example.app", versions = listOf(100, 120))
        record("org.example.app", StoreId.FDROID, "org.example.app")
        installedApps.setIgnoreUpdates("org.example.app", ignore = true)

        val all = repository().observeAll().first()

        // The update **exists** and the row has to know it: it is the difference between "there is
        // nothing" and "there is, but you paused it", and without the second the user would never
        // remember they have a pause active.
        assertThat(all.single().available).isNull()
        assertThat(all.single().suppressed).isTrue()
        assertThat(repository().observeAvailable().first()).isEmpty()
    }

    @Test
    fun `a paused app does not even make the request to the store`() = runTest {
        install("org.example.app", versionCode = 100)
        listing(StoreId.APKMIRROR, "app/example", packageName = "org.example.app", versions = listOf(100))
        record("org.example.app", StoreId.APKMIRROR, "app/example")
        installedApps.setIgnoreUpdates("org.example.app", ignore = true)

        val report = repository(remoteStores = setOf(StoreId.APKMIRROR)).check()

        // Silencing a notice and not making the request are two things, and the second is the one
        // concerning the store on the other side: asking for a page for an app we will say nothing
        // about is traffic that serves nobody.
        assertThat(details.refreshes).isEqualTo(0)
        assertThat(report.checked).isEqualTo(0)
    }

    // --- the version pin, finally read -------------------------------------------------------

    @Test
    fun `a pinned app does not offer the version beyond the pin`() = runTest {
        install("org.example.app", versionCode = 100)
        listing(StoreId.FDROID, "org.example.app", packageName = "org.example.app", versions = listOf(100, 120))
        record("org.example.app", StoreId.FDROID, "org.example.app")
        installedApps.setPinnedVersionCode("org.example.app", 100)

        assertThat(repository().observeAvailable().first()).isEmpty()
    }

    @Test
    fun `an app pinned above the installed version still offers the pin`() = runTest {
        install("org.example.app", versionCode = 90)
        listing(StoreId.FDROID, "org.example.app", packageName = "org.example.app", versions = listOf(90, 100, 120))
        record("org.example.app", StoreId.FDROID, "org.example.app")
        installedApps.setPinnedVersionCode("org.example.app", 100)

        // "No further than 100" is not "stay at 90".
        assertThat(repository().observeAvailable().first().single().available?.versionCode)
            .isEqualTo(100)
    }

    // --- the multi-store rule -----------------------------------------------------------------

    @Test
    fun `the update comes from the registered channel, not from the store with the highest number`() =
        runTest {
            install("org.example.app", versionCode = 100)
            listing(StoreId.FDROID, "org.example.app", packageName = "org.example.app", versions = listOf(100))
            listing(StoreId.APKMIRROR, "app/example", packageName = "org.example.app", versions = listOf(200))
            record("org.example.app", StoreId.FDROID, "org.example.app")

            // apkmirror has 200. It is not offered: two stores redistributing the same app almost
            // never sign it with the same key, and an update with a different signature is refused by
            // the operating system, not by us.
            assertThat(repository().observeAvailable().first()).isEmpty()
        }

    @Test
    fun `changing channel changes where the update comes from`() = runTest {
        install("org.example.app", versionCode = 100)
        listing(StoreId.FDROID, "org.example.app", packageName = "org.example.app", versions = listOf(100))
        listing(StoreId.APKMIRROR, "app/example", packageName = "org.example.app", versions = listOf(200))
        record("org.example.app", StoreId.FDROID, "org.example.app")

        installedApps.setUpdateChannel("org.example.app", StoreId.APKMIRROR, StoreAppRef("app/example"))

        val available = repository().observeAvailable().first().single()
        assertThat(available.available?.versionCode).isEqualTo(200)
        assertThat(available.channel?.storeId).isEqualTo(StoreId.APKMIRROR)
    }

    // --- the stores that do not publish the packageName ---------------------------------------

    @Test
    fun `an app from a store with no packageName is recognised all the same`() = runTest {
        install("com.example.mod", versionCode = 5)
        // Four stores out of nine do not publish the packageName: the listing does not know what the
        // package is called, so it cannot query the system. But we do know the name — the APK told us
        // at installation time — and it is what makes the row recognisable instead of "not
        // installed".
        listing(StoreId.APKMODY, "example-mod", packageName = null, versions = listOf(5, 7))
        record("com.example.mod", StoreId.APKMODY, "example-mod")

        assertThat(repository().observeAvailable().first().single().available?.versionCode)
            .isEqualTo(7)
    }

    // --- what gets queried --------------------------------------------------------------------

    @Test
    fun `an indexed store syncs once, not once per app`() = runTest {
        repeat(3) { i ->
            install("org.example.app$i", versionCode = 1)
            listing(StoreId.FDROID, "org.example.app$i", packageName = "org.example.app$i", versions = listOf(1))
            record("org.example.app$i", StoreId.FDROID, "org.example.app$i")
        }
        index.result = Outcome.Success(syncReport(StoreId.FDROID))

        val report = repository().check()

        assertThat(index.syncs).isEqualTo(1)
        assertThat(details.refreshes).isEqualTo(0)
        assertThat(report.checked).isEqualTo(3)
    }

    @Test
    fun `a store that does not answer does not stop the others`() = runTest {
        install("org.example.remote", versionCode = 1)
        listing(StoreId.APKMIRROR, "app/remote", packageName = "org.example.remote", versions = listOf(1))
        record("org.example.remote", StoreId.APKMIRROR, "app/remote")

        install("org.example.indexed", versionCode = 1)
        listing(StoreId.FDROID, "org.example.indexed", packageName = "org.example.indexed", versions = listOf(1))
        record("org.example.indexed", StoreId.FDROID, "org.example.indexed")

        details.refreshResult = Outcome.Failure(AppError.RateLimited(null))
        index.result = Outcome.Success(syncReport(StoreId.FDROID))

        val report = repository(remoteStores = setOf(StoreId.APKMIRROR)).check()

        assertThat(report.failures.keys).containsExactly(StoreId.APKMIRROR)
        assertThat(report.checked).isEqualTo(1)
        assertThat(report.complete).isFalse()
    }

    // --- scaffolding --------------------------------------------------------------------------

    private fun repository(remoteStores: Set<StoreId> = emptySet()): UpdateRepositoryImpl {
        val adapters = buildSet<StoreAdapter> {
            add(FakeIndexedStoreAdapter(id = StoreId.FDROID))
            remoteStores.forEach {
                add(FakeIndexedStoreAdapter(id = it, source = SearchSource.REMOTE))
            }
        }
        return UpdateRepositoryImpl(
            installedApps = installedApps,
            settings = settings,
            details = details,
            index = index,
            registry = StoreRegistry(adapters),
            catalogDao = db.catalogDao(),
            packageEvents = PackageEvents(context, CoroutineScope(Dispatchers.Unconfined)),
            device = device,
            io = Dispatchers.Unconfined,
        )
    }

    /**
     * The listing's double: it counts the refreshes and answers what the test decided.
     *
     * Local and not taken from `:core:testing`, because that module depends on `:core:data` and
     * `:core:data`'s tests cannot depend on it: it is the same reason a `FakeIndexedStoreAdapter` of
     * its own already lives next to this file.
     */
    private class RecordingDetails : AppDetailRepository {
        var refreshes = 0
            private set
        val refreshed = mutableListOf<Pair<StoreId, StoreAppRef>>()
        var refreshResult: Outcome<Unit> = Outcome.Success(Unit)

        override fun observe(storeId: StoreId, ref: StoreAppRef): Flow<AppDetail?> = flowOf(null)

        override suspend fun detail(storeId: StoreId, ref: StoreAppRef): AppDetail? = null

        override suspend fun loadVersionHistory(
            storeId: StoreId,
            ref: StoreAppRef,
        ): Outcome<Unit> = Outcome.Success(Unit)

        override suspend fun refresh(
            storeId: StoreId,
            ref: StoreAppRef,
            force: Boolean,
        ): Outcome<Unit> {
            refreshes++
            refreshed += storeId to ref
            return refreshResult
        }
    }

    /** The fake index: it counts syncs, which is the only thing that matters here. */
    private class RecordingIndex : StoreIndexRepository {
        var syncs = 0
            private set
        var result: Outcome<IndexSyncReport> = Outcome.Failure(AppError.NotFound)

        override fun observeState(storeId: StoreId): Flow<IndexState?> = flowOf(null)

        override suspend fun state(storeId: StoreId): IndexState? = null

        override fun observeTaxonomy(storeId: StoreId): Flow<StoreTaxonomy> =
            flowOf(StoreTaxonomy())

        override suspend fun taxonomy(storeId: StoreId): StoreTaxonomy = StoreTaxonomy()

        override suspend fun sync(
            storeId: StoreId,
            force: Boolean,
            onProgress: (IndexSyncProgress) -> Unit,
        ): Outcome<IndexSyncReport> {
            syncs++
            return result
        }
    }

    private fun syncReport(storeId: StoreId) = IndexSyncReport(
        storeId = storeId,
        mode = IndexSyncMode.FULL,
        written = 0,
        removed = 0,
        token = "t",
        upToDate = true,
    )

    private fun install(packageName: String, versionCode: Long) {
        shadowOf(context.packageManager).installPackage(
            PackageInfo().apply {
                this.packageName = packageName
                versionName = versionCode.toString()
                longVersionCode = versionCode
            },
        )
    }

    private suspend fun listing(
        storeId: StoreId,
        ref: String,
        packageName: String?,
        versions: List<Long>,
    ) {
        val appKey = packageName?.let { "pkg:$it" } ?: "sig:$ref"
        db.catalogDao().upsertApps(
            listOf(
                AppEntity(
                    appKey = appKey,
                    packageName = packageName,
                    title = ref,
                    titleNorm = ref,
                    contentKind = ContentKind.APP,
                    updatedAt = clock.now(),
                ),
            ),
        )
        db.catalogDao().saveListing(
            StoreListingEntity(
                appKey = appKey,
                storeId = storeId,
                storeAppRef = ref,
                title = ref,
                titleNorm = ref,
                fetchedAt = clock.now(),
                ttlSeconds = 86_400,
            ),
            versions.map { code ->
                AppVersionEntity(
                    listingId = 0,
                    versionRef = "v$code",
                    versionName = code.toString(),
                    versionCode = code,
                    fetchedAt = clock.now(),
                )
            },
            emptyList(),
        )
    }

    private suspend fun record(packageName: String, storeId: StoreId, ref: String) =
        installedApps.record(
            packageName = packageName,
            label = packageName,
            storeId = storeId,
            ref = StoreAppRef(ref),
            listingId = null,
            apkSha256 = null,
            installerKind = InstallerKind.SESSION,
        )
}

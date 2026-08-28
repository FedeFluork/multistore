package com.multistore.core.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.multistore.core.data.FakeIndexedStoreAdapter
import com.multistore.core.data.store.StoreRegistry
import com.multistore.core.database.DatabaseMaintenance
import com.multistore.core.database.MultiStoreDatabase
import com.multistore.core.database.entity.AppEntity
import com.multistore.core.database.entity.DownloadEntity
import com.multistore.core.database.entity.IdentityOverrideEntity
import com.multistore.core.database.entity.InstalledAppEntity
import com.multistore.core.database.entity.StoreIndexEntryEntity
import com.multistore.core.database.entity.StoreIndexStateEntity
import com.multistore.core.database.entity.StoreListingEntity
import com.multistore.core.model.CatalogRetention
import com.multistore.core.model.StorageLevel
import com.multistore.core.model.StoreId
import com.multistore.core.network.http.NetworkEnvironment
import com.multistore.core.network.http.StoreHttpClients
import com.multistore.store.api.SearchSource
import java.io.File
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The purge: what it removes, and above all what it does **not**.
 *
 * The query's six protections are six separate tests, and not one with six lines: a clause that stops
 * working has to say which. They are also the six ways this operation can do damage instead of
 * freeing space — an update channel detached, a user decision undone, a download in progress left
 * without a listing — and none of the six would produce a visible error: only an app that at some
 * point stops updating something.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MaintenanceRepositoryTest {

    private lateinit var db: MultiStoreDatabase
    private lateinit var clients: StoreHttpClients
    private lateinit var repository: MaintenanceRepositoryImpl

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** A fixed instant: the purge compares two dates, and with a real clock the test flickers. */
    private val now = Instant.fromEpochMilliseconds(1_787_747_676_000L)
    private val clock = object : Clock {
        override fun now(): Instant = now
    }

    private val settings = LocalSettings()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, MultiStoreDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        clients = StoreHttpClients(
            NetworkEnvironment(cacheDirectory = File(context.cacheDir, "http-test")),
        )
        repository = MaintenanceRepositoryImpl(
            context = context,
            maintenance = DatabaseMaintenance(db, context, Dispatchers.Unconfined),
            catalogDao = db.catalogDao(),
            indexDao = db.indexDao(),
            downloadDao = db.downloadDao(),
            // F-Droid is the only local-index store, and it is exactly the case the purge has to
            // spare: its listings are projections, not downloaded pages.
            registry = StoreRegistry(
                setOf(
                    FakeIndexedStoreAdapter(StoreId.FDROID),
                    // apkmirror declares `REMOTE` while implementing the interface: it is exactly what
                    // `StoreRegistry.indexed` has to **not** recognise as an indexed store, and it is
                    // the case this purge has to tell it apart from.
                    FakeIndexedStoreAdapter(StoreId.APKMIRROR, source = SearchSource.REMOTE),
                ),
            ),
            settings = settings,
            httpClients = clients,
            imageCache = ImageCache.NONE,
            clock = clock,
            io = Dispatchers.Unconfined,
        )
        Staging.dir(context).listFiles()?.forEach { it.deleteRecursively() }
    }

    @After
    fun tearDown() {
        clients.shutdown()
        db.close()
    }

    // --- the automatic purge ------------------------------------------------------------------

    @Test
    fun `a scraped listing expired for longer than the retention goes away`() = runTest {
        val id = insertListing(StoreId.APKMIRROR, ref = "vecchia", expiredDaysAgo = 40)

        assertThat(repository.purgeStale().listings).isEqualTo(1)
        assertThat(listingIds()).doesNotContain(id)
    }

    @Test
    fun `a listing expired for less than the retention stays`() = runTest {
        // Expired, but only ten days ago: the search still shows it immediately while re-downloading,
        // and it is why the retention is not zero.
        val id = insertListing(StoreId.APKMIRROR, ref = "recente", expiredDaysAgo = 10)

        assertThat(repository.purgeStale().listings).isEqualTo(0)
        assertThat(listingIds()).contains(id)
    }

    @Test
    fun `a local-index store's listing is never touched`() = runTest {
        // On F-Droid the listings are projections of the index: throwing them away does not free the
        // 37 MB of `store_index_entries` and breaks the search until the next sync.
        val id = insertListing(StoreId.FDROID, ref = "org.fdroid.fdroid", expiredDaysAgo = 400)

        assertThat(repository.purgeStale().listings).isEqualTo(0)
        assertThat(listingIds()).contains(id)
    }

    @Test
    fun `the listing that is an installed app's update channel stays`() = runTest {
        val id = insertListing(StoreId.APKMIRROR, ref = "canale", expiredDaysAgo = 400)
        db.installedAppDao().upsert(installed(packageName = "org.example.canale", channelListingId = id))

        assertThat(repository.purgeStale().listings).isEqualTo(0)
        assertThat(listingIds()).contains(id)
    }

    @Test
    fun `the listing an installed app came from stays`() = runTest {
        // `installed_apps` remembers provenance as (store, ref) and not as a row id: it is a second
        // key, and one clause alone would cover half of it.
        val id = insertListing(StoreId.APKMIRROR, ref = "provenienza", expiredDaysAgo = 400)
        db.installedAppDao().upsert(
            installed(
                packageName = "org.example.provenienza",
                sourceStore = StoreId.APKMIRROR,
                sourceRef = "provenienza",
            ),
        )

        assertThat(repository.purgeStale().listings).isEqualTo(0)
        assertThat(listingIds()).contains(id)
    }

    @Test
    fun `a queued download's listing stays`() = runTest {
        val id = insertListing(StoreId.APKMIRROR, ref = "in-download", expiredDaysAgo = 400)
        db.downloadDao().upsert(download(listingId = id, path = null))

        assertThat(repository.purgeStale().listings).isEqualTo(0)
        assertThat(listingIds()).contains(id)
    }

    @Test
    fun `the listing the user decided a match on stays`() = runTest {
        // It is the only one of the protections defending a **decision**: throwing it away would ask
        // the same question again at the next search.
        val id = insertListing(StoreId.APKMIRROR, ref = "confermata", expiredDaysAgo = 400)
        db.catalogDao().upsertIdentityOverride(
            IdentityOverrideEntity(listingId = id, appKey = "pkg:org.example.confermata", action = "confirmed"),
        )

        assertThat(repository.purgeStale().listings).isEqualTo(0)
        assertThat(listingIds()).contains(id)
    }

    @Test
    fun `with 'forever' nothing is thrown away`() = runTest {
        val id = insertListing(StoreId.APKMIRROR, ref = "eterna", expiredDaysAgo = 4000)
        settings.setCatalogRetention(CatalogRetention.KEEP)

        assertThat(repository.purgeStale().listings).isEqualTo(0)
        assertThat(listingIds()).contains(id)
    }

    // --- the staging area ---------------------------------------------------------------------

    @Test
    fun `a MultiStore update's APK goes away at the first launch afterwards`() = runTest {
        // No row in `downloads` names it, and none ever will: `InstallSelfUpdateUseCase` writes
        // directly into staging, and after the commit the process is killed. Without this sweep that
        // file stays there forever — 28.2 MB measured on the device.
        val orfano = File(Staging.dir(context), "multistore-update.apk").apply { writeBytes(ByteArray(2048)) }

        val purged = repository.purgeStale()

        assertThat(orfano.exists()).isFalse()
        assertThat(purged.stagedFiles).isEqualTo(1)
        assertThat(purged.freedBytes).isEqualTo(2048)
    }

    @Test
    fun `the automatic purge does not touch a file a row claims`() = runTest {
        val id = insertListing(StoreId.APKMIRROR, ref = "scaricata", expiredDaysAgo = 0)
        val tenuto = File(Staging.dir(context), "1.apk").apply { writeBytes(ByteArray(1024)) }
        db.downloadDao().upsert(download(listingId = id, path = tenuto.absolutePath))

        assertThat(repository.purgeStale().stagedFiles).isEqualTo(0)
        assertThat(tenuto.exists()).isTrue()
    }

    @Test
    fun `a split directory opened and abandoned goes away`() = runTest {
        // Staging no longer holds only files: opening a container leaves a directory. A sweep
        // filtering on `isFile` would ignore it **forever** — on Firefox that is 250 MB — and nobody
        // would notice, because no file manager opens that directory.
        val opened = File(Staging.dir(context), "9.split").apply { mkdirs() }
        File(opened, "base.apk").writeBytes(ByteArray(3000))
        File(opened, "config.arm64_v8a.apk").writeBytes(ByteArray(1000))

        val purged = repository.purgeStale()

        assertThat(opened.exists()).isFalse()
        assertThat(purged.freedBytes).isEqualTo(4000)
    }

    @Test
    fun `a live download's directory stays, together with its file`() = runTest {
        val id = insertListing(StoreId.APKMIRROR, ref = "in-corso", expiredDaysAgo = 0)
        val downloaded = File(Staging.dir(context), "7.apk").apply { writeBytes(ByteArray(1024)) }
        val opened = Staging.splitsOf(downloaded).apply { mkdirs() }
        File(opened, "base.apk").writeBytes(ByteArray(2048))
        db.downloadDao().upsert(download(listingId = id, path = downloaded.absolutePath))

        repository.purgeStale()

        // The "downloaded file / its directory" pair is known by [Staging] and nobody else: if the
        // two rules diverged, the symptom would be the extraction thrown away halfway through an
        // installation, and the cause would be elsewhere.
        assertThat(downloaded.exists()).isTrue()
        assertThat(opened.exists()).isTrue()
    }

    @Test
    fun `the button throws away the completed APKs and spares those in progress`() = runTest {
        val id = insertListing(StoreId.APKMIRROR, ref = "mista", expiredDaysAgo = 0)
        val concluso = File(Staging.dir(context), "concluso.apk").apply { writeBytes(ByteArray(4096)) }
        val inCorso = File(Staging.dir(context), "in-corso.apk").apply { writeBytes(ByteArray(512)) }
        db.downloadDao().upsert(
            download(listingId = id, path = concluso.absolutePath, state = com.multistore.core.model.DownloadState.DONE),
        )
        db.downloadDao().upsert(
            download(
                listingId = id,
                path = inCorso.absolutePath,
                ref = "in-corso",
                state = com.multistore.core.model.DownloadState.PAUSED,
            ),
        )

        val reclaimed = repository.clear(StorageLevel.STAGED_APKS)

        // A paused transfer has a partial file needed for resumption: deleting it would turn a
        // cleanup into eighteen megabytes to re-download.
        assertThat(inCorso.exists()).isTrue()
        assertThat(concluso.exists()).isFalse()
        assertThat(reclaimed.freedBytes).isEqualTo(4096)
        // The completed row goes **after** the file: a row with no file would offer "Install" on
        // nothing.
        assertThat(db.downloadDao().claimedFilePaths()).containsExactly(inCorso.absolutePath)
    }

    // --- the "empty the catalogue" button ------------------------------------------------------

    @Test
    fun `emptying the catalogue deletes the index's state too`() = runTest {
        insertListing(StoreId.FDROID, ref = "org.fdroid.fdroid", expiredDaysAgo = 0)
        db.indexDao().upsertEntries(
            listOf(StoreIndexEntryEntity(StoreId.FDROID, "org.fdroid.fdroid", "{}", now)),
        )
        db.indexDao().upsertState(
            StoreIndexStateEntity(
                storeId = StoreId.FDROID,
                indexToken = "token-42",
                syncedAt = now,
                pruningProfile = "it",
                entryCount = 1,
            ),
        )

        repository.clear(StorageLevel.CATALOG)

        assertThat(listingIds()).isEmpty()
        assertThat(db.indexDao().entryCount(StoreId.FDROID)).isEqualTo(0)
        // The row that counts. With the state still there, the next sync would ask for a **diff**
        // against a document that no longer exists: it would apply the differences to nothing and
        // declare itself up to date, leaving the catalogue incomplete forever and with no error
        // anywhere.
        assertThat(db.indexDao().state(StoreId.FDROID)).isNull()
    }

    @Test
    fun `emptying the catalogue spares an installed app's channel`() = runTest {
        val id = insertListing(StoreId.APKMIRROR, ref = "canale", expiredDaysAgo = 0)
        db.installedAppDao().upsert(installed(packageName = "org.example.canale", channelListingId = id))

        repository.clear(StorageLevel.CATALOG)

        // The button uses the **same** query as the automatic purge, with the same protections:
        // freeing space by detaching every installed app's update channel would be worse than the
        // problem it solves.
        assertThat(listingIds()).contains(id)
    }

    @Test
    fun `emptying the catalogue gives the space back, not only the rows`() = runTest {
        // **On a file and not in memory**, unlike every other test in this class: `VACUUM` has nothing
        // to give back to a database living in RAM, and the number the button reports to the user is
        // precisely the file's size difference. Without compaction SQLite keeps the freed pages to
        // reuse them — right during use, useless when somebody is asking for space back — and the
        // button would say it had freed zero bytes after deleting everything.
        val fileDb = Room
            .databaseBuilder(context, MultiStoreDatabase::class.java, MultiStoreDatabase.NAME)
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .allowMainThreadQueries()
            .build()
        val onFile = MaintenanceRepositoryImpl(
            context = context,
            maintenance = DatabaseMaintenance(fileDb, context, Dispatchers.Unconfined),
            catalogDao = fileDb.catalogDao(),
            indexDao = fileDb.indexDao(),
            downloadDao = fileDb.downloadDao(),
            registry = StoreRegistry(setOf(FakeIndexedStoreAdapter(StoreId.FDROID))),
            settings = settings,
            httpClients = clients,
            imageCache = ImageCache.NONE,
            clock = clock,
            io = Dispatchers.Unconfined,
        )
        try {
            fileDb.indexDao().upsertEntries(
                (1..400).map { StoreIndexEntryEntity(StoreId.FDROID, "app.$it", "x".repeat(4_096), now) },
            )
            onFile.reclaimSpace()

            val reclaimed = onFile.clear(StorageLevel.CATALOG)

            assertThat(reclaimed.freedBytes).isGreaterThan(0L)
        } finally {
            fileDb.close()
            context.getDatabasePath(MultiStoreDatabase.NAME).delete()
        }
    }

    // --- the sizes ----------------------------------------------------------------------------

    @Test
    fun `the sizes count staging and the database`() = runTest {
        File(Staging.dir(context), "peso.apk").writeBytes(ByteArray(3072))

        val usage = repository.usage()

        assertThat(usage.stagedApkBytes).isEqualTo(3072)
        // An in-memory Room database has no file: what matters is that the count does not blow up and
        // that the total sums the four levels instead of reporting one.
        assertThat(usage.totalBytes).isEqualTo(
            usage.catalogBytes + usage.imagesBytes + usage.pagesBytes + usage.stagedApkBytes,
        )
    }

    // --- scaffolding --------------------------------------------------------------------------

    private suspend fun listingIds(): List<Long> =
        StoreId.entries.flatMap { store -> db.catalogDao().listings(store, limit = 100, offset = 0) }
            .map { it.listing.id }

    private suspend fun insertListing(store: StoreId, ref: String, expiredDaysAgo: Int): Long {
        val appKey = "pkg:org.example.$ref"
        db.catalogDao().upsertApps(
            listOf(AppEntity(appKey = appKey, title = ref, titleNorm = ref, updatedAt = now)),
        )
        // `fetched_at` pushed back by `ttl + expiredDaysAgo`: the row expired `expiredDaysAgo` ago.
        val ttl = 6.days
        return db.catalogDao().insertListing(
            StoreListingEntity(
                appKey = appKey,
                storeId = store,
                storeAppRef = ref,
                title = ref,
                titleNorm = ref,
                fetchedAt = now - ttl - expiredDaysAgo.days,
                ttlSeconds = ttl.inWholeSeconds,
            ),
        )
    }

    private fun installed(
        packageName: String,
        channelListingId: Long? = null,
        sourceStore: StoreId? = null,
        sourceRef: String? = null,
    ) = InstalledAppEntity(
        packageName = packageName,
        label = packageName,
        sourceStoreId = sourceStore,
        sourceRef = sourceRef,
        installedVersionName = "1.0",
        installedVersionCode = 1,
        installedAt = now,
        updateChannelListingId = channelListingId,
    )

    private fun download(
        listingId: Long,
        path: String?,
        ref: String = "in-download",
        state: com.multistore.core.model.DownloadState = com.multistore.core.model.DownloadState.QUEUED,
    ) = DownloadEntity(
        listingId = listingId,
        storeId = StoreId.APKMIRROR,
        storeAppRef = ref,
        versionRef = "v1",
        packageName = null,
        state = state,
        filePath = path,
        createdAt = now,
        updatedAt = now,
    )

}

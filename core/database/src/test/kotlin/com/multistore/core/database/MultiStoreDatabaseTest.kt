package com.multistore.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.multistore.core.database.entity.AppEntity
import com.multistore.core.database.entity.AppVersionEntity
import com.multistore.core.database.entity.InstalledAppEntity
import com.multistore.core.database.entity.ListingScreenshotEntity
import com.multistore.core.database.entity.StoreIndexEntryEntity
import com.multistore.core.database.entity.StoreIndexStateEntity
import com.multistore.core.database.entity.StoreEntity
import com.multistore.core.database.entity.StoreListingEntity
import com.multistore.core.model.ContentKind
import com.multistore.core.model.LocalizedText
import com.multistore.core.model.Sha256
import com.multistore.core.model.StoreHealthState
import com.multistore.core.model.StoreId
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MultiStoreDatabaseTest {

    private lateinit var db: MultiStoreDatabase

    private val now = Instant.fromEpochMilliseconds(1_787_316_712_615L)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MultiStoreDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun app(key: String, title: String, iconUrl: String? = null) = AppEntity(
        appKey = key,
        packageName = key,
        title = title,
        titleNorm = title.lowercase(),
        iconUrl = iconUrl,
        contentKind = ContentKind.APP,
        updatedAt = now,
    )

    private fun listing(ref: String, title: String, lastUpdated: Instant? = now) = StoreListingEntity(
        appKey = ref,
        storeId = StoreId.FDROID,
        storeAppRef = ref,
        title = title,
        titleNorm = title.lowercase(),
        fetchedAt = now,
        ttlSeconds = 86_400,
        lastUpdated = lastUpdated,
    )

    private fun version(ref: String, code: Long?, signer: Sha256? = null) = AppVersionEntity(
        listingId = 0,
        versionRef = ref,
        versionName = "1.0",
        versionCode = code,
        signerSha256 = signer,
        fetchedAt = now,
    )

    @Test
    fun `saving a listing preserves its id across two syncs`() = runTest {
        val dao = db.catalogDao()
        dao.upsertApps(listOf(app("a.b.c", "Alfa")))

        val first = dao.saveListing(listing("a.b.c", "Alfa"), listOf(version("v1", 1)), emptyList())
        val second = dao.saveListing(listing("a.b.c", "Alfa v2"), listOf(version("v2", 2)), emptyList())

        // If the id changed, every installed app would lose the link to its update channel on
        // every index sync.
        assertThat(second).isEqualTo(first)
        assertThat(dao.listingCount(StoreId.FDROID)).isEqualTo(1)
        assertThat(dao.versions(first).map { it.versionRef }).containsExactly("v2")
    }

    @Test
    fun `two versions with the same versionCode and no signer coexist`() = runTest {
        val dao = db.catalogDao()
        dao.upsertApps(listOf(app("juloo.keyboard2", "Unexpected Keyboard")))

        val id = dao.saveListing(
            listing("juloo.keyboard2", "Unexpected Keyboard"),
            listOf(version("sha-a|1|/a.apk", 50), version("sha-b|1|/b.apk", 50)),
            emptyList(),
        )

        // The real case of `juloo.keyboard2`. With the obvious unique key —
        // `(listing_id, version_code, signer_sha256)` — these two rows would only be
        // indistinguishable if NULL comparison worked, and in SQLite two NULLs are always
        // different: the key would have prevented nothing. With `version_ref`, which is non-null
        // by construction, both exist and stay distinct.
        assertThat(dao.versions(id)).hasSize(2)
    }

    @Test
    fun `the same version_ref twice is not duplicated`() = runTest {
        val dao = db.catalogDao()
        dao.upsertApps(listOf(app("a.b.c", "Alfa")))
        val id = dao.saveListing(listing("a.b.c", "Alfa"), listOf(version("v1", 1)), emptyList())

        dao.upsertVersions(listOf(version("v1", 1).copy(listingId = id, versionName = "1.1")))

        assertThat(dao.versions(id)).hasSize(1)
        assertThat(dao.versions(id).single().versionName).isEqualTo("1.1")
    }

    @Test
    fun `deleting a listing takes its versions and screenshots with it`() = runTest {
        val dao = db.catalogDao()
        dao.upsertApps(listOf(app("a.b.c", "Alfa")))
        val id = dao.saveListing(
            listing("a.b.c", "Alfa"),
            listOf(version("v1", 1)),
            listOf(ListingScreenshotEntity(listingId = 0, url = "https://x/1.png", kind = "PHONE", sortOrder = 0)),
        )
        assertThat(dao.versions(id)).hasSize(1)

        dao.deleteListings(StoreId.FDROID, listOf("a.b.c"))

        assertThat(dao.versions(id)).isEmpty()
        assertThat(dao.listing(StoreId.FDROID, "a.b.c")).isNull()
    }

    @Test
    fun `search puts whoever starts with the search term first`() = runTest {
        val dao = db.catalogDao()
        listOf("calculator" to "Calculator", "tor" to "Tor", "torrent" to "Torrent Client").forEach {
            dao.upsertApps(listOf(app(it.first, it.second)))
            dao.saveListing(listing(it.first, it.second), emptyList(), emptyList())
        }

        val results = dao.search(StoreId.FDROID, "tor", limit = 10, offset = 0).map { it.listing.title }

        // "Calculator" contains "tor" but nobody typing "tor" is looking for a calculator.
        assertThat(results).containsExactly("Tor", "Torrent Client", "Calculator").inOrder()
        assertThat(dao.searchCount(StoreId.FDROID, "tor")).isEqualTo(3)
    }

    @Test
    fun `Home reads the recently updated without touching the network`() = runTest {
        val dao = db.catalogDao()
        dao.upsertApps(listOf(app("vecchia", "Vecchia"), app("nuova", "Nuova")))
        dao.saveListing(listing("vecchia", "Vecchia", now - kotlin.time.Duration.parse("30d")), emptyList(), emptyList())
        dao.saveListing(listing("nuova", "Nuova", now), emptyList(), emptyList())

        val recent = dao.recentlyUpdated(StoreId.FDROID, limit = 10, offset = 0).map { it.listing.title }

        assertThat(recent).containsExactly("Nuova", "Vecchia").inOrder()
    }

    @Test
    fun `a list carries its icon, which lives in apps and not in the listing`() = runTest {
        val dao = db.catalogDao()
        dao.upsertApps(
            listOf(
                app("with.icon", "With icon", iconUrl = "https://example.test/icon.png"),
                app("without.icon", "Without icon"),
            ),
        )
        dao.saveListing(listing("with.icon", "With icon"), emptyList(), emptyList())
        dao.saveListing(listing("without.icon", "Without icon"), emptyList(), emptyList())

        val byTitle = dao.recentlyUpdated(StoreId.FDROID, limit = 10, offset = 0)
            .associate { it.listing.title to it.iconUrl }

        assertThat(byTitle["With icon"]).isEqualTo("https://example.test/icon.png")
        // The join is a LEFT JOIN: an app without an icon stays in the list. With an inner join
        // it would vanish, and vanish silently — on F-Droid that is 405 packages out of 4,268.
        assertThat(byTitle).containsKey("Without icon")
        assertThat(byTitle["Without icon"]).isNull()
    }

    @Test
    fun `the converters normalise the SHA-256 and preserve localised text`() = runTest {
        val dao = db.catalogDao()
        dao.upsertApps(listOf(app("a.b.c", "Alfa")))
        val upperCase = requireNotNull(Sha256.parseOrNull("AB".repeat(32)))
        val id = dao.saveListing(
            listing("a.b.c", "Alfa").copy(
                summary = LocalizedText(mapOf("it" to "Ciao", "en-US" to "Hello")),
                preferredSignerSha256 = upperCase,
            ),
            listOf(version("v1", 1, signer = upperCase)),
            emptyList(),
        )

        val read = requireNotNull(dao.listing(StoreId.FDROID, "a.b.c"))
        assertThat(read.listing.preferredSignerSha256?.hex).isEqualTo("ab".repeat(32))
        assertThat(read.listing.summary?.resolve(listOf("it"))).isEqualTo("Ciao")
        assertThat(dao.versions(id).single().signerSha256).isEqualTo(upperCase)
    }

    @Test
    fun `the index state preserves token and pruning profile`() = runTest {
        val dao = db.indexDao()
        dao.upsertState(
            StoreIndexStateEntity(
                storeId = StoreId.FDROID,
                indexToken = "1787316712615",
                syncedAt = now,
                pruningProfile = "de,en,es,fr,it",
                entryCount = 4257,
            ),
        )
        dao.upsertEntries(
            listOf(StoreIndexEntryEntity(StoreId.FDROID, "org.fdroid.fdroid", "{}", now)),
        )

        val state = requireNotNull(dao.state(StoreId.FDROID))
        assertThat(state.indexToken).isEqualTo("1787316712615")
        // Without the profile, adding a sixth language to the app would leave that language empty
        // forever on every already-downloaded app: no sync would reload it.
        assertThat(state.pruningProfile).isEqualTo("de,en,es,fr,it")
        assertThat(dao.payload(StoreId.FDROID, "org.fdroid.fdroid")).isEqualTo("{}")
    }

    @Test
    fun `registering a store does not reset its health state`() = runTest {
        val dao = db.storeDao()
        dao.upsert(
            StoreEntity(
                storeId = StoreId.FDROID,
                enabled = false,
                healthState = StoreHealthState.OPEN,
                consecutiveOpenCycles = 3,
            ),
        )

        dao.registerIfAbsent(StoreEntity(storeId = StoreId.FDROID))

        // At startup the adapters announce themselves. An upsert would wipe, on every launch,
        // both the circuit breaker and the user's choice of which stores to query.
        val stored = requireNotNull(dao.get(StoreId.FDROID))
        assertThat(stored.enabled).isFalse()
        assertThat(stored.healthState).isEqualTo(StoreHealthState.OPEN)
        assertThat(stored.consecutiveOpenCycles).isEqualTo(3)
    }

    @Test
    fun `apps uninstalled outside MultiStore disappear from the list`() = runTest {
        val dao = db.installedAppDao()
        listOf("a.uno", "a.due").forEach {
            dao.upsert(
                InstalledAppEntity(
                    packageName = it,
                    label = it,
                    installedVersionName = "1.0",
                    installedVersionCode = 1,
                    installedAt = now,
                ),
            )
        }

        dao.retainOnly(listOf("a.uno"))

        assertThat(dao.packageNames()).containsExactly("a.uno")
    }
}

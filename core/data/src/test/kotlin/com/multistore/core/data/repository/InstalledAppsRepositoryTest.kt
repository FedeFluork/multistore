package com.multistore.core.data.repository

import android.content.pm.PackageInfo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.multistore.core.database.MultiStoreDatabase
import com.multistore.core.database.entity.AppEntity
import com.multistore.core.database.entity.StoreListingEntity
import com.multistore.core.model.ContentKind
import com.multistore.core.model.InstallerKind
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import kotlin.time.Clock
import kotlin.time.Instant
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
 * "My apps" and reconciliation with the system.
 *
 * The case that really needs a test is the one nobody causes on purpose: an app uninstalled
 * **outside** MultiStore. It happens from the system settings, it happens with a secondary profile,
 * and without reconciliation the list would show an app that is not there — and the update check
 * would try to update it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class InstalledAppsRepositoryTest {

    private lateinit var db: MultiStoreDatabase
    private lateinit var repository: InstalledAppsRepositoryImpl

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(1_787_316_712_615L)
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, MultiStoreDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = InstalledAppsRepositoryImpl(context, db.installedAppDao(), db.catalogDao(), clock, Dispatchers.Unconfined)
    }

    @After
    fun tearDown() = db.close()

    private fun install(packageName: String, versionCode: Long = 1L, versionName: String = "1.0") {
        shadowOf(context.packageManager).installPackage(
            PackageInfo().apply {
                this.packageName = packageName
                this.versionName = versionName
                longVersionCode = versionCode
            },
        )
    }

    private suspend fun record(packageName: String) = repository.record(
        packageName = packageName,
        label = packageName,
        storeId = StoreId.FDROID,
        ref = StoreAppRef(packageName),
        listingId = 7L,
        apkSha256 = null,
        installerKind = InstallerKind.SESSION,
    )

    @Test
    fun `it records what the system reports, not what we thought we were installing`() = runTest {
        install("org.example.app", versionCode = 42, versionName = "4.2")

        record("org.example.app")

        val app = repository.get("org.example.app")
        assertThat(app?.versionCode).isEqualTo(42)
        assertThat(app?.versionName).isEqualTo("4.2")
        assertThat(app?.sourceStoreId).isEqualTo(StoreId.FDROID)
    }

    @Test
    fun `reconciliation removes the apps that vanished outside MultiStore`() = runTest {
        install("org.example.stays")
        install("org.example.goes")
        record("org.example.stays")
        record("org.example.goes")

        shadowOf(context.packageManager).removePackage("org.example.goes")
        repository.reconcile()

        assertThat(repository.observe().first().map { it.packageName })
            .containsExactly("org.example.stays")
    }

    @Test
    fun `reconciliation touches nothing when nothing has changed`() = runTest {
        install("org.example.app")
        record("org.example.app")

        repository.reconcile()

        assertThat(repository.get("org.example.app")).isNotNull()
    }

    @Test
    fun `an uninstalled package does not exist to the PackageManager`() = runTest {
        assertThat(repository.installedPackage("org.example.absent")).isNull()
    }

    @Test
    fun `installedPackage reads the system, not our table`() = runTest {
        install("org.example.app", versionCode = 1)
        record("org.example.app")

        // The user updates the app elsewhere: our row stays at 1, the system says 2. For a security
        // decision the second holds, and it is why the pre-install pipeline does not look at
        // `installed_apps`.
        install("org.example.app", versionCode = 2)

        assertThat(repository.get("org.example.app")?.versionCode).isEqualTo(1)
        assertThat(repository.installedPackage("org.example.app")?.versionCode).isEqualTo(2)
    }

    @Test
    fun `reconciliation realigns a version changed outside MultiStore`() = runTest {
        install("org.example.app", versionCode = 1, versionName = "1.0")
        record("org.example.app")

        // Another store, a sideload, `adb install`: the package changes and nothing of ours goes
        // through. Without realignment "My apps" would go on saying "1.0" forever — while the
        // comparison with the store, which reads the PackageManager, would say something else.
        install("org.example.app", versionCode = 2, versionName = "2.0")
        repository.reconcile()

        assertThat(repository.get("org.example.app")?.versionCode).isEqualTo(2)
        assertThat(repository.get("org.example.app")?.versionName).isEqualTo("2.0")
    }

    @Test
    fun `realigning works backwards too`() = runTest {
        install("org.example.app", versionCode = 4, versionName = "1.3")
        record("org.example.app")

        // Downgrading is the case that makes an update visible: it is how the periodic check is
        // tested, and a reconciliation accepting only higher numbers would leave the row saying "1.3"
        // above an update to 1.3.
        install("org.example.app", versionCode = 3, versionName = "1.2")
        repository.reconcile()

        assertThat(repository.get("org.example.app")?.versionCode).isEqualTo(3)
    }

    @Test
    fun `realigning does not make it forget where the app comes from`() = runTest {
        install("org.example.app", versionCode = 1)
        record("org.example.app")

        install("org.example.app", versionCode = 2)
        repository.reconcile()

        // Provenance and channel are ours and not the package's: rewriting them from scratch would
        // take away from the app the store it updates from.
        val app = repository.get("org.example.app")
        assertThat(app?.sourceStoreId).isEqualTo(StoreId.FDROID)
        assertThat(app?.sourceRef).isEqualTo(StoreAppRef("org.example.app"))
    }

    @Test
    fun `the update channel is deduced from the listing when the caller does not know it`() = runTest {
        // Whoever installs knows the store and an opaque ref, not the listing's row id: it is the
        // repository that has to resolve it. Without that, `update_channel_listing_id` stays null and
        // the app loses the link with the store it comes from — i.e. the multi-store rule no longer
        // has data to rest on.
        db.catalogDao().upsertApps(
            listOf(
                AppEntity(
                    appKey = "pkg:org.example.app",
                    packageName = "org.example.app",
                    title = "Example",
                    titleNorm = "example",
                    contentKind = ContentKind.APP,
                    updatedAt = clock.now(),
                ),
            ),
        )
        val listingId = db.catalogDao().saveListing(
            StoreListingEntity(
                appKey = "pkg:org.example.app",
                storeId = StoreId.FDROID,
                storeAppRef = "org.example.app",
                title = "Example",
                titleNorm = "example",
                fetchedAt = clock.now(),
                ttlSeconds = 86_400,
            ),
            emptyList(),
            emptyList(),
        )
        install("org.example.app")

        repository.record(
            packageName = "org.example.app",
            label = "Example",
            storeId = StoreId.FDROID,
            ref = StoreAppRef("org.example.app"),
            listingId = null,
            apkSha256 = null,
            installerKind = InstallerKind.SESSION,
        )

        val row = db.installedAppDao().get("org.example.app")
        assertThat(row?.updateChannelListingId).isEqualTo(listingId)
        assertThat(row?.appKey).isEqualTo("pkg:org.example.app")
    }

    @Test
    fun `the user's choices survive an update`() = runTest {
        install("org.example.app")
        record("org.example.app")
        repository.setIgnoreUpdates("org.example.app", ignore = true)

        install("org.example.app", versionCode = 2)
        record("org.example.app")

        // Whoever had paused an app's updates does not find them switched back on because they
        // updated it once by hand.
        assertThat(repository.get("org.example.app")?.ignoreUpdates).isTrue()
    }
}

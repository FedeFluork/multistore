package com.multistore.feature.settings

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.multistore.core.data.repository.DiagnosticsRepository
import com.multistore.core.data.repository.HealthEvent
import com.multistore.core.data.repository.MaintenanceRepository
import com.multistore.core.data.repository.SpaceReclaimed
import com.multistore.core.data.repository.StalePurged
import com.multistore.core.data.repository.StoreEntry
import com.multistore.core.data.repository.StoreHealthRepository
import com.multistore.core.model.InstallerAvailability
import com.multistore.core.model.InstallerKind
import com.multistore.core.model.InstallerPreference
import com.multistore.core.common.net.StoreHealth
import com.multistore.core.model.CatalogRetention
import com.multistore.core.model.StorageLevel
import com.multistore.core.model.StorageSettings
import com.multistore.core.model.StorageUsage
import com.multistore.core.model.StoreId
import com.multistore.core.remoteconfig.ActiveConfig
import com.multistore.core.remoteconfig.RemoteConfigStatus
import com.multistore.core.testing.FakeInstallRepository
import com.multistore.core.testing.FakeRemoteConfigRepository
import com.multistore.core.testing.FakeSettingsRepository
import com.multistore.core.testing.MainDispatcherRule
import com.multistore.store.api.StoreError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import com.multistore.core.common.net.StoreDiagnosis
import com.multistore.core.data.repository.SearchHistoryRepository
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The only part of Settings that does anything beyond writing a field: picking the installer.
 *
 * The other entries are a switch and a `suspend fun` — testing them would test the DataStore. This one
 * is different: picking "Shizuku" on a device where Shizuku is not running yet has to do **two**
 * different things, and getting one of them wrong only shows on a device.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SettingsViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(dispatcher)

    private val settings = FakeSettingsRepository()
    private val installs = FakeInstallRepository()
    private val remoteConfig = FakeRemoteConfigRepository()

    @Test
    fun `picking a dormant channel asks for it, and writes the preference anyway`() = runTest {
        installs.permissionGranted = true
        val viewModel = viewModel()

        viewModel.setInstallerPreference(InstallerPreference.SHIZUKU)

        assertThat(installs.permissionRequests).containsExactly(InstallerKind.SHIZUKU)
        // The preference is written **regardless**: that is `InstallerSelector`'s promise, preferring
        // is not requiring. Whoever picks Shizuku before starting it must not have to come back here.
        assertThat(settings.installation.value.preference).isEqualTo(InstallerPreference.SHIZUKU)
    }

    @Test
    fun `an already usable channel brings up no dialog`() = runTest {
        installs.availability = InstallerAvailability(
            supported = setOf(InstallerKind.SESSION, InstallerKind.SHIZUKU),
            usable = setOf(InstallerKind.SESSION, InstallerKind.SHIZUKU),
            silent = setOf(InstallerKind.SHIZUKU),
        )
        val viewModel = viewModel()

        viewModel.setInstallerPreference(InstallerPreference.SHIZUKU)

        // Requesting an already-granted permission is not harmless: on Shizuku it is a window that
        // appears, and on root a dialog from the manager.
        assertThat(installs.permissionRequests).isEmpty()
    }

    @Test
    fun `automatic asks nobody for anything`() = runTest {
        val viewModel = viewModel()

        viewModel.setInstallerPreference(InstallerPreference.AUTOMATIC)

        assertThat(installs.permissionRequests).isEmpty()
        assertThat(settings.installation.value.preference).isEqualTo(InstallerPreference.AUTOMATIC)
    }

    @Test
    fun `a denied permission is reported, not left to be guessed`() = runTest {
        installs.permissionGranted = false
        val viewModel = viewModel()

        viewModel.permissionDenials.test {
            viewModel.setInstallerPreference(InstallerPreference.ROOT)

            assertThat(awaitItem()).isEqualTo(InstallerKind.ROOT)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `availability is read when the screen opens`() = runTest {
        installs.availability = InstallerAvailability(
            supported = setOf(InstallerKind.SESSION, InstallerKind.ROOT),
            usable = setOf(InstallerKind.SESSION, InstallerKind.ROOT),
            silent = setOf(InstallerKind.ROOT),
        )

        // This is what decides whether "install updates by itself" can be switched on: without this
        // read the entry would stay off even on a device that can do it.
        assertThat(viewModel().installers.value.hasSilent).isTrue()
    }

    // --- remote configuration -------------------------------------------------------------------

    @Test
    fun `the button really asks for a new document`() = runTest {
        val viewModel = viewModel()

        viewModel.refreshRemoteConfig()

        assertThat(remoteConfig.manualRefreshes).isEqualTo(1)
    }

    /**
     * A button that made the request with the channel off would be a way of bypassing the setting by
     * pressing a key. Both assertions are needed: the first says nothing was started, the second that
     * the user gets to know.
     */
    @Test
    fun `with the channel off the button asks for nothing, and says so`() = runTest {
        remoteConfig.blocked = true
        val viewModel = viewModel()

        viewModel.configRefreshBlocked.test {
            viewModel.refreshRemoteConfig()

            assertThat(awaitItem()).isEqualTo(Unit)
            assertThat(remoteConfig.manualRefreshes).isEqualTo(0)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the configuration status reaches the screen`() = runTest {
        remoteConfig.status.value = RemoteConfigStatus(
            active = ActiveConfig.Applied(
                schemaVersion = 1,
                generatedAt = null,
                storedAt = null,
                stores = setOf(StoreId.UPTODOWN),
            ),
        )

        viewModel().configStatus.test {
            assertThat((awaitItem().active as ActiveConfig.Applied).stores)
                .containsExactly(StoreId.UPTODOWN)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the switch writes to the DataStore`() = runTest {
        viewModel().setBlockRemoteParsers(true)

        assertThat(settings.remoteConfig.value.blockRemoteParsers).isTrue()
    }

    @Test
    fun `clearing a level re-reads the sizes`() = runTest {
        val maintenance = CountingMaintenance()
        val model = viewModel(maintenance)

            // It has to be **observed**: `storage` is a `stateIn(WhileSubscribed)`, so without a
            // collector `value` stays at the initial value and the test would measure its own silence.
        model.storage.test {
            val before = awaitUntil { it.usage.imagesBytes == BEFORE_CLEAR.imagesBytes }
            assertThat(before.busy).isNull()

            model.clearStorage(StorageLevel.IMAGES)
            val after = awaitUntil { it.busy == null && it.freed.isNotEmpty() }

            assertThat(maintenance.cleared).containsExactly(StorageLevel.IMAGES)
            // Re-reading is half the operation: without it the row would keep declaring the previous
            // megabytes above a "4.3 MB freed", that is, two numbers contradicting each other on the
            // same row. And **all four** are re-read, because clearing the catalogue compacts the
            // database and changes what the other levels declared too.
            assertThat(maintenance.usageReads).isAtLeast(2)
            assertThat(after.usage.imagesBytes).isEqualTo(0)
            assertThat(after.freed[StorageLevel.IMAGES]).isEqualTo(AFTER_CLEAR_FREED)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** Drains emissions until one satisfies [predicate]. */
    private suspend fun app.cash.turbine.ReceiveTurbine<StorageUiState>.awaitUntil(
        predicate: (StorageUiState) -> Boolean,
    ): StorageUiState {
        var item = awaitItem()
        while (!predicate(item)) item = awaitItem()
        return item
    }

    @Test
    fun `the storage settings reach the DataStore`() = runTest {
        val model = viewModel()

        model.setKeepApkAfterInstall(true)
        model.setImageCacheMaxMb(64)
        model.setCatalogRetention(CatalogRetention.KEEP)

        assertThat(settings.storage.value.keepApkAfterInstall).isTrue()
        assertThat(settings.storage.value.imageCacheMaxBytes).isEqualTo(StorageSettings.megabytes(64))
        assertThat(settings.storage.value.catalogRetention).isEqualTo(CatalogRetention.KEEP)
    }

    /** Counts the reads: the second one is the proof that the screen does not stay on stale numbers. */
    private class CountingMaintenance : MaintenanceRepository {
        var usageReads = 0
        val cleared = mutableListOf<StorageLevel>()

        override suspend fun usage(): StorageUsage {
            usageReads++
            // Before clearing, images weigh something; after, zero. With a constant value the test
            // would pass even without the re-read.
            return if (cleared.isEmpty()) BEFORE_CLEAR else StorageUsage.UNKNOWN
        }

        override suspend fun clear(level: StorageLevel): SpaceReclaimed {
            cleared += level
            return SpaceReclaimed(bytesBefore = AFTER_CLEAR_FREED, bytesAfter = 0)
        }

        override suspend fun reclaimSpace() = SpaceReclaimed(bytesBefore = 0, bytesAfter = 0)
        override suspend fun purgeStale() = StalePurged(listings = 0, stagedFiles = 0, freedBytes = 0)
    }

    @Test
    fun `switching the record off also forgets what is there`() = runTest {
        val viewModel = viewModel()

        viewModel.setKeepSearchHistory(false)
        advanceUntilIdle()

        assertThat(settings.search.value.keepSearchHistory).isFalse()
        // The half the entry promises out loud. A switch that saved the preference and left the list
        // behind would be a control that says it forgets and does not.
        assertThat(searchHistory.cleared).isEqualTo(1)
    }

    @Test
    fun `switching it back on keeps what is there, which is nothing`() = runTest {
        val viewModel = viewModel()

        viewModel.setKeepSearchHistory(true)
        advanceUntilIdle()

        assertThat(settings.search.value.keepSearchHistory).isTrue()
        // And it does **not** clear: turning the record back on is not a destructive act, and a
        // symmetric implementation would have made it one.
        assertThat(searchHistory.cleared).isEqualTo(0)
    }

    private val searchHistory = RecordingSearchHistory()

    private fun viewModel(maintenance: MaintenanceRepository = NoMaintenance()) = SettingsViewModel(
        settingsRepository = settings,
        maintenance = maintenance,
        storeHealth = NoStores(),
        installs = installs,
        remoteConfigRepository = remoteConfig,
        diagnostics = NoDiagnostics(),
        searchHistory = searchHistory,
    )

    private class NoDiagnostics : DiagnosticsRepository {
        override suspend fun report(): String = "test report"
    }

    /**
     * Records whether the switch cleared what was there.
     *
     * That second half is what the entry promises — "forgets the ones already there" — and it lives
     * in the ViewModel rather than in the settings repository, because clearing is another
     * repository's job. A double that only answered would let a version that saves the preference
     * and keeps the record pass.
     */
    private class RecordingSearchHistory : SearchHistoryRepository {
        var cleared = 0
            private set

        override fun recent(): Flow<List<String>> = flowOf(emptyList())

        override suspend fun record(query: String) = Unit

        override suspend fun forget(query: String) = Unit

        override suspend fun clear() {
            cleared++
        }
    }

    private companion object {
        val BEFORE_CLEAR = StorageUsage(imagesBytes = 4_493_312)
        const val AFTER_CLEAR_FREED = 4_493_312L
    }

    private class NoMaintenance : MaintenanceRepository {
        override suspend fun usage() = StorageUsage.UNKNOWN
        override suspend fun clear(level: StorageLevel) = SpaceReclaimed(bytesBefore = 0, bytesAfter = 0)
        override suspend fun reclaimSpace() = SpaceReclaimed(bytesBefore = 0, bytesAfter = 0)
        override suspend fun purgeStale() = StalePurged(listings = 0, stagedFiles = 0, freedBytes = 0)
    }

    private class NoStores : StoreHealthRepository {
        override suspend fun registerKnownStores() = Unit
        override fun observeAll(): Flow<List<StoreHealth>> = flowOf(emptyList())
        override fun observeStores(): Flow<List<StoreEntry>> = flowOf(emptyList())
        override suspend fun health(storeId: StoreId) = StoreHealth(storeId)
        override suspend fun diagnosis(storeId: StoreId): StoreDiagnosis = StoreDiagnosis(storeId)

        override suspend fun canAttempt(storeId: StoreId): Boolean = true
        override suspend fun recordSuccess(storeId: StoreId) = Unit
        override suspend fun recordFailure(storeId: StoreId, error: StoreError) = Unit
        override suspend fun recordEvent(
            storeId: StoreId,
            kind: String,
            selector: String?,
            tier: Int?,
            detail: String?,
            durationMillis: Long?,
        ) = Unit

        override suspend fun recentEvents(limit: Int): List<HealthEvent> = emptyList()
        override suspend fun pruneOldEvents() = Unit
        override suspend fun setEnabled(storeId: StoreId, enabled: Boolean) = Unit
    }
}

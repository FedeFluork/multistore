package com.multistore.feature.home

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.multistore.core.common.result.AppError
import com.multistore.core.common.result.Outcome
import com.multistore.core.data.repository.AppDetail
import com.multistore.core.data.repository.IndexState
import com.multistore.core.data.repository.InstallStep
import com.multistore.core.data.repository.IndexSyncReport
import com.multistore.core.data.repository.StoreIndexRepository
import com.multistore.core.data.repository.StoreTaxonomy
import com.multistore.core.data.store.StoreRegistry
import com.multistore.core.data.repository.InstalledAppUpdate
import com.multistore.core.data.repository.UpdateChannel
import com.multistore.core.domain.usecase.GetHomeContentUseCase
import com.multistore.core.domain.usecase.InstallAppUseCase
import com.multistore.core.domain.usecase.ObserveUpdatesUseCase
import com.multistore.core.domain.usecase.ResolveDownloadUseCase
import com.multistore.core.domain.usecase.SyncIndexUseCase
import com.multistore.core.model.Category
import com.multistore.core.model.AppVersion
import com.multistore.core.model.InstalledApp
import com.multistore.core.model.InstallerKind
import com.multistore.core.model.LocalizedText
import com.multistore.core.model.OwnPackage
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.model.StoreListingDetail
import com.multistore.core.model.StoreListingSummary
import com.multistore.core.model.VersionRef
import com.multistore.core.model.ArtifactType
import com.multistore.store.api.DownloadResolution
import com.multistore.store.api.StoreResult
import androidx.test.core.app.ApplicationProvider
import com.multistore.core.common.version.VersionSelection
import com.multistore.core.data.repository.HomeIndex
import com.multistore.core.data.repository.RemoteIndexRepository
import com.multistore.core.data.repository.SelfUpdateOffer
import com.multistore.core.data.repository.SelfUpdateRepository
import com.multistore.core.domain.usecase.InstallSelfUpdateUseCase
import com.multistore.core.remoteconfig.FetchAttempt
import com.multistore.core.remoteconfig.SelfUpdateSource
import com.multistore.core.testing.FakeAppDetailRepository
import com.multistore.core.testing.FakeDownloadRepository
import com.multistore.core.testing.FakeIndexedStoreAdapter
import com.multistore.core.testing.FakeInstallRepository
import com.multistore.core.testing.FakeInstalledAppsRepository
import com.multistore.core.testing.FakeSearchRepository
import com.multistore.core.testing.FakeSettingsRepository
import com.multistore.core.testing.FakeStoreAdapter
import com.multistore.core.testing.FakeStoreIndexRepository
import com.multistore.core.testing.FakeUpdateRepository
import com.multistore.core.testing.MainDispatcherRule
import com.multistore.store.api.IndexSyncMode
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The Home, and the project decision that governs it.
 *
 * "Automatic sync on an unmetered network, with explicit confirmation if metered."
 * `SyncIndexUseCase` already has its own tests on that rule; here the half that belongs to the screen
 * is tested, the one no layer below can see: **when** the Home decides to sync on its own, and what it
 * shows when it is told no.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class HomeViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val search = FakeSearchRepository()
    private val settings = FakeSettingsRepository()
    private val updates = FakeUpdateRepository()
    private val installedApps = FakeInstalledAppsRepository()
    private val details = FakeAppDetailRepository()
    private val downloads = FakeDownloadRepository()
    private val installs = FakeInstallRepository()

    /**
     * An adapter that really resolves a link: without one, every install ends in `NotFound`.
     *
     * It sits on a **different** store from the index one, and that is realistic: the Home reports
     * F-Droid's catalogue, and updates can come from any store the user installed something from.
     */
    private class ResolvingAdapter : FakeStoreAdapter(id = StoreId.APKMIRROR) {
        override suspend fun getDownloadLink(
            ref: StoreAppRef,
            version: VersionRef?,
        ): StoreResult<DownloadResolution> = StoreResult.Success(
            DownloadResolution.Direct(
                url = "https://example.test/app.apk",
                headers = emptyMap(),
                fileName = "app.apk",
                artifactType = ArtifactType.APK,
                expectedSha256 = null,
                expectedSize = 1_000L,
            ),
        )
    }

    private val installableRegistry =
        StoreRegistry(setOf(FakeIndexedStoreAdapter(), ResolvingAdapter()))

    private fun viewModel(
        index: StoreIndexRepository,
        metered: Boolean = false,
        registry: StoreRegistry = StoreRegistry(setOf(FakeIndexedStoreAdapter())),
        ownPackage: OwnPackage = OwnPackage("com.multistore.test"),
    ) = HomeViewModel(
        registry = registry,
        index = index,
        syncIndex = SyncIndexUseCase(index, settings) { metered },
        homeContent = GetHomeContentUseCase(search),
        updates = ObserveUpdatesUseCase(updates, installedApps),
        installApp = InstallAppUseCase(
            resolve = ResolveDownloadUseCase(registry, details, settings),
            downloads = downloads,
            installs = installs,
            details = details,
            settings = settings,
        ),
        // The remote index is absent in these tests, and that is the normal state: with no document
        // the Home stays the local-catalogue one, which is exactly what is verified here.
        remoteIndex = object : RemoteIndexRepository {
            override val index: Flow<HomeIndex> = flowOf(HomeIndex())
            override suspend fun refreshIfStale(): FetchAttempt? = null
            override suspend fun refreshNow(): FetchAttempt? = null
        },
        selfUpdates = object : SelfUpdateRepository {
            override val offer: Flow<SelfUpdateOffer?> = flowOf(null)
        },
        // No network: in these tests MultiStore's own update is never offered, so the use case exists
        // to be wired up rather than to be walked.
        installSelfUpdate = InstallSelfUpdateUseCase(
            context = ApplicationProvider.getApplicationContext(),
            downloader = object : SelfUpdateSource {
                override suspend fun download(
                    release: com.multistore.core.remoteconfig.SelfUpdateRelease,
                    destination: java.io.File,
                    onProgress: (Long, Long?) -> Unit,
                ): SelfUpdateSource.Outcome = error("no network in tests")
            },
            install = installs,
        ),
        ownPackage = ownPackage,
    )

    @Test
    fun `at first launch the catalogue is missing and the Home downloads it by itself`() = runTest {
        val index = synced(state = null)

        viewModel(index)

        assertThat(index.syncs).isEqualTo(1)
    }

    @Test
    fun `with a catalogue already downloaded, opening does not resync`() = runTest {
        val index = synced(state = anIndex())

        viewModel(index)

        // Eighteen megabytes every time the Home opens would be a cost nobody asked for: an index that
        // is there is refreshed on request, or by the worker.
        assertThat(index.syncs).isEqualTo(0)
    }

    @Test
    fun `metered network - the Home asks instead of downloading`() = runTest {
        val index = synced(state = null)

        val viewModel = viewModel(index, metered = true)

        viewModel.uiState.test {
            val ready = awaitReady()
            assertThat(ready.meteredConsentRequired).isTrue()
        }
        // The point is not that it fails after trying: it is that it does not try.
        assertThat(index.syncs).isEqualTo(0)
    }

    @Test
    fun `consent applies to that one sync and the question goes away`() = runTest {
        val index = synced(state = null)
        val viewModel = viewModel(index, metered = true)

        viewModel.sync(userConsented = true)

        viewModel.uiState.test {
            val ready = awaitReady()
            assertThat(ready.meteredConsentRequired).isFalse()
        }
        assertThat(index.syncs).isEqualTo(1)
    }

    @Test
    fun `a failure does not delete the catalogue already there`() = runTest {
        val index = synced(state = anIndex(entryCount = 4_269)).apply {
            result = Outcome.Failure(AppError.Network(null))
        }
        val viewModel = viewModel(index)

        viewModel.sync()

        viewModel.uiState.test {
            val failed = awaitReady().index as IndexStatus.Failed
            // Last week's index is still useful: the error is a notice above it, not an empty state in
            // place of the catalogue.
            assertThat(failed.previous?.entryCount).isEqualTo(4_269)
        }
    }

    @Test
    fun `the store's categories reach the Home already localised`() = runTest {
        val index = synced(
            state = anIndex(),
            taxonomy = StoreTaxonomy(
                categories = listOf(
                    Category("Internet", LocalizedText(mapOf("it" to "Internet"))),
                    Category("Games", LocalizedText(mapOf("it" to "Games"))),
                ),
            ),
        )

        viewModel(index).uiState.test {
            val ready = awaitReady()
            // They are the only way into the catalogue for whoever does not already know what to look
            // for, and the names come from the store: translating them in `strings.xml` would mean a
            // release for every new category.
            assertThat(ready.categories.map { it.displayName(listOf("it")) })
                .containsExactly("Internet", "Games")
        }
    }

    @Test
    fun `no index store - the Home says so instead of offering an inert button`() = runTest {
        val index = synced(state = null)

        // `FakeStoreAdapter` declares `SearchSource.REMOTE`: no catalogue to download.
        val viewModel = viewModel(index, registry = StoreRegistry(setOf(FakeStoreAdapter())))

        viewModel.uiState.test {
            assertThat(awaitItem()).isEqualTo(HomeUiState.NoIndexedStore)
        }
        assertThat(index.syncs).isEqualTo(0)
    }

    /** Skips the initial loading state and returns the first ready one. */
    private suspend fun app.cash.turbine.ReceiveTurbine<HomeUiState>.awaitReady(): HomeUiState.Ready {
        var item = awaitItem()
        while (item !is HomeUiState.Ready) item = awaitItem()
        return item
    }

    /** Waits for an "update all" to finish, however many intermediate states it goes through. */
    private suspend fun app.cash.turbine.ReceiveTurbine<HomeUiState>.awaitFinished(): UpdateAllUiState.Finished {
        while (true) {
            val state = awaitItem()
            val finished = (state as? HomeUiState.Ready)?.updateAll as? UpdateAllUiState.Finished
            if (finished != null) return finished
        }
    }

    // --- available updates ----------------------------------------------------------------------

    @Test
    fun `the available updates appear on the Home`() = runTest {
        updates.state.value = listOf(anUpdate("AntennaPod", "de.danoeh.antennapod"))

        viewModel(synced(state = anIndex())).uiState.test {
            assertThat(awaitReady().updates.map { it.channel?.title }).containsExactly("AntennaPod")
        }
    }

    @Test
    fun `update all installs them one at a time, and says how far it has got`() = runTest {
        readyToInstall()
        updates.state.value = listOf(
            anUpdate("AntennaPod", "de.danoeh.antennapod"),
            anUpdate("Firefox", "org.mozilla.firefox"),
        )
        val viewModel = viewModel(synced(state = anIndex()), registry = installableRegistry)

        viewModel.uiState.test {
            awaitReady()
            viewModel.updateAll()
            val finished = awaitFinished()
            assertThat(finished.installed).isEqualTo(2)
            assertThat(finished.failed).isEqualTo(0)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `MultiStore updates last, because updating itself kills the process`() = runTest {
        readyToInstall()
        updates.state.value = listOf(
            anUpdate("MultiStore", "com.multistore.test"),
            anUpdate("AntennaPod", "de.danoeh.antennapod"),
        )
        val viewModel = viewModel(synced(state = anIndex()), registry = installableRegistry)

        viewModel.uiState.test {
            awaitReady()
            viewModel.updateAll()
            awaitFinished()
            cancelAndIgnoreRemainingEvents()
        }

        // The order is read from the install plans: if ours came first, the process would die halfway
        // through the commit and the others would never be touched.
        assertThat(installs.plans.mapNotNull { it.ref?.value })
            .containsExactly("de.danoeh.antennapod", "com.multistore.test")
            .inOrder()
    }

    @Test
    fun `two update-all taps at once become one`() = runTest {
        readyToInstall()
        updates.state.value = listOf(anUpdate("AntennaPod", "de.danoeh.antennapod"))
        // Without an installation that takes time, the first would already have finished when the
        // second arrives and this test would pass even with the guard removed.
        downloads.completionDelay = 1.seconds
        val viewModel = viewModel(synced(state = anIndex()), registry = installableRegistry)

        viewModel.uiState.test {
            awaitReady()
            viewModel.updateAll()
            viewModel.updateAll()
            awaitFinished()
            cancelAndIgnoreRemainingEvents()
        }

        assertThat(installs.plans).hasSize(1)
    }

    /** Everything needed for an installation to run to completion, with no network and no worker. */
    private fun readyToInstall() {
        details.details.value = AppDetail(
            listing = StoreListingDetail(
                summary = StoreListingSummary(
                    storeId = StoreId.APKMIRROR,
                    ref = StoreAppRef("qualsiasi"),
                    title = "Example",
                    packageName = "org.example.app",
                ),
                versions = listOf(AppVersion("2.0", 2, VersionRef("v2"))),
            ),
            installed = null,
            selection = VersionSelection.Outcome.Offer(
                AppVersion("2.0", 2, VersionRef("v2")),
                isUpdate = true,
            ),
            stale = false,
        )
        downloads.completion = Outcome.Success(java.io.File("/dev/null"))
        installs.installSteps = listOf(InstallStep.Installed("org.example.app", 2))
    }

    private fun anUpdate(title: String, packageName: String) = InstalledAppUpdate(
        app = InstalledApp(
            packageName = packageName,
            label = title,
            versionName = "1.0",
            versionCode = 1,
            signerSha256 = null,
            installedAt = Instant.fromEpochSeconds(1_756_000_000),
            installerKind = InstallerKind.SESSION,
        ),
        channel = UpdateChannel(
            storeId = StoreId.APKMIRROR,
            ref = StoreAppRef(packageName),
            listingId = 1L,
            title = title,
            iconUrl = null,
        ),
        selection = VersionSelection.Outcome.Offer(
            version = AppVersion(versionName = "2.0", versionCode = 2, ref = VersionRef("v2")),
            isUpdate = true,
        ),
    )

    private fun synced(
        state: IndexState?,
        taxonomy: StoreTaxonomy = StoreTaxonomy(),
    ) = FakeStoreIndexRepository(state = state, taxonomy = taxonomy).apply {
        result = Outcome.Success(
            IndexSyncReport(
                storeId = StoreId.FDROID,
                mode = IndexSyncMode.FULL,
                written = 1,
                removed = 0,
                token = "1",
                upToDate = false,
            ),
        )
    }

    private fun anIndex(entryCount: Int = 10) = IndexState(
        storeId = StoreId.FDROID,
        token = "token",
        syncedAt = Instant.fromEpochSeconds(1_756_000_000),
        entryCount = entryCount,
        pruningProfile = StoreIndexRepository.currentPruningProfile(),
    )
}

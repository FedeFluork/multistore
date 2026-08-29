package com.multistore.core.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.multistore.core.common.result.AppError
import com.multistore.core.common.result.Outcome
import com.multistore.core.common.version.VersionSelection
import com.multistore.core.data.repository.AppDetail
import com.multistore.core.data.repository.AppDetailRepository
import com.multistore.core.data.repository.DownloadStatus
import com.multistore.core.data.repository.DownloadRepository
import com.multistore.core.data.repository.InstallPlan
import com.multistore.core.data.repository.InstallRepository
import com.multistore.core.data.repository.InstallStep
import com.multistore.core.data.store.StoreRegistry
import com.multistore.core.model.StorageSettings
import com.multistore.core.model.AppVersion
import com.multistore.core.model.ArtifactType
import com.multistore.core.model.ContentKind
import com.multistore.core.model.DownloadState
import com.multistore.core.model.InstallerAvailability
import com.multistore.core.model.InstallerKind
import com.multistore.core.model.Sha256
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.model.StoreListingDetail
import com.multistore.core.model.StoreListingSummary
import com.multistore.core.model.VersionRef
import com.multistore.store.api.DownloadMode
import com.multistore.store.api.DownloadResolution
import com.multistore.store.api.HashAvailability
import com.multistore.store.api.NetworkTier
import com.multistore.store.api.PagedResult
import com.multistore.store.api.SearchFilters
import com.multistore.store.api.StoreAdapter
import com.multistore.store.api.StoreCapabilities
import com.multistore.store.api.StoreMetadata
import com.multistore.store.api.StoreResult
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Test

/**
 * The critical path lined up: resolve, download, verify, install, throw away the staging.
 *
 * The first test is a **regression found on the emulator**, not a hypothesis: `run` suspends until the
 * end of the transfer, so until progress was also observed the bar stayed at 0 B for all 18 MB of a
 * real app. It compiled, it threw no exceptions, and the only way of noticing was to look at it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InstallAppUseCaseTest {

    private val store = StoreId.FDROID
    private val ref = StoreAppRef("org.example.app")
    private val version = AppVersion(versionName = "1.0", versionCode = 1, ref = VersionRef("v1"))

    private val resolution = DownloadResolution.Direct(
        url = "https://fake.test/app.apk",
        headers = emptyMap(),
        fileName = "app.apk",
        artifactType = ArtifactType.APK,
        expectedSha256 = null,
        expectedSize = TOTAL_BYTES,
    )

    private class FakeAdapter(override val id: StoreId) : StoreAdapter {
        override val metadata = StoreMetadata("Fake", "https://fake.test", "en-US", "fake.test")
        override val capabilities = StoreCapabilities(
            search = true, trending = false, recent = false, versionHistory = true,
            providesPackageName = true, providesRating = false, providesScreenshots = false,
            providesChangelog = false, providesHash = HashAvailability.ALWAYS,
            providesSignerFingerprint = true, supportsSplits = false,
            downloadMode = DownloadMode.DIRECT, networkTier = NetworkTier.OKHTTP,
            userAgent = "MultiStoreTest/1.0", supportedFilters = emptySet(),
            contentKinds = setOf(ContentKind.APP),
        )

        var link: StoreResult<DownloadResolution> = StoreResult.Unsupported

        override suspend fun search(query: String, filters: SearchFilters, page: Int) =
            StoreResult.Success(PagedResult.empty<StoreListingSummary>())

        override suspend fun getAppDetails(ref: StoreAppRef) = StoreResult.Unsupported
        override suspend fun getDownloadLink(ref: StoreAppRef, version: VersionRef?) = link
        override suspend fun healthCheck() = StoreResult.Success(Unit)
        override suspend fun preflight(resolution: DownloadResolution) = StoreResult.Success(true)
    }

    private class FakeDetails(private val detail: AppDetail?) : AppDetailRepository {
        override fun observe(storeId: StoreId, ref: StoreAppRef): Flow<AppDetail?> = flowOf(detail)
        override suspend fun detail(storeId: StoreId, ref: StoreAppRef): AppDetail? = detail

        override suspend fun loadVersionHistory(
            storeId: StoreId,
            ref: StoreAppRef,
        ): Outcome<Unit> = Outcome.Success(Unit)

        override suspend fun refresh(storeId: StoreId, ref: StoreAppRef, force: Boolean) =
            Outcome.Success(Unit)
    }

    /**
     * A download repository imitating the one thing that matters here: **the transfer does not happen
     * where it is asked for**.
     *
     * [start] sets the worker going and returns; progress appears in [observe]; [awaitCompletion]
     * reads the persisted state. It is the real shape after the `DownloadWorker` arrived, and it is
     * what makes the test able to notice if somebody put the transfer back inside the caller's scope.
     */
    private class FakeDownloads(private val file: File) : DownloadRepository {
        private val statuses = MutableStateFlow<DownloadStatus?>(null)

        var progressSteps: List<Long> = listOf(HALF_BYTES, TOTAL_BYTES)
        var succeeds = true
        var discarded = 0
        var retired = 0
        var started = 0
        var startedUnmetered = 0
        var cancelled = mutableListOf<Long>()

        /** Who was queued with an installation meant to follow, and who was not. */
        var pendingInstalls = mutableListOf<Boolean>()

        /** Who spent the claim token, and how many times. */
        var claims = 0

        override fun observeActive(): Flow<List<DownloadStatus>> = flowOf(emptyList())
        override fun observeAll(): Flow<List<DownloadStatus>> = flowOf(emptyList())
        override fun observe(id: Long): Flow<DownloadStatus?> = statuses
        override fun observeFor(storeId: StoreId, ref: StoreAppRef): Flow<DownloadStatus?> = statuses
        override suspend fun get(id: Long): DownloadStatus? = statuses.value

        override suspend fun enqueue(
            storeId: StoreId,
            ref: StoreAppRef,
            versionRef: VersionRef,
            packageName: String?,
            listingId: Long?,
            resolution: DownloadResolution.Direct,
            pendingInstall: Boolean,
        ): Long {
            pendingInstalls += pendingInstall
            return DOWNLOAD_ID
        }

        override suspend fun run(id: Long): Outcome<File> = error("the worker does not go through here")

        override suspend fun start(id: Long, requireUnmetered: Boolean) {
            started++
            if (requireUnmetered) startedUnmetered++
            for (bytes in progressSteps) {
                statuses.value = status(bytes, DownloadState.RUNNING)
                yield()
            }
            statuses.value = status(
                TOTAL_BYTES,
                if (succeeds) DownloadState.READY else DownloadState.FAILED,
            )
        }

        override suspend fun awaitCompletion(id: Long): Outcome<File> {
            val current = statuses.value ?: return Outcome.Failure(AppError.NotFound)
            return if (current.state == DownloadState.READY) {
                Outcome.Success(file)
            } else {
                Outcome.Failure(AppError.Network(null))
            }
        }

        override suspend fun cancel(id: Long) {
            cancelled += id
        }

        override suspend fun recordInstalled(id: Long) {
            discarded++
        }

        override suspend fun retire(id: Long) {
            retired++
        }

        override suspend fun deleteStaged(id: Long) = Unit

        override suspend fun claimPendingInstall(id: Long): Boolean {
            claims++
            return true
        }

        override suspend fun pruneHistory(): Int = 0

        override suspend fun clearHistory(): Int = 0

        override suspend fun requeueInterrupted() = Unit

        override suspend fun expectedHash(id: Long): Sha256? = null

        private fun status(bytes: Long, state: DownloadState) = DownloadStatus(
            id = DOWNLOAD_ID,
            storeId = StoreId.FDROID,
            ref = StoreAppRef("org.example.app"),
            versionRef = VersionRef("v1"),
            packageName = "org.example.app",
            state = state,
            bytesDownloaded = bytes,
            bytesTotal = TOTAL_BYTES,
            file = file,
            error = null,
        )
    }

    private class FakeInstalls(var steps: List<InstallStep>) : InstallRepository {
        var plans = 0
        override fun install(plan: InstallPlan): Flow<InstallStep> {
            plans++
            return steps.asFlow()
        }

        override fun uninstall(packageName: String): Flow<InstallStep> = flowOf()

        override suspend fun installerAvailability(): InstallerAvailability = InstallerAvailability()

        override suspend fun requestInstallerPermission(kind: InstallerKind): Boolean = false

        override suspend fun reconcileAbandonedSessions(): Int = 0
    }

    private fun detail(selection: VersionSelection.Outcome) = AppDetail(
        listing = StoreListingDetail(
            summary = StoreListingSummary(
                storeId = store,
                ref = ref,
                title = "Example",
                packageName = ref.value,
            ),
            versions = listOf(version),
        ),
        installed = null,
        selection = selection,
        stale = false,
    )

    private fun useCase(
        downloads: FakeDownloads,
        installs: FakeInstalls,
        adapter: FakeAdapter = FakeAdapter(store).apply { link = StoreResult.Success(resolution) },
        detail: AppDetail? = detail(VersionSelection.Outcome.Offer(version, isUpdate = false)),
        settings: DomainSettings = DomainSettings(),
    ): InstallAppUseCase {
        val details = FakeDetails(detail)
        return InstallAppUseCase(
            resolve = ResolveDownloadUseCase(StoreRegistry(setOf(adapter)), details, settings),
            downloads = downloads,
            installs = installs,
            details = details,
            settings = settings,
            drivers = ActiveInstallDrivers(),
        )
    }

    @Test
    fun `the download's progress really reaches the UI`() =
        runTest(UnconfinedTestDispatcher()) {
            val downloads = FakeDownloads(File("app.apk"))
            val installs = FakeInstalls(listOf(InstallStep.Installed("org.example.app", 1)))

            val steps = useCase(downloads, installs)(store, ref).toList()

            val transferred = steps.filterIsInstance<InstallProgressStep.Downloading>()
                .map { it.bytesDownloaded }
            // Before the fix this list was `[0]` and nothing else: the bar stayed still for the whole
            // duration of the transfer.
            assertThat(transferred).contains(0L)
            assertThat(transferred.max()).isEqualTo(TOTAL_BYTES)
        }

    @Test
    fun `the transfer is done by the worker, not by the caller's scope`() =
        runTest(UnconfinedTestDispatcher()) {
            val downloads = FakeDownloads(File("app.apk"))
            val installs = FakeInstalls(listOf(InstallStep.Installed("org.example.app", 1)))

            useCase(downloads, installs)(store, ref).toList()

            // `FakeDownloads.run` throws: if somebody put the transfer back inside this flow, the test
            // would die instead of passing silently. The download has to survive the screen that
            // started it, and it is the only way to guarantee that.
            assertThat(downloads.started).isEqualTo(1)
        }

    @Test
    fun `cancelling stops the worker, not only the flow`() = runTest(UnconfinedTestDispatcher()) {
        val downloads = FakeDownloads(File("app.apk"))

        useCase(downloads, FakeInstalls(emptyList())).cancelDownload(DOWNLOAD_ID)

        // Interrupting the collection is no longer enough: the worker runs by itself, and without this
        // call it would go on downloading — and showing a notification — after the user pressed
        // Cancel.
        assertThat(downloads.cancelled).containsExactly(DOWNLOAD_ID)
    }

    @Test
    fun `staging is thrown away only on a successful installation`() = runTest(UnconfinedTestDispatcher()) {
        val downloads = FakeDownloads(File("app.apk"))
        val installs = FakeInstalls(listOf(InstallStep.Installed("org.example.app", 1)))

        useCase(downloads, installs)(store, ref).toList()

        assertThat(downloads.discarded).isEqualTo(1)
        assertThat(downloads.retired).isEqualTo(0)
    }

    @Test
    fun `with 'keep the APKs' on the file stays and the row is retired`() =
        runTest(UnconfinedTestDispatcher()) {
            val downloads = FakeDownloads(File("app.apk"))
            val installs = FakeInstalls(listOf(InstallStep.Installed("org.example.app", 1)))
            val settings = DomainSettings().apply { storage.value = StorageSettings(keepApkAfterInstall = true) }

            useCase(downloads, installs, settings = settings)(store, ref).toList()

            // Field 17. Proto3's zero value is `false`, i.e. "delete", i.e. what the app has always
            // done — and it is why the field is called `keep_apk_after_install` and not
            // `delete_apk_after_install` as the plan had it.
            assertThat(downloads.discarded).isEqualTo(0)
            // `retire` and not "do nothing": the row has to stay in `DONE` with its file, because that
            // is what makes the file reusable. `filesDir` is private to the app, so an APK kept with no
            // row naming it would be occupied space and nothing else.
            assertThat(downloads.retired).isEqualTo(1)
        }

    @Test
    fun `cancelled at the confirmation, the file stays where it is`() = runTest(UnconfinedTestDispatcher()) {
        val downloads = FakeDownloads(File("app.apk"))
        val installs = FakeInstalls(listOf(InstallStep.Cancelled))

        useCase(downloads, installs)(store, ref).toList()

        // Throwing it away here would mean re-downloading tens of already present megabytes next time,
        // and next time is nearly always immediately: the user cancelled by mistake.
        assertThat(downloads.discarded).isEqualTo(0)
    }

    @Test
    fun `a failed download never reaches the installer`() = runTest(UnconfinedTestDispatcher()) {
        val downloads = FakeDownloads(File("app.apk")).apply { succeeds = false }
        val installs = FakeInstalls(listOf(InstallStep.Installed("org.example.app", 1)))

        val steps = useCase(downloads, installs)(store, ref).toList()

        assertThat(installs.plans).isEqualTo(0)
        assertThat(steps.last()).isInstanceOf(InstallProgressStep.Failed::class.java)
    }

    @Test
    fun `a listing we do not have stops everything before queueing`() =
        runTest(UnconfinedTestDispatcher()) {
            val downloads = FakeDownloads(File("app.apk"))
            val installs = FakeInstalls(emptyList())

            val steps = useCase(downloads, installs, detail = null)(store, ref).toList()

            assertThat(steps.last()).isInstanceOf(InstallProgressStep.Failed::class.java)
            assertThat(installs.plans).isEqualTo(0)
        }

    private companion object {
        const val DOWNLOAD_ID = 7L
        const val HALF_BYTES = 500L
        const val TOTAL_BYTES = 1_000L
    }
}

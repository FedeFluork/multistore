package com.multistore.feature.appdetail

import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.multistore.core.common.result.Outcome
import com.multistore.core.common.version.VersionSelection
import com.multistore.core.data.repository.CrossStoreAvailability
import com.multistore.core.data.repository.StoreAvailability
import com.multistore.core.data.repository.AppDetail
import com.multistore.core.data.repository.DownloadStatus
import com.multistore.core.data.repository.InstallStep
import com.multistore.core.data.store.StoreRegistry
import com.multistore.core.domain.usecase.GetAppDetailUseCase
import com.multistore.core.domain.usecase.GetCrossStoreAvailabilityUseCase
import com.multistore.core.domain.usecase.InstallAppUseCase
import com.multistore.core.domain.usecase.ResolveDownloadUseCase
import com.multistore.core.domain.usecase.UninstallAppUseCase
import com.multistore.core.installer.verify.ApkArchiveInfo
import com.multistore.core.installer.verify.PreInstallVerifier
import com.multistore.core.model.AggregatedListing
import com.multistore.core.model.MatchMethod
import com.multistore.core.model.AppVersion
import com.multistore.core.model.ArtifactType
import com.multistore.core.model.DownloadState
import com.multistore.core.model.Sha256
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.model.StoreListingDetail
import com.multistore.core.model.StoreListingSummary
import com.multistore.core.model.VersionRef
import com.multistore.core.testing.FakeSettingsRepository
import com.multistore.core.testing.FakeAppDetailRepository
import com.multistore.core.testing.FakeDownloadRepository
import com.multistore.core.testing.FakeInstallRepository
import com.multistore.core.testing.FakeCrossStoreRepository
import com.multistore.core.testing.FakeStoreAdapter
import com.multistore.core.testing.FakeStoreIndexRepository
import com.multistore.core.testing.MainDispatcherRule
import com.multistore.store.api.DownloadHint
import com.multistore.store.api.DownloadResolution
import com.multistore.store.api.StoreResult
import java.io.File
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The detail page, and the translation from "what is happening" to "what to show".
 *
 * Three things live only here and have no test further down:
 *
 *  1. **re-attaching** — since the download lives in a worker, the page can open onto a transfer it did
 *     not start. Without this it would show "Install" above a system notification saying the opposite;
 *  2. **the `Intent` as an event** — since API 34 the system confirmation cannot start from the
 *     background, so the ViewModel hands it to the UI instead of launching it. If it ended up in the
 *     state, it would fire again on every rotation;
 *  3. **the already-ready file** — a verified APK nobody installed must not be re-downloaded, and the
 *     button must not send the user back to the store page.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AppDetailViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(dispatcher)

    private val details = FakeAppDetailRepository(anAppDetail())
    private val index = FakeStoreIndexRepository()
    private val downloads = FakeDownloadRepository()
    private val installs = FakeInstallRepository()

    /** An adapter that really resolves a link: without one, every install ends in `NotFound`. */
    private class ResolvingAdapter : FakeStoreAdapter() {
        var linkRequests = 0

        override suspend fun getDownloadLink(
            ref: StoreAppRef,
            version: VersionRef?,
        ): StoreResult<DownloadResolution> {
            linkRequests++
            return StoreResult.Success(
                DownloadResolution.Direct(
                    url = "https://example.test/app.apk",
                    headers = emptyMap(),
                    fileName = "app.apk",
                    artifactType = ArtifactType.APK,
                    expectedSha256 = null,
                    expectedSize = SIZE,
                ),
            )
        }
    }

    private var adapter: FakeStoreAdapter = ResolvingAdapter()
    private val crossStore = FakeCrossStoreRepository()

    private fun viewModel(): AppDetailViewModel {
        val registry = StoreRegistry(setOf(adapter))
        return AppDetailViewModel(
            savedStateHandle = SavedStateHandle(
                mapOf("storeId" to StoreId.FDROID.wireName, "ref" to REF.value),
            ),
            getDetail = GetAppDetailUseCase(details, index),
            crossStore = GetCrossStoreAvailabilityUseCase(crossStore),
            installApp = InstallAppUseCase(
                resolve = ResolveDownloadUseCase(registry, details, FakeSettingsRepository()),
                downloads = downloads,
                installs = installs,
                details = details,
                settings = FakeSettingsRepository(),
            ),
            uninstallApp = UninstallAppUseCase(installs),
            registry = registry,
        )
    }

    @Test
    fun `a page reopened mid-transfer re-attaches to it`() = runTest(dispatcher) {
        downloads.put(downloadRow(state = DownloadState.RUNNING, bytes = 3_000_000))
        val viewModel = viewModel()

        val install = viewModel.subscribedState().install

        // Before the worker the question made no sense: either the screen was there or the download was
        // not. Now the two survive separately.
        val downloading = install as InstallUiState.Downloading
        assertThat(downloading.downloadId).isEqualTo(DOWNLOAD_ID)
        assertThat(downloading.bytesDownloaded).isEqualTo(3_000_000)
    }

    @Test
    fun `an already-ready file does not turn back into an ordinary Install button`() = runTest(dispatcher) {
        downloads.put(downloadRow(state = DownloadState.READY, bytes = SIZE))
        val viewModel = viewModel()

        val install = viewModel.subscribedState().install

        // If `READY` became `Idle`, pressing Install would redo the resolution — and on the assisted
        // path it would send the user back to the store page for a file they already have on the
        // phone.
        assertThat(install).isEqualTo(InstallUiState.ReadyToInstall(DOWNLOAD_ID))
    }

    @Test
    fun `a paused row leaves the normal button, not a ready file`() = runTest(dispatcher) {
        downloads.put(downloadRow(state = DownloadState.PAUSED, bytes = 1_000))
        val viewModel = viewModel()

        // `PAUSED` means something is on disk but the file is not complete: pressing Install must
        // resume the transfer, not hand half an APK to verification.
        assertThat(viewModel.subscribedState().install).isEqualTo(InstallUiState.Idle)
    }

    @Test
    fun `the system confirmation leaves as an event and the state stays waiting`() = runTest(dispatcher) {
        val apk = File.createTempFile("multistore", ".apk").apply { deleteOnExit() }
        downloads.completion = Outcome.Success(apk)
        val intent = Intent("com.multistore.test.CONFIRM")
        installs.installSteps = listOf(
            InstallStep.Verifying,
            InstallStep.UserActionRequired(intent),
        )
        val viewModel = viewModel()
        val intents = mutableListOf<Intent>()
        backgroundScope.launch { viewModel.userActions.collect { intents += it } }

        viewModel.install()

        // The intent is launched by the UI, which knows it is in the foreground: since API 34 that
        // activity cannot start from the background, and returning it as an event is what makes the
        // difference manageable.
        assertThat(intents).containsExactly(intent)
        assertThat(viewModel.subscribedState().install).isEqualTo(InstallUiState.AwaitingUserAction)
    }

    @Test
    fun `cancelling stops the worker, not only the flow collection`() = runTest(dispatcher) {
        downloads.put(downloadRow(state = DownloadState.RUNNING, bytes = 100))
        val viewModel = viewModel()
        viewModel.subscribedState()

        viewModel.cancel()

        // Stopping collection no longer stops anything — and that is intended, otherwise leaving the
        // page would throw the download away. Stopping it is therefore a gesture of its own.
        assertThat(downloads.cancelled).containsExactly(DOWNLOAD_ID)
    }

    @Test
    fun `installing a ready file does not ask the store for the link again`() = runTest(dispatcher) {
        val apk = File.createTempFile("multistore", ".apk").apply { deleteOnExit() }
        downloads.put(downloadRow(state = DownloadState.READY, bytes = SIZE))
        downloads.completion = Outcome.Success(apk)
        installs.installSteps = listOf(InstallStep.Installed(PACKAGE, versionCode = 12))
        val viewModel = viewModel()

        viewModel.installFromDownload(DOWNLOAD_ID)

        // This is the assisted path's return: asking for the link again would mean sending the user
        // back to redo the captcha for a file that is already on the phone.
        assertThat((adapter as ResolvingAdapter).linkRequests).isEqualTo(0)
        assertThat(installs.plans).hasSize(1)
        assertThat(installs.plans.single().declaredPackageName).isEqualTo(PACKAGE)
        // The staged file is discarded **only** on a successful install.
        assertThat(downloads.discarded).containsExactly(DOWNLOAD_ID)
    }

    @Test
    fun `what verification could check stays on screen once the install has finished`() = runTest(dispatcher) {
        val apk = File.createTempFile("multistore", ".apk").apply { deleteOnExit() }
        downloads.put(downloadRow(state = DownloadState.READY, bytes = SIZE))
        downloads.completion = Outcome.Success(apk)
        installs.installSteps = listOf(
            InstallStep.Verified(verificationOk(packageNameWasVerified = false)),
            InstallStep.Installed(PACKAGE, versionCode = 12),
        )
        val viewModel = viewModel()
        viewModel.subscribedState()

        viewModel.installFromDownload(DOWNLOAD_ID)

        // It outlives the step that produced it: the user reads it **afterwards**, once the
        // installation has finished, and that is when they need it. `Ok.packageNameWasVerified`
        // existed and was tested; what did not read it was the UI.
        val verification = requireNotNull(viewModel.ready().verification)
        assertThat(verification.packageNameWasVerified).isFalse()
        assertThat(verification.hashWasVerified).isTrue()
    }

    @Test
    fun `a new installation does not leave the previous one's verification on screen`() = runTest(dispatcher) {
        val apk = File.createTempFile("multistore", ".apk").apply { deleteOnExit() }
        downloads.put(downloadRow(state = DownloadState.READY, bytes = SIZE))
        downloads.completion = Outcome.Success(apk)
        installs.installSteps = listOf(InstallStep.Verified(verificationOk()), InstallStep.Installed(PACKAGE, 12))
        val viewModel = viewModel()
        viewModel.subscribedState()
        viewModel.installFromDownload(DOWNLOAD_ID)
        assertThat(viewModel.ready().verification).isNotNull()

        // A second run that stops before verifying: what stays on screen would describe another file,
        // and would read as if it referred to this one.
        installs.installSteps = listOf(InstallStep.Failed(statusCode = null, message = "no"))
        viewModel.installFromDownload(DOWNLOAD_ID)

        assertThat(viewModel.ready().verification).isNull()
    }

    private fun verificationOk(packageNameWasVerified: Boolean = true) =
        PreInstallVerifier.VerificationOutcome.Ok(
            info = ApkArchiveInfo(
                packageName = PACKAGE,
                versionCode = 12,
                minSdk = 23,
                signerSha256 = listOf(requireNotNull(Sha256.parseOrNull("ab".repeat(32)))),
                signatureSchemes = setOf(2, 3),
                fileSha256 = requireNotNull(Sha256.parseOrNull("cd".repeat(32))),
                sizeBytes = SIZE,
            ),
            packageNameWasVerified = packageNameWasVerified,
            signerWasVerified = true,
            hashWasVerified = true,
        )

    /** An adapter that requires a human gesture: the uptodown case. */
    private class AssistedAdapter : FakeStoreAdapter() {
        override suspend fun getDownloadLink(
            ref: StoreAppRef,
            version: VersionRef?,
        ): StoreResult<DownloadResolution> = StoreResult.Success(
            DownloadResolution.UserAssisted(
                pageUrl = "https://example.test/android/download",
                hint = DownloadHint.TAP_DOWNLOAD_BUTTON,
            ),
        )
    }

    @Test
    fun `a download returning from the assisted path is verified and installed`() = runTest(dispatcher) {
        adapter = AssistedAdapter()
        val apk = File.createTempFile("multistore", ".apk").apply { deleteOnExit() }
        downloads.completion = Outcome.Success(apk)
        installs.installSteps = listOf(InstallStep.Installed(PACKAGE, versionCode = 12))
        val viewModel = viewModel()
        viewModel.subscribedState()

        viewModel.install()
        // So far the path is the expected one: the page sends the user to the store page.
        assertThat(viewModel.ready().install).isInstanceOf(InstallUiState.UserAssisted::class.java)

        // The WebView intercepts the file and queues it. **This is the line that was missing.**
        // `UserAssisted` is not `Idle`, so `orDownloadInFlight` did not replace it: the page kept saying
        // "open the store page" above an APK already fully downloaded, and nobody ever verified it.
        // Measured on the device with uptodown — 82,680,854 bytes in `filesDir/staging` and the page
        // stuck on the notice.
        downloads.put(downloadRow(state = DownloadState.READY, bytes = SIZE))

        assertThat(installs.plans).hasSize(1)
        assertThat(viewModel.ready().install).isEqualTo(InstallUiState.Installed)
    }

    @Test
    fun `a failed assisted download does not restart by itself`() = runTest(dispatcher) {
        adapter = AssistedAdapter()
        val viewModel = viewModel()
        viewModel.subscribedState()
        viewModel.install()

        // Automatically resuming a transfer that failed would mean re-downloading silently, and hiding
        // from the user the reason it did not work.
        downloads.put(downloadRow(state = DownloadState.FAILED, bytes = 0))

        assertThat(installs.plans).isEmpty()
        assertThat(viewModel.ready().install).isInstanceOf(InstallUiState.UserAssisted::class.java)
    }

    @Test
    fun `uninstalling goes through the installed package, not the listing's one`() = runTest(dispatcher) {
        details.details.value = anAppDetail(installedVersionCode = 11)
        installs.uninstallSteps = listOf(InstallStep.Uninstalled(PACKAGE))
        val viewModel = viewModel()
        viewModel.subscribedState()

        viewModel.uninstall()

        assertThat(installs.uninstalled).containsExactly(PACKAGE)
    }

    // --- Cross-store identity -------------------------------------------------------------------

    @Test
    fun `the other stores reach the state, split between certain and possible`() = runTest(dispatcher) {
        crossStore.emit(
            CrossStoreAvailability(
                availableOn = listOf(availability(StoreId.APKMIRROR, "f-droid/app", 1.0f)),
                possibleMatches = listOf(availability(StoreId.APKMODY, "apps/app-mod", 0.5f)),
                unexploredStores = 2,
            ),
        )
        val viewModel = viewModel()

        val state = viewModel.subscribedState()

        // The two lists stay two, and that is the safety rule made visible: below `0.85` nothing is
        // merged silently, the user is asked.
        assertThat(state.crossStore.availableOn.map { it.storeId }).containsExactly(StoreId.APKMIRROR)
        assertThat(state.crossStore.possibleMatches.map { it.storeId }).containsExactly(StoreId.APKMODY)
        assertThat(state.crossStore.canLookUp).isTrue()
    }

    @Test
    fun `opening the page queries no other store`() = runTest(dispatcher) {
        val viewModel = viewModel()

        viewModel.subscribedState()

        // No mass crawling, no speculative prefetching. Four searches on third-party sites every time
        // a page opens would be exactly that.
        assertThat(crossStore.lookUps).isEmpty()
    }

    @Test
    fun `searching the other stores is the user's gesture`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.subscribedState()

        viewModel.lookUpOtherStores()

        assertThat(crossStore.lookUps).containsExactly(StoreId.FDROID to REF)
    }

    @Test
    fun `confirm and reject reach the repository, and are not mixed up`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.subscribedState()

        viewModel.confirmMatch(7L)
        viewModel.rejectMatch(9L)

        assertThat(crossStore.confirmed).containsExactly(7L)
        assertThat(crossStore.rejected).containsExactly(9L)
    }

    @Test
    fun `a page never seen is loading, not missing`() = runTest(dispatcher) {
        // Opening the page of a remote store never visited, Room does not have the row yet and the
        // refresh is in flight. The screen used to write "app not found" for that fraction of a second
        // — on every search result tapped.
        details.details.value = null
        details.refreshDelay = 1.seconds
        val viewModel = viewModel()
        subscriptions.launch { viewModel.uiState.collect { } }

        assertThat(viewModel.uiState.value).isEqualTo(AppDetailUiState.Loading)

        advanceTimeBy(2_000)
        assertThat(viewModel.uiState.value).isEqualTo(AppDetailUiState.NotFound)
    }

    @Test
    fun `a page just saved is not missing while the flow catches up`() =
        runTest(dispatcher) {
            // The window no instant double shows: `refresh` has written the row and Room notifies the
            // invalidation on another thread, so for an instant the disk has it and the flow still says
            // `null`. On the device it was a flash of "app not found" above a page about to appear.
            details.details.value = null
            details.writesOnRefresh = anAppDetail()
            details.refreshDelay = 1.seconds
            val viewModel = viewModel()
            subscriptions.launch { viewModel.uiState.collect { } }

            advanceTimeBy(2_000)
            assertThat(viewModel.uiState.value).isEqualTo(AppDetailUiState.Loading)

            // And when the flow arrives, the page: the wait is not a dead end.
            details.details.value = anAppDetail()
            advanceTimeBy(1)
            assertThat(viewModel.uiState.value).isInstanceOf(AppDetailUiState.Ready::class.java)
        }

    private fun availability(storeId: StoreId, ref: String, confidence: Float) = StoreAvailability(
        listing = AggregatedListing(
            summary = StoreListingSummary(
                storeId = storeId,
                ref = StoreAppRef(ref),
                title = "Example",
            ),
            confidence = confidence,
            method = if (confidence >= 1.0f) MatchMethod.PACKAGE_NAME else MatchMethod.TITLE_DEV,
        ),
        listingId = 5,
    )

    // --- Version history ------------------------------------------------------------------------

    @Test
    fun `the history is not requested until somebody opens the section`() = runTest(dispatcher) {
        val viewModel = viewModel()
        val state = viewModel.subscribedState()

        // On apkcombo, apkmody and modyolo the history is a page of its own: asking for it when every
        // page opens would be the speculative prefetching this project forbids.
        assertThat(state.versionHistory.expanded).isFalse()
        assertThat(details.historyLoads).isEqualTo(0)
    }

    @Test
    fun `opening it asks for it, closing and reopening does not`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.subscribedState()

        viewModel.toggleVersionHistory()
        assertThat(viewModel.ready().versionHistory.expanded).isTrue()
        assertThat(details.historyLoads).isEqualTo(1)

        viewModel.toggleVersionHistory()
        viewModel.toggleVersionHistory()

        // Closing and reopening costs the user nothing: if every reopening were a request, three
        // absent-minded taps would be three requests to a third-party site.
        assertThat(details.historyLoads).isEqualTo(1)
    }

    /**
     * Waiting for the refresh, which is this file's one defence the first draft did not cover.
     *
     * `AppDetailRepository.refresh` writes the listing with `saveListing`, which begins with
     * `clearVersions`: a history merged **while** that refresh is still in flight would be deleted right
     * after, and the section would be left with only the listing's version with nothing saying so. It is
     * not a textbook case — the section can be opened as soon as the page appears, which is precisely
     * when the cache is there and the refresh is not.
     *
     * Only a slow refresh produces the case: with a double that answers immediately the window does not
     * exist, and removing the `join()` stayed green. Same lesson as elsewhere — when an injection stays
     * green, first check whether the case is actually there.
     */
    @Test
    fun `the history is not requested while the refresh could still delete it`() =
        runTest(dispatcher) {
            details.refreshDelay = 1.seconds
            val viewModel = viewModel()
            viewModel.subscribedState()

            viewModel.toggleVersionHistory()

            advanceTimeBy(500)
            assertThat(details.historyLoads).isEqualTo(0)
            // And meanwhile it says so: open and waiting, not open and empty.
            assertThat(viewModel.ready().versionHistory.loading).isTrue()

            advanceTimeBy(1_000)
            assertThat(details.historyLoads).isEqualTo(1)
        }

    @Test
    fun `after a failure the section stays open, says so, and Retry asks again`() =
        runTest(dispatcher) {
            details.historyResult = Outcome.Failure(com.multistore.core.common.result.AppError.NotFound)
            val viewModel = viewModel()
            viewModel.subscribedState()

            viewModel.toggleVersionHistory()

            // Open and in error at once: what the catalogue had stays on screen, and above it the row
            // saying the rest did not arrive. If a failure closed the section, the user would see their
            // own tap undo itself.
            assertThat(viewModel.ready().versionHistory.expanded).isTrue()
            assertThat(viewModel.ready().versionHistory.failed).isTrue()

            details.historyResult = Outcome.Success(Unit)
            viewModel.retryVersionHistory()

            assertThat(details.historyLoads).isEqualTo(2)
            assertThat(viewModel.ready().versionHistory.failed).isFalse()
        }

    /**
     * The button under "preview only" opens, and nothing more.
     *
     * With `toggle` in its place, pressing it on an already-open section would close it — that is, do the
     * opposite of what the button promises, and precisely to whoever presses it twice because they did
     * not see what happened.
     */
    @Test
    fun `the notice's button opens the section and does not close it`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.subscribedState()

        viewModel.showVersionHistory()
        viewModel.showVersionHistory()

        assertThat(viewModel.ready().versionHistory.expanded).isTrue()
        assertThat(details.historyLoads).isEqualTo(1)
    }

    /**
     * `FakeStoreAdapter` declares `versionHistory = false`, and the screen must know: it is the
     * capability that decides whether the section exists. The **repository** looks at it anyway so as
     * not to make the request; this is the other half — no version-history section where it is not
     * supported.
     */
    @Test
    fun `the adapter's capability reaches the screen state`() = runTest(dispatcher) {
        assertThat(viewModel().subscribedState().versionHistorySupported).isFalse()

        adapter = object : FakeStoreAdapter() {
            override val capabilities = super.capabilities.copy(versionHistory = true)
        }

        assertThat(viewModel().subscribedState().versionHistorySupported).isTrue()
    }

    /**
     * Tapping a history row overrides the selection rule, and that is its purpose.
     *
     * `ResolveDownloadUseCase` has always allowed for it — whoever picks a version by hand overrides the
     * rule, and that is exactly the explicit user request the rule allows for — and until now no screen
     * passed it one.
     */
    @Test
    fun `installing a hand-picked version passes that one, not the rule's`() =
        runTest(dispatcher) {
            val older = AppVersion(
                versionName = "1.0.0",
                versionCode = 10,
                ref = VersionRef("v10"),
                sizeBytes = SIZE,
            )
            val viewModel = viewModel()
            viewModel.subscribedState()

            viewModel.install(explicitVersion = older)

            assertThat(downloads.active.value.map { it.versionRef }).containsExactly(older.ref)
        }

    private fun AppDetailViewModel.subscribedState(): AppDetailUiState.Ready {
        subscriptions.launch { uiState.collect { } }
        return uiState.value as AppDetailUiState.Ready
    }

    /** The **current** state, as against `subscribedState()`, which snapshots one and stops. */
    private fun AppDetailViewModel.ready(): AppDetailUiState.Ready =
        uiState.value as AppDetailUiState.Ready

    private val subscriptions = CoroutineScope(SupervisorJob() + dispatcher)

    @After
    fun tearDown() = subscriptions.cancel()

    private fun downloadRow(state: DownloadState, bytes: Long) = DownloadStatus(
        id = DOWNLOAD_ID,
        storeId = StoreId.FDROID,
        ref = REF,
        versionRef = VERSION.ref,
        packageName = PACKAGE,
        state = state,
        bytesDownloaded = bytes,
        bytesTotal = SIZE,
        file = null,
        error = null,
    )

    private fun anAppDetail(installedVersionCode: Long? = null) = AppDetail(
        listing = StoreListingDetail(
            summary = StoreListingSummary(
                storeId = StoreId.FDROID,
                ref = REF,
                title = "Example",
                packageName = PACKAGE,
            ),
            versions = listOf(VERSION),
        ),
        installed = installedVersionCode?.let {
            com.multistore.core.model.InstalledPackage(
                packageName = PACKAGE,
                versionName = "1.0",
                versionCode = it,
                signerSha256 = null,
            )
        },
        selection = VersionSelection.Outcome.Offer(VERSION, isUpdate = installedVersionCode != null),
        stale = false,
    )

    private companion object {
        const val PACKAGE = "org.example.app"
        const val SIZE = 7_000_000L
        const val DOWNLOAD_ID = 1L
        val REF = StoreAppRef(PACKAGE)
        val VERSION = AppVersion(
            versionName = "1.2.3",
            versionCode = 12,
            ref = VersionRef("v12"),
            sizeBytes = SIZE,
        )
    }
}

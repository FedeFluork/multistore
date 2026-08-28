package com.multistore.feature.appdetail

import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.multistore.core.common.result.AppError
import com.multistore.core.data.repository.AppDetail
import com.multistore.core.common.result.Outcome
import com.multistore.core.data.repository.CrossStoreAvailability
import com.multistore.core.data.repository.DownloadStatus
import com.multistore.core.data.repository.ContainerProblem
import com.multistore.core.data.repository.InstallStep
import com.multistore.core.data.repository.StoreTaxonomy
import com.multistore.core.data.store.StoreRegistry
import com.multistore.core.domain.usecase.AppDetailWithTaxonomy
import com.multistore.core.domain.usecase.GetAppDetailUseCase
import com.multistore.core.domain.usecase.GetCrossStoreAvailabilityUseCase
import com.multistore.core.domain.usecase.InstallAppUseCase
import com.multistore.core.domain.usecase.InstallProgressStep
import com.multistore.core.domain.usecase.UninstallAppUseCase
import com.multistore.core.installer.verify.PreInstallVerifier
import com.multistore.core.model.AppVersion
import com.multistore.core.model.BundleSummary
import com.multistore.core.model.DownloadState
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.model.VersionRef
import com.multistore.store.api.DownloadHint
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Where the installation path currently is, in a vocabulary a screen can draw.
 *
 * It is a translation of [InstallProgressStep] and not an alias for it, because the two sets answer
 * different questions: that one describes **what is happening**, this one **what to show and what to
 * offer**. Three cases make it obvious, and in all three the right answer is not an error message but
 * an action:
 *
 *  - [Rejected] — the pipeline said no. Which check failed decides what can be proposed: a hash that
 *    does not match is a dead end, a signature differing from the installed one is not.
 *  - [SignerConflict] — the only way through is uninstall and reinstall, losing the data. It is the
 *    user's choice and stays available even with `allow_signer_mismatch` off.
 *  - [UserAssisted] — the download requires a real human gesture on the store page. Actually doing
 *    what the site asks is legitimate; pretending to have done it is not.
 */
sealed interface InstallUiState {

    data object Idle : InstallUiState

    /** Deciding which version, and from which URL. */
    data object Resolving : InstallUiState

    /**
     * [downloadId] is what the Cancel button needs.
     *
     * Since the transfer lives in a worker, stopping collection of the flow does not stop it — and
     * that is intended, otherwise leaving the page would throw the download away. Stopping it is
     * therefore an action of its own, and an action of its own needs to know what to act on.
     */
    data class Downloading(
        val downloadId: Long,
        val bytesDownloaded: Long,
        val bytesTotal: Long?,
    ) : InstallUiState {
        val fraction: Float?
            get() = bytesTotal?.takeIf { it > 0 }
                ?.let { (bytesDownloaded.toFloat() / it).coerceIn(0f, 1f) }
    }

    /** The pre-install pipeline: size, hash, archive, packageName, signature, downgrade. */
    data object Verifying : InstallUiState

    /**
     * The downloaded file is a split container, and it is being opened.
     *
     * It carries the summary because that is what explains the difference between the megabytes
     * downloaded and the ones installed: a container carries every architecture and every density
     * together, and without saying so the difference looks like space that vanished.
     */
    data class Unpacking(val summary: BundleSummary) : InstallUiState

    /** Copying the game data to where the app will look for it. */
    data class PlacingExpansions(val files: Int, val bytes: Long) : InstallUiState

    /**
     * The container could not be used.
     *
     * Separate from [Rejected] because it is a different question: there a package was read and failed
     * a check, here no package could even be reached — and the ways out differ.
     */
    data class ContainerRejected(val problem: ContainerProblem) : InstallUiState

    data class Writing(val bytesWritten: Long, val bytesTotal: Long) : InstallUiState

    data object Committing : InstallUiState

    /** The system confirmation screen has been opened: the ball is in the user's court. */
    data object AwaitingUserAction : InstallUiState

    data class Rejected(val outcome: PreInstallVerifier.VerificationOutcome) : InstallUiState

    data class SignerConflict(val available: List<AppVersion>) : InstallUiState

    data object Incompatible : InstallUiState

    /**
     * The file can only be obtained through a human gesture on the store page.
     *
     * It carries [hint] and [versionRef] because the path continues in another screen, and that screen
     * needs to know **what** to tell the user to do and **which version** they are getting: deriving
     * it again would mean asking the store for something already known.
     */
    data class UserAssisted(
        val pageUrl: String,
        val hint: DownloadHint,
        val versionRef: VersionRef,
    ) : InstallUiState

    /**
     * The file is already there, verified in staging, and nobody has installed it.
     *
     * There are two ways here: the user cancelled at the system confirmation, or the download came
     * back from the assisted path. In both cases restarting from a normal "Install" would redo the
     * resolution — and on the assisted path it would send the user back to the store page for a file
     * they already have on the phone.
     */
    data class ReadyToInstall(val downloadId: Long) : InstallUiState

    /**
     * Failed, with the error in the app's vocabulary.
     *
     * [systemMessage] is the raw `PackageInstaller` text, when there is one: it is not translated and
     * not for the ordinary user, but it is the only thing telling two different `INSTALL_FAILED_*`
     * apart — and without it, a bug report becomes "it does not install".
     */
    data class Failed(val error: AppError, val systemMessage: String? = null) : InstallUiState

    data object Cancelled : InstallUiState

    data object Installed : InstallUiState

    data object Uninstalling : InstallUiState

    data object Uninstalled : InstallUiState

    /** `true` while something is running that must not be startable twice. */
    val isBusy: Boolean
        get() = this is Resolving || this is Downloading || this is Verifying ||
            this is Unpacking || this is PlacingExpansions ||
            this is Writing || this is Committing || this is AwaitingUserAction ||
            this is Uninstalling
}

/**
 * The "previous versions" section: whether it is open, and how the request went.
 *
 * Three `Boolean`s rather than a `sealed interface`, on the same criterion this project already uses
 * for `UpToDate.comparable`: a variant when the situations lead to **different gestures**, a field
 * when only what is read changes. Here there are two gestures — open, and retry if it did not answer
 * — and they coexist: the section stays open with the versions already known inside it and, above
 * them, the row saying the others did not arrive.
 *
 * [loading] and [failed] describe **the extra request**, not the versions: those come from the
 * catalogue through `AppDetail`, and are there even when the request fails. That is why a failure here
 * is not an error state for the section.
 */
data class VersionHistoryUiState(
    val expanded: Boolean = false,
    val loading: Boolean = false,
    val failed: Boolean = false,
)

/**
 * How far the listing read has got — and, when it has finished, **whether the disk knows more than
 * the flow**.
 *
 * Two states rather than a `Boolean`, because of a race that only showed on a device: `refresh` writes
 * the listing into Room and returns, but Room notifies the invalidation on another thread. With a
 * boolean, the instant between the write and the emission read as "refresh finished **and** nothing to
 * show", which the screen rendered as "app not found" — a flash of under a second, over a listing just
 * saved, right on the first visit to a remote store.
 *
 * [Settled.rowOnDisk] is the direct read that closes that window **without a timer**: if the row is
 * there the flow is still awaited, because it will arrive; if it is not, "not found" is as immediate as
 * it should be. A fixed delay would have done both worse: waiting where there was nothing to wait for,
 * and not long enough where there was.
 */
private sealed interface Load {

    /** The request is in flight. */
    data object InFlight : Load

    /** The request has finished. [rowOnDisk] says whether the flow still has to catch up. */
    data class Settled(val rowOnDisk: Boolean) : Load
}

/** `true` while something is on the way: a request in flight, or an emission still to come. */
private val Load.stillArriving: Boolean
    get() = this is Load.InFlight || (this is Load.Settled && rowOnDisk)

sealed interface AppDetailUiState {

    data object Loading : AppDetailUiState

    /**
     * There is nothing to show, **and nothing on the way**.
     *
     * Distinct from [Loading] because it means two different things depending on the store: on a
     * local-index store it means the app is not in the downloaded catalogue; on a remote store, that
     * the page did not answer and the cache is empty.
     *
     * The second half of that sentence arrived with cross-store matching, and it fixes a defect that
     * was already there: opening the page of a remote store never visited, Room does not have the row
     * yet, so the flow emitted `null` and the screen wrote **"app not found"** for the fraction of a
     * second between opening and the store's answer. It happened on every search result tapped, and
     * with "available on N stores" it would have happened on every jump between stores.
     */
    data object NotFound : AppDetailUiState

    data class Ready(
        val detail: AppDetail,
        val taxonomy: StoreTaxonomy,
        val storeName: String,
        val install: InstallUiState,
        /**
         * Where else this app is, and where it might be.
         *
         * It lives in the screen state rather than in a sub-screen because the question "which store
         * do I take it from" is part of the decision to install, not a later detail: the difference
         * between a store that publishes a hash and one that redistributes a modified build changes
         * what the verification pipeline will be able to prove.
         */
        val crossStore: CrossStoreAvailability = CrossStoreAvailability(),
        /**
         * `true` while the listing is being re-read from the store.
         *
         * It tells "this store publishes no installable package" apart from "I do not know yet": a
         * listing discovered through cross-store matching starts from a result list, therefore
         * **without versions**, and without this field the screen would say the first while the second
         * is true.
         */
        val refreshing: Boolean = false,
        /**
         * What the pipeline was **actually** able to verify about the last file installed.
         *
         * It lives next to [install] rather than inside it, because it outlives the step that produced
         * it: the user reads it afterwards, once the installation has finished, and that is when they
         * need it.
         */
        val verification: PreInstallVerifier.VerificationOutcome.Ok? = null,
        /**
         * Whether this store publishes a history, and what the screen is doing about it.
         *
         * [versionHistorySupported] is the adapter's `versionHistory` capability, and this is the first
         * reader that declaration has ever had: eight adapters out of nine set it to `true`, the
         * contract test verified it, and no screen looked at it. Hence the rule: no version-history
         * section where it is not supported.
         */
        val versionHistorySupported: Boolean = false,
        val versionHistory: VersionHistoryUiState = VersionHistoryUiState(),
    ) : AppDetailUiState
}

/**
 * The detail page, and the critical path that starts there.
 *
 * The installation is **not** launched from here: `InstallStep.UserActionRequired` carries an `Intent`
 * that is handed back to the UI through [userActions]. Since API 34 the system confirmation screen
 * cannot start from the background, so only something that knows it is in the foreground may launch
 * it — and this ViewModel does not know.
 */
@HiltViewModel
class AppDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val getDetail: GetAppDetailUseCase,
    private val crossStore: GetCrossStoreAvailabilityUseCase,
    private val installApp: InstallAppUseCase,
    private val uninstallApp: UninstallAppUseCase,
    private val registry: StoreRegistry,
) : ViewModel() {

    private val route: AppDetailRoute = savedStateHandle.toRoute()
    private val storeId: StoreId? = route.storeIdOrNull()
    private val ref: StoreAppRef = route.appRef()

    private val storeName: String = storeId
        ?.let { registry.adapter(it)?.metadata?.displayName }
        ?: route.storeId

    /** Whether this store publishes versions beyond the one on the listing. */
    private val versionHistorySupported: Boolean =
        storeId?.let { registry.adapter(it)?.capabilities?.versionHistory } == true

    private val install = MutableStateFlow<InstallUiState>(InstallUiState.Idle)
    private val verification = MutableStateFlow<PreInstallVerifier.VerificationOutcome.Ok?>(null)
    private val load = MutableStateFlow<Load>(
        if (storeId != null) Load.InFlight else Load.Settled(rowOnDisk = false),
    )
    private val history = MutableStateFlow(VersionHistoryUiState())
    private var installJob: Job? = null

    /**
     * The refresh started from `init`, kept so that it can be **awaited**.
     *
     * `AppDetailRepository.refresh` writes the listing with `saveListing`, which begins with
     * `clearVersions`: a history loaded while that refresh is still in flight would be deleted right
     * after, and the section would be left with only the listing's version with nothing saying so. It
     * is not a textbook case — the section is visible as soon as the page appears, which is precisely
     * when the cache is there and the refresh is not.
     */
    private var refreshJob: Job? = null
    private var historyJob: Job? = null

    /**
     * A successful history is not requested again.
     *
     * Collapsing and reopening the section is a gesture that costs nothing and that users perform
     * without thinking; on three stores out of nine every reopening would be a request to a
     * third-party site. A failure leaves this at `false`, so that "Retry" has something to do.
     */
    private var historyLoaded = false

    /**
     * The `Intent`s the UI has to launch: install confirmation, uninstall confirmation.
     *
     * A `SharedFlow` with no replay rather than a state field: an intent is an **event**, and keeping
     * it in the state would mean relaunching it on every recomposition and every rotation.
     * `extraBufferCapacity = 1` so the emission never suspends inside the collection of the install
     * flow.
     */
    private val _userActions = MutableSharedFlow<Intent>(extraBufferCapacity = 1)
    val userActions: SharedFlow<Intent> = _userActions.asSharedFlow()

    private val detail: Flow<AppDetailWithTaxonomy?> =
        storeId?.let { getDetail(it, ref) } ?: flowOf(null)

    /** A transfer in flight for this app, even one this screen did not start. */
    private val activeDownload: Flow<DownloadStatus?> =
        storeId?.let { installApp.observeDownload(it, ref) } ?: flowOf(null)

    private val availability: Flow<CrossStoreAvailability> =
        storeId?.let { crossStore(it, ref) } ?: flowOf(CrossStoreAvailability())

    /**
     * The group describing **what there is to show**, as against [progress], which describes what is
     * happening. A private `data class` rather than a widened `Triple`: four positional fields, two of
     * them `Boolean`, are two chances to swap them without the compiler noticing.
     */
    private data class Content(
        val loaded: AppDetailWithTaxonomy?,
        val stores: CrossStoreAvailability,
        val load: Load,
        val history: VersionHistoryUiState,
    )

    private val content = combine(detail, availability, load, history, ::Content)

    private val progress = combine(install, activeDownload, verification, ::Triple)

    val uiState: StateFlow<AppDetailUiState> =
        combine(content, progress) { visible, (installState, download, verified) ->
            val loaded = visible.loaded
            when {
                loaded != null -> AppDetailUiState.Ready(
                    detail = loaded.detail,
                    taxonomy = loaded.taxonomy,
                    storeName = storeName,
                    install = installState.orDownloadInFlight(download),
                    verification = verified,
                    crossStore = visible.stores,
                    refreshing = visible.load is Load.InFlight,
                    versionHistorySupported = versionHistorySupported,
                    versionHistory = visible.history,
                )

                // Nothing in hand **but something on the way**: it is loading, not missing. The two
                // reasons something can be on the way are different and are kept apart — see [Load].
                visible.load.stillArriving -> AppDetailUiState.Loading

                else -> AppDetailUiState.NotFound
            }
        }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MILLIS),
        initialValue = AppDetailUiState.Loading,
    )

    init {
        // On a local-index store this does nothing by construction: a listing's freshness depends on
        // the whole index, not on the single page. On a remote store it is the plan's
        // stale-while-revalidate — show the cache immediately and refresh.
        storeId?.let { id ->
            refreshJob = viewModelScope.launch {
                var rowOnDisk = false
                try {
                    getDetail.refresh(id, ref)
                    rowOnDisk = getDetail.isInCatalog(id, ref)
                } finally {
                    load.value = Load.Settled(rowOnDisk = rowOnDisk)
                }
            }
        }
        resumeWhenTheAssistedDownloadComesBack()
    }

    /**
     * The return from the assisted path, which without this did not happen.
     *
     * When the WebView intercepts the download, the file enters the queue and the screen comes back
     * here. But [InstallUiState.UserAssisted] **is not `Idle`**, and `orDownloadInFlight` only replaces
     * the idle state: the page therefore kept saying "open the store page" above a file already fully
     * downloaded, and nobody ever verified it. Measured on the device with uptodown — 82,680,854 bytes
     * in `filesDir/staging` and the page stuck on the notice.
     *
     * The defect was not new: it is the one install branch no store exercised before — up to uptodown
     * all four adapters were `DIRECT`. A path nobody walks is a path nobody tests.
     *
     * It hooks in as soon as the row appears, not when it is ready: `resume` already waits for the
     * file, and this way the page shows progress instead of standing still until the end.
     */
    private fun resumeWhenTheAssistedDownloadComesBack() {
        viewModelScope.launch {
            activeDownload.collect { download ->
                val assisted = install.value as? InstallUiState.UserAssisted ?: return@collect
                if (download == null || download.versionRef != assisted.versionRef) return@collect
                // A failed or cancelled download does not resume by itself: restarting it here would
                // silently re-download what the user stopped.
                if (download.state !in RESUMABLE_STATES) return@collect
                installFromDownload(download.id)
            }
        }
    }

    /**
     * Opens or closes "previous versions", and the first opening asks the store for the rest.
     *
     * The request starts from the opening and not from arriving on the page: on apkcombo, apkmody and
     * modyolo the history is a page of its own, and fetching it for every page opened would be the
     * speculative prefetching this project forbids. On the other six there is nothing to ask for, and
     * `AppDetailRepository.loadVersionHistory` returns immediately after looking at the capability.
     */
    fun toggleVersionHistory() {
        val current = history.value
        history.value = current.copy(expanded = !current.expanded)
        if (!current.expanded) loadVersionHistory()
    }

    /**
     * Opens the section without closing it if it is already open.
     *
     * The "preview only" notice calls this: there the user's gesture is "show them to me", and a
     * `toggle` on an already-open section would close it — that is, do the opposite of what the button
     * promises.
     */
    fun showVersionHistory() {
        if (history.value.expanded) return
        toggleVersionHistory()
    }

    private fun loadVersionHistory() {
        val id = storeId ?: return
        if (historyLoaded || historyJob?.isActive == true) return

        historyJob = viewModelScope.launch {
            history.value = history.value.copy(loading = true, failed = false)
            // The listing refresh first, since it begins by deleting the versions: see the note on
            // [refreshJob]. If it has already finished, `join` returns immediately.
            refreshJob?.join()
            val outcome = getDetail.loadVersionHistory(id, ref)
            historyLoaded = outcome is Outcome.Success
            history.value = history.value.copy(loading = false, failed = outcome is Outcome.Failure)
        }
    }

    /** After a failure. The section is already open: nothing is opened here, it is requested again. */
    fun retryVersionHistory() = loadVersionHistory()

    /**
     * Asks the other stores whether they have this app. **On request, never on its own.**
     *
     * No mass crawling, no speculative prefetching: querying four third-party sites every time a page
     * opens would be exactly speculative prefetching — the user asked for this app on this store. When
     * one arrives here from a search, the answer is already there without anybody pressing anything:
     * the search has already made those requests.
     */
    fun lookUpOtherStores() {
        val id = storeId ?: return
        viewModelScope.launch { crossStore.lookUp(id, ref) }
    }

    /** "Yes, it is the same app." */
    fun confirmMatch(listingId: Long) {
        val id = storeId ?: return
        viewModelScope.launch { crossStore.confirm(id, ref, listingId) }
    }

    /**
     * The name a store presents itself under. The adapter declares it: it is not interface text.
     *
     * "APKMirror" is spelled the same in all five languages — it is a trademark, and the store name is
     * the one thing that is not translated.
     */
    fun storeDisplayName(storeId: StoreId): String =
        registry.adapter(storeId)?.metadata?.displayName ?: storeId.wireName

    /** "No, these are two different apps." It will not be proposed for this app again. */
    fun rejectMatch(listingId: Long) {
        val id = storeId ?: return
        viewModelScope.launch { crossStore.reject(id, ref, listingId) }
    }

    fun install(explicitVersion: AppVersion? = null, allowDowngrade: Boolean = false) {
        val id = storeId ?: return
        if (install.value.isBusy) return

        installJob = viewModelScope.launch {
            // The previous verification describes another file: keeping it on screen while a new one
            // is downloaded would make it read as if it referred to this one.
            verification.value = null
            install.value = InstallUiState.Resolving
            installApp(id, ref, explicitVersion = explicitVersion, allowDowngrade = allowDowngrade)
                .collect { step -> install.value = step.toUiState() }
        }
    }

    /**
     * Installs a file **already downloaded**, without going through resolution again.
     *
     * It is the assisted path's return and at the same time the way out of a cancellation at the system
     * confirmation: in both cases there is an APK in staging that nobody installed, and without this
     * route it would stay there forever — re-downloaded from scratch on the next tap.
     */
    fun installFromDownload(downloadId: Long) {
        val id = storeId ?: return
        if (install.value.isBusy) return

        installJob = viewModelScope.launch {
            verification.value = null
            install.value = InstallUiState.Verifying
            installApp.resume(id, ref, downloadId).collect { step -> install.value = step.toUiState() }
        }
    }

    /**
     * Uninstalls.
     *
     * It is also the way out of a signer conflict, and it deliberately stays **two taps**: uninstall
     * now, install after. Chaining the two would start an installation right after the user has watched
     * their data disappear, with no moment in which to stop.
     */
    fun uninstall() {
        val packageName = (uiState.value as? AppDetailUiState.Ready)
            ?.detail?.installed?.packageName
            ?: return
        if (install.value.isBusy) return

        installJob = viewModelScope.launch {
            install.value = InstallUiState.Uninstalling
            uninstallApp(packageName).collect { step -> install.value = step.toUiState() }
        }
    }

    /**
     * Cancels at the user's request.
     *
     * Two gestures, not one: following the path stops **and** the worker is stopped. Doing only the
     * first would leave an orphan download consuming network after the user said no — and showing a
     * notification nobody has any way of dismissing.
     *
     * The id is read from the visible state rather than from a field, because the download to cancel
     * may have been started by another instance of this screen: after a return to the foreground this
     * ViewModel never started it, but it is showing it.
     */
    fun cancel() {
        val downloading = (uiState.value as? AppDetailUiState.Ready)?.install as? InstallUiState.Downloading
        installJob?.cancel()
        install.value = InstallUiState.Idle
        downloading?.let { viewModelScope.launch { installApp.cancelDownload(it.downloadId) } }
    }

    /** Puts the button back to its starting state after a terminal outcome. */
    fun dismissInstallOutcome() {
        if (!install.value.isBusy) install.value = InstallUiState.Idle
    }

    /**
     * The path's state, or that of the download running on its own.
     *
     * The order is not negotiable: what **this** screen is doing always wins. A package refused by
     * verification, or a confirmation awaiting an answer, must not be covered by the state of a transfer
     * that has meanwhile completed.
     *
     * `PAUSED` becomes [InstallUiState.Idle]: there is a button to press and pressing it resumes from
     * the bytes that are there, it does not re-download. `READY` instead becomes
     * [InstallUiState.ReadyToInstall], which is a different thing from `Idle`: the file is already
     * there, and restarting the normal path would redo the resolution — on the assisted path sending the
     * user back to the store page for a file they already have.
     */
    private fun InstallUiState.orDownloadInFlight(download: DownloadStatus?): InstallUiState {
        if (this != InstallUiState.Idle || download == null) return this
        return when (download.state) {
            DownloadState.QUEUED, DownloadState.RUNNING -> InstallUiState.Downloading(
                downloadId = download.id,
                bytesDownloaded = download.bytesDownloaded,
                bytesTotal = download.bytesTotal,
            )

            DownloadState.READY -> InstallUiState.ReadyToInstall(download.id)

            else -> InstallUiState.Idle
        }
    }

    private suspend fun InstallProgressStep.toUiState(): InstallUiState = when (this) {
        InstallProgressStep.Resolving -> InstallUiState.Resolving
        is InstallProgressStep.Downloading ->
            InstallUiState.Downloading(downloadId, bytesDownloaded, bytesTotal)
        is InstallProgressStep.UserAssistedDownload ->
            InstallUiState.UserAssisted(pageUrl, hint, versionRef)
        is InstallProgressStep.SignerConflict -> InstallUiState.SignerConflict(available)
        InstallProgressStep.Incompatible -> InstallUiState.Incompatible
        is InstallProgressStep.Failed -> InstallUiState.Failed(error)
        // The file is there and nobody is installing it: that is exactly [ReadyToInstall], which is
        // what the page shows for an already-completed download. This screen cannot reach it —
        // `unattended` is only set by the periodic check — but if one day it could, the right answer is
        // already this one and not a dead end.
        is InstallProgressStep.Downloaded -> InstallUiState.ReadyToInstall(downloadId)
        is InstallProgressStep.Install -> step.toUiState()
    }

    private suspend fun InstallStep.toUiState(): InstallUiState = when (this) {
        InstallStep.Verifying -> InstallUiState.Verifying
        // It does not change the visible state — it is still installing — but it puts aside what was
        // verified, which stays on screen once everything else has finished.
        is InstallStep.Verified -> {
            verification.value = outcome
            InstallUiState.Verifying
        }
        is InstallStep.Rejected -> InstallUiState.Rejected(outcome)
        is InstallStep.Unpacking -> InstallUiState.Unpacking(summary)
        is InstallStep.PlacingExpansions -> InstallUiState.PlacingExpansions(files, bytes)
        is InstallStep.ContainerRejected -> InstallUiState.ContainerRejected(problem)
        is InstallStep.Writing -> InstallUiState.Writing(bytesWritten, bytesTotal)
        InstallStep.Committing -> InstallUiState.Committing
        is InstallStep.UserActionRequired -> {
            _userActions.emit(intent)
            InstallUiState.AwaitingUserAction
        }

        is InstallStep.Installed -> InstallUiState.Installed
        // A silent installer was required and there was none. This cannot happen from here:
        // `requireSilent` is only set by the periodic check, which does not go through this ViewModel.
        // `Idle` is the honest answer anyway — nothing failed — and `orDownloadInFlight` turns it into
        // "ready to install" by itself, since the file is right there.
        InstallStep.SilentUnavailable -> InstallUiState.Idle
        is InstallStep.Uninstalled -> InstallUiState.Uninstalled
        is InstallStep.Failed -> InstallUiState.Failed(
            error = AppError.InstallFailed(statusCode, message),
            systemMessage = message,
        )
        InstallStep.Cancelled -> InstallUiState.Cancelled
    }

    private companion object {
        /**
         * The states in which a download returning from the assisted path must be resumed.
         *
         * `FAILED` and `DONE` are deliberately out: the first because resuming on its own would hide the
         * error, the second because it has already been installed.
         */
        val RESUMABLE_STATES = setOf(
            DownloadState.QUEUED,
            DownloadState.RUNNING,
            DownloadState.VERIFYING,
            DownloadState.READY,
        )

        const val SUBSCRIPTION_GRACE_MILLIS = 5_000L
    }
}

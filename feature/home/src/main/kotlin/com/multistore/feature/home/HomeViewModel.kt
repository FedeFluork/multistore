package com.multistore.feature.home

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.multistore.core.common.result.AppError
import com.multistore.core.data.repository.IndexState
import com.multistore.core.data.repository.InstallStep
import com.multistore.core.data.repository.InstalledAppUpdate
import com.multistore.core.data.repository.HomeIndex
import com.multistore.core.data.repository.RemoteIndexRepository
import com.multistore.core.data.repository.SelfUpdateOffer
import com.multistore.core.data.repository.SelfUpdateRepository
import com.multistore.core.data.repository.StoreIndexRepository
import com.multistore.core.data.repository.UpdateChannel
import com.multistore.core.data.store.StoreRegistry
import com.multistore.core.domain.usecase.GetHomeContentUseCase
import com.multistore.core.domain.usecase.InstallAppUseCase
import com.multistore.core.domain.usecase.InstallProgressStep
import com.multistore.core.domain.usecase.InstallSelfUpdateUseCase
import com.multistore.core.domain.usecase.SelfUpdateStep
import com.multistore.core.domain.usecase.ObserveUpdatesUseCase
import com.multistore.core.domain.usecase.SyncIndexUseCase
import com.multistore.core.domain.usecase.SyncRequestOutcome
import com.multistore.core.model.Category
import com.multistore.core.model.OwnPackage
import com.multistore.core.model.StoreId
import com.multistore.core.model.StoreListingSummary
import javax.inject.Inject
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import dagger.hilt.android.lifecycle.HiltViewModel

/** The state of the local index, as the Home tells it. */
sealed interface IndexStatus {

    /** The index has never been downloaded: this is first launch. */
    data object NeverSynced : IndexStatus

    /**
     * A sync in progress.
     *
     * [expected] is `null` until it is known how many entries will arrive — on an incremental diff it
     * never is, and a determinate bar that is not determinate lies to the user.
     */
    data class Syncing(val processed: Int, val expected: Int?) : IndexStatus

    data class Synced(val entryCount: Int, val syncedAt: Instant) : IndexStatus

    data class Failed(val error: AppError, val previous: Synced?) : IndexStatus
}

/**
 * How far an "update all" has got.
 *
 * It is not an atomic operation and must not be told as one: with only `SessionInstaller` every app
 * asks for its own system confirmation, so five updates are five dialogs in a row. Saying where we are
 * is the only thing that makes that queue understandable rather than exhausting.
 */
sealed interface UpdateAllUiState {

    data object Idle : UpdateAllUiState

    data class Running(val done: Int, val total: Int, val label: String) : UpdateAllUiState

    /** [failed] includes cancellations: whoever said no to the system dialog. */
    data class Finished(val installed: Int, val failed: Int) : UpdateAllUiState
}

/**
 * How far MultiStore's own update has got.
 *
 * Separate from [UpdateAllUiState] even though they look alike, because the two end differently: an
 * ordinary update has an "after" in which the app is alive and can report the outcome, this one does
 * not — on a successful commit the system kills the process. There is no `Finished` here, and the last
 * state anybody reads is [Installing].
 */
sealed interface SelfUpdateUiState {

    data object Idle : SelfUpdateUiState

    data class Downloading(val bytesDownloaded: Long, val bytesTotal: Long?) : SelfUpdateUiState

    data object Installing : SelfUpdateUiState

    data class Failed(val error: AppError) : SelfUpdateUiState
}

sealed interface HomeUiState {

    data object Loading : HomeUiState

    /**
     * No local-index store is wired.
     *
     * Not an error, and it matters that it is distinct from an empty index: with nine stores, eight of
     * which are queried over the network, the Home can legitimately have nothing to sync. It does not
     * happen today — F-Droid is always there — but showing "download the catalogue" to somebody with no
     * catalogue to download would be a button that does nothing.
     */
    data object NoIndexedStore : HomeUiState

    data class Ready(
        val storeId: StoreId,
        val index: IndexStatus,
        val recentlyUpdated: List<StoreListingSummary>,
        /**
         * The categories the store publishes, already localised by it.
         *
         * They are the only way into the catalogue for whoever does not already know what to search
         * for: with 4,269 packages downloaded, "recently updated" shows thirty and search expects the
         * name to be known. They stay empty until there is an index.
         */
        val categories: List<Category> = emptyList(),
        /** The network is metered and the user has not yet said whether to proceed. */
        val meteredConsentRequired: Boolean = false,
        /**
         * The apps installed through MultiStore that have something newer.
         *
         * It is in Home and not only in "My apps" because it is the one thing on this screen that
         * concerns the user rather than the catalogue: "recently updated" lists what moves on F-Droid,
         * not what moves on their phone.
         */
        val updates: List<InstalledAppUpdate> = emptyList(),
        val updateAll: UpdateAllUiState = UpdateAllUiState.Idle,
        /**
         * The two sections that come from the index we publish ourselves.
         *
         * Empty when there is no index — first launch, unreachable CDN, switch off — and in that case
         * the Home does not draw them. It is not an error to show: it is the non-negotiable rule about
         * remote config seen from the UI side.
         */
        val remoteIndex: HomeIndex = HomeIndex(),
        /** There is a new version of MultiStore, and it runs on this device. */
        val selfUpdate: SelfUpdateOffer? = null,
        val selfUpdateProgress: SelfUpdateUiState = SelfUpdateUiState.Idle,
    ) : HomeUiState
}

/**
 * The Home: the state of the local catalogue, and what has come out of it recently.
 *
 * It makes no network request to fill itself — the apps it shows come from the already-synced index.
 * The only network it touches is the sync itself, which is an action with a visible beginning and end,
 * not a background load.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    registry: StoreRegistry,
    private val index: StoreIndexRepository,
    private val syncIndex: SyncIndexUseCase,
    private val homeContent: GetHomeContentUseCase,
    private val updates: ObserveUpdatesUseCase,
    private val installApp: InstallAppUseCase,
    private val remoteIndex: RemoteIndexRepository,
    private val selfUpdates: SelfUpdateRepository,
    private val installSelfUpdate: InstallSelfUpdateUseCase,
    private val ownPackage: OwnPackage,
) : ViewModel() {

    /**
     * The index store whose state the Home reports.
     *
     * Only one, because only one exists. Its name does not appear: the registry is asked who declares
     * `SearchSource.LOCAL_INDEX`, so the day a second store publishes an index this line does not
     * change — the screen does, since it will have to pick one.
     */
    private val indexedStore: StoreId? = registry.indexedStores.firstOrNull()?.id

    private val syncing = MutableStateFlow<IndexStatus.Syncing?>(null)
    private val syncFailure = MutableStateFlow<AppError?>(null)
    private val meteredConsentRequired = MutableStateFlow(false)

    private var syncJob: Job? = null

    private val updateAll = MutableStateFlow<UpdateAllUiState>(UpdateAllUiState.Idle)
    private var updateAllJob: Job? = null

    /**
     * The `Intent`s only a foreground UI may launch.
     *
     * Here that is the system install confirmation, one per updated app. Same shape as the detail page
     * and "My apps", and for the same reason: since API 34 that activity cannot start from the
     * background, and a ViewModel does not know it is in the foreground.
     */
    private val _userActions = MutableSharedFlow<Intent>(extraBufferCapacity = 1)
    val userActions: SharedFlow<Intent> = _userActions.asSharedFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val recentlyUpdated: Flow<List<StoreListingSummary>> =
        indexedStore?.let { storeId ->
            index.observeState(storeId)
                // The token changes if and only if the index changed: reloading the list on every
                // Room re-emission would mean re-reading it even when the only thing that changed is
                // `syncedAt`.
                .map { it?.token }
                .distinctUntilChanged()
                .mapLatest { homeContent.recentlyUpdated(storeId) }
        } ?: flowOf(emptyList())

    /**
     * What the local catalogue says about itself: state, what is new, categories.
     *
     * In a single flow because `combine` with the typed lambda stops at five sources, and because the
     * three things change together — a sync re-emits them all, and nothing else does.
     */
    private val catalogue: Flow<CatalogueSnapshot> = indexedStore?.let { storeId ->
        combine(
            index.observeState(storeId),
            recentlyUpdated,
            index.observeTaxonomy(storeId),
        ) { state, apps, taxonomy -> CatalogueSnapshot(state, apps, taxonomy.categories) }
    } ?: flowOf(CatalogueSnapshot())

    /**
     * The updates and the "update all" state, in a single flow.
     *
     * Together because `combine` with the typed lambda stops at five sources, and because the two
     * values are always read together: the button only makes sense next to the list it updates.
     */
    private val pendingUpdates: Flow<UpdatesSnapshot> =
        combine(updates.available(), updateAll, ::UpdatesSnapshot)

    private val selfUpdateProgress = MutableStateFlow<SelfUpdateUiState>(SelfUpdateUiState.Idle)
    private var selfUpdateJob: Job? = null

    /**
     * What comes from the remote index, in a single flow.
     *
     * Together because `combine` with the typed lambda stops at five sources, and because the three
     * things change together: a new document re-emits them, and nothing else does.
     */
    private val remote: Flow<RemoteSnapshot> =
        combine(remoteIndex.index, selfUpdates.offer, selfUpdateProgress, ::RemoteSnapshot)

    /** The state of the local sync, in a single flow. Same reason as above. */
    private val syncState: Flow<SyncSnapshot> =
        combine(syncing, syncFailure, meteredConsentRequired, ::SyncSnapshot)

    val uiState: StateFlow<HomeUiState> = if (indexedStore == null) {
        flowOf<HomeUiState>(HomeUiState.NoIndexedStore)
    } else {
        combine(
            catalogue,
            syncState,
            pendingUpdates,
            remote,
        ) { snapshot, sync, pending, index ->
            HomeUiState.Ready(
                storeId = indexedStore,
                index = statusOf(snapshot.state, sync.inProgress, sync.failure),
                recentlyUpdated = snapshot.recentlyUpdated,
                categories = snapshot.categories,
                updates = pending.available,
                updateAll = pending.progress,
                meteredConsentRequired = sync.consentRequired,
                remoteIndex = index.index,
                selfUpdate = index.selfUpdate,
                selfUpdateProgress = index.progress,
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MILLIS),
        initialValue = HomeUiState.Loading,
    )

    init {
        // Project decision: automatic sync on an unmetered network, with explicit confirmation if
        // metered. It starts on its own only when there is nothing at all — a catalogue already
        // downloaded is refreshed on request or by the worker, not every time the Home opens.
        viewModelScope.launch {
            val storeId = indexedStore ?: return@launch
            if (index.state(storeId) == null) sync()
        }
    }

    /**
     * @param userConsented the user has just agreed to use the metered network. It applies to this sync
     * only: `SyncIndexUseCase` does not remember it, and turning it into a permanent setting would be
     * deciding on their behalf.
     */
    fun sync(force: Boolean = false, userConsented: Boolean = false) {
        val storeId = indexedStore ?: return
        // Two concurrent syncs would write the same index: the second is ignored rather than queued,
        // because queueing it would mean redoing 18 MB immediately.
        if (syncJob?.isActive == true) return

        syncJob = viewModelScope.launch {
            syncFailure.value = null
            meteredConsentRequired.value = false
            syncing.value = IndexStatus.Syncing(processed = 0, expected = null)
            try {
                val outcome = syncIndex(storeId, force = force, userConsented = userConsented) { progress ->
                    syncing.value = IndexStatus.Syncing(progress.processed, progress.expected)
                }
                when (outcome) {
                    is SyncRequestOutcome.Completed -> Unit
                    is SyncRequestOutcome.NeedsMeteredConsent -> meteredConsentRequired.value = true
                    is SyncRequestOutcome.Failed -> syncFailure.value = outcome.error
                }
            } finally {
                syncing.value = null
            }
        }
    }

    fun dismissMeteredConsent() {
        meteredConsentRequired.value = false
    }

    fun dismissFailure() {
        syncFailure.value = null
    }

    private fun statusOf(
        state: IndexState?,
        inProgress: IndexStatus.Syncing?,
        failure: AppError?,
    ): IndexStatus {
        val synced = state?.let { IndexStatus.Synced(it.entryCount, it.syncedAt) }
        return when {
            inProgress != null -> inProgress
            // A failure does not delete what is already there: if last week's index is still around,
            // the Home stays useful and the error is a notice above it, not an empty state in place of
            // the catalogue.
            failure != null -> IndexStatus.Failed(failure, synced)
            synced != null -> synced
            else -> IndexStatus.NeverSynced
        }
    }

    /**
     * Updates everything that has something newer, one app at a time.
     *
     * In sequence rather than together because with only `SessionInstaller` every installation opens
     * the system confirmation screen, and two such screens at once do not exist. The loop therefore
     * waits for each to finish — the wait is inside `collect`, because the install flow closes when the
     * system reports the outcome.
     *
     * MultiStore goes last: updating itself kills the process halfway through the commit, and with it
     * the loop — the apps after it would never be touched, and the user would have no way of knowing
     * which. Putting it at the end costs a `sortedBy` and removes that case. No store publishes
     * MultiStore today, so the line is never walked — but the day one does, the normal path will take
     * it without anybody having to remember.
     */
    fun updateAll() {
        if (updateAllJob?.isActive == true) return
        updateAllJob = viewModelScope.launch {
            val targets = updates.available()
                .first()
                .sortedBy { it.app.packageName == ownPackage.name }
            if (targets.isEmpty()) return@launch

            var installed = 0
            var failed = 0
            targets.forEachIndexed { index, update ->
                val channel = update.channel ?: return@forEachIndexed
                updateAll.value = UpdateAllUiState.Running(
                    done = index,
                    total = targets.size,
                    label = channel.title,
                )
                if (install(update, channel)) installed++ else failed++
            }
            updateAll.value = UpdateAllUiState.Finished(installed = installed, failed = failed)
        }
    }

    /**
     * Downloads and installs the new version of MultiStore.
     *
     * No `requireSilent` and no preferred installer: updating itself kills the process halfway through
     * the commit, and doing that without the user having just pressed something means an app vanishing
     * from under their fingers. It is the same reason "update all" puts it last.
     */
    fun updateSelf() {
        if (selfUpdateJob?.isActive == true) return
        val offer = (uiState.value as? HomeUiState.Ready)?.selfUpdate ?: return
        selfUpdateJob = viewModelScope.launch {
            installSelfUpdate(offer).collect { step ->
                selfUpdateProgress.value = when (step) {
                    is SelfUpdateStep.Downloading ->
                        SelfUpdateUiState.Downloading(step.bytesDownloaded, step.bytesTotal)

                    is SelfUpdateStep.Failed -> SelfUpdateUiState.Failed(step.error)

                    is SelfUpdateStep.Install -> when (val inner = step.step) {
                        is InstallStep.UserActionRequired -> {
                            _userActions.emit(inner.intent)
                            SelfUpdateUiState.Installing
                        }

                        is InstallStep.Rejected ->
                            SelfUpdateUiState.Failed(AppError.IntegrityFailed(SELF_UPDATE))

                        is InstallStep.Failed -> SelfUpdateUiState.Failed(AppError.Unexpected(null))

                        // There is no `Installed` to report: on a successful commit the system kills
                        // the process reading this line.
                        else -> SelfUpdateUiState.Installing
                    }
                }
            }
        }
    }

    fun dismissSelfUpdateFailure() {
        if (selfUpdateProgress.value is SelfUpdateUiState.Failed) {
            selfUpdateProgress.value = SelfUpdateUiState.Idle
        }
    }

    fun dismissUpdateAllResult() {
        if (updateAll.value is UpdateAllUiState.Finished) updateAll.value = UpdateAllUiState.Idle
    }

    /**
     * A single app, from the registered channel.
     *
     * `explicitVersion` is the one the check found, not "the one the rule would pick now": between the
     * check and the tap the store may have published something else, and the user pressed a button that
     * stated a precise number.
     */
    private suspend fun install(update: InstalledAppUpdate, channel: UpdateChannel): Boolean {
        var installed = false
        installApp(
            storeId = channel.storeId,
            ref = channel.ref,
            explicitVersion = update.available,
        ).collect { step ->
            when (step) {
                is InstallProgressStep.Install -> when (val inner = step.step) {
                    is InstallStep.UserActionRequired -> _userActions.emit(inner.intent)
                    is InstallStep.Installed -> installed = true
                    else -> Unit
                }

                // An assisted path cannot be carried forward from here: it needs a WebView and a
                // gesture on the store page. It counts as unsuccessful, and the user finds it on the
                // detail page with its own button.
                else -> Unit
            }
        }
        return installed
    }

    private data class UpdatesSnapshot(
        val available: List<InstalledAppUpdate> = emptyList(),
        val progress: UpdateAllUiState = UpdateAllUiState.Idle,
    )

    private data class RemoteSnapshot(
        val index: HomeIndex = HomeIndex(),
        val selfUpdate: SelfUpdateOffer? = null,
        val progress: SelfUpdateUiState = SelfUpdateUiState.Idle,
    )

    private data class SyncSnapshot(
        val inProgress: IndexStatus.Syncing? = null,
        val failure: AppError? = null,
        val consentRequired: Boolean = false,
    )

    private data class CatalogueSnapshot(
        val state: IndexState? = null,
        val recentlyUpdated: List<StoreListingSummary> = emptyList(),
        val categories: List<Category> = emptyList(),
    )

    private companion object {
        /** Survives a rotation without redoing the query on the index. */
        const val SUBSCRIPTION_GRACE_MILLIS = 5_000L

        /** What gets recorded when pre-install verification refuses **our own** APK. */
        const val SELF_UPDATE = "self-update"
    }
}

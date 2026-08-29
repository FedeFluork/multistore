package com.multistore.feature.downloads

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.multistore.core.common.result.AppError
import com.multistore.core.data.repository.DownloadRepository
import com.multistore.core.data.repository.DownloadStatus
import com.multistore.core.data.repository.InstallStep
import com.multistore.core.data.store.StoreRegistry
import com.multistore.core.domain.usecase.InstallAppUseCase
import com.multistore.core.domain.usecase.InstallProgressStep
import com.multistore.core.model.DownloadState
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.time.Instant
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One download, as the screen draws it.
 *
 * [title] arrives already resolved and never `null`: a download outlives the listing that produced
 * it, so the name can be gone — and a row with no name at all would be a row the user cannot
 * identify. The fallbacks are, in order, the package and the store's own reference.
 */
data class DownloadItem(
    val id: Long,
    val storeId: StoreId,
    val ref: StoreAppRef,
    val title: String,
    val iconUrl: String?,
    val storeName: String,
    val state: DownloadState,
    val bytesDownloaded: Long,
    val bytesTotal: Long?,
    val fraction: Float?,
    /**
     * The staged file is on disk.
     *
     * A field on its own and not folded into [readyToInstall], because the file outlives that
     * state: a cancelled transfer leaves a **partial** one, which is worth exactly as much — the
     * megabytes are paid for either way — and is what gives a paused row something to delete.
     */
    val hasFile: Boolean,
    /** When it was installed, or `null` if it never was. */
    val installedAt: Instant?,
    val createdAt: Instant,
    val error: AppError?,
    /** What this screen is doing to this row right now. */
    val install: RowInstallState = RowInstallState.Idle,
) {
    /** The APK is whole and nobody has installed it: the row that offers Install and Delete. */
    val readyToInstall: Boolean get() = state == DownloadState.READY && hasFile

    /**
     * The transfer is moving, or is about to: it can be stopped.
     *
     * Two states and not "everything that has not finished", written out rather than by exclusion,
     * because the set is exactly what `cancel` acts on — it stops the worker and parks the row.
     * `VERIFYING` is absent on purpose: nothing in the app writes that state today, and a button
     * whose only branch is unreachable is a branch nobody proves. `INSTALLING` is absent because a
     * `PackageInstaller` session is being written into at that moment, and there is nothing here
     * that could stop it halfway without leaving the system holding it.
     */
    val cancellable: Boolean
        get() = state == DownloadState.QUEUED || state == DownloadState.RUNNING

    /**
     * There is a file to throw away and nothing is touching it.
     *
     * The paused half is what keeps [cancellable] from creating a dead end: cancelling parks the row
     * with its partial file, and without a way out that row would sit on this screen for good —
     * this screen cannot restart a transfer, only the app's page can.
     */
    val deletable: Boolean
        get() = hasFile && (state == DownloadState.READY || state == DownloadState.PAUSED)

    /**
     * Nothing is going to happen to this row on its own, and nothing can be asked of it: history.
     *
     * The terminal states are the obvious half. The other half is a `READY` row whose file is gone,
     * and it is there because the two groups are decided by **different** columns: the state says
     * the transfer ended, the path says whether there is anything left to install. Left out of this,
     * such a row would sit under "In progress" saying "file deleted" — a heading contradicting the
     * line beneath it.
     */
    val settled: Boolean get() = state.isTerminal || (state == DownloadState.READY && !hasFile)
}

/**
 * What the Install button on **this row** is doing.
 *
 * Per row and not per screen: two files can be ready at once, and a single "installing" flag would
 * put the spinner on the wrong one. It carries no fine-grained stages — verifying, unpacking,
 * writing — because that detail belongs to the listing, which has room for it; here it would be
 * four words changing under a button.
 */
sealed interface RowInstallState {
    data object Idle : RowInstallState

    data object Working : RowInstallState

    data class Failed(val error: AppError) : RowInstallState

    /**
     * The pre-install verification refused the file, or the container could not be used.
     *
     * A state of its own rather than an `AppError.IntegrityFailed`, whose sentence reads "the file
     * was discarded" — which here would be false: nothing is deleted, the APK stays exactly where
     * it is. What that outcome carries is a card's worth of detail (which check, what was expected,
     * what was found) and this row has one line, so the honest thing is to say what happened and
     * point at the page that can show it.
     */
    data object Rejected : RowInstallState
}

sealed interface DownloadsUiState {
    data object Loading : DownloadsUiState

    data object Empty : DownloadsUiState

    /**
     * The three groups, drawn in this order: what is moving, what is waiting for a tap, what has
     * already happened.
     *
     * Three lists from **one** query, and that is not an optimisation: a row crosses between them
     * while it is being looked at — a transfer ends and becomes "ready", an installation succeeds
     * and becomes history — and three separate flows would emit at three moments, showing the same
     * app twice or not at all.
     */
    data class Ready(
        val active: List<DownloadItem>,
        val readyToInstall: List<DownloadItem>,
        val history: List<DownloadItem>,
    ) : DownloadsUiState
}

/**
 * The Downloads screen.
 *
 * ### Why the screen exists
 *
 * The transfer has lived in a worker since M1 and survives the listing it started from, so from the
 * moment the user walked away there was **no** surface in the app showing it — only a notification,
 * which sits outside the app and can be silenced. The progress card above the screens answered half
 * of that; the other half is a file that has finished downloading and that nobody installed, which
 * with the system installer stays where it is forever, invisible, in a private directory no file
 * manager opens.
 *
 * ### Why the history is here and not in "My apps"
 *
 * They answer different questions. "My apps" says what is **on the device now** and where it will
 * be updated from; this says what was **downloaded**, including what failed, what was deleted
 * without ever being installed, and the same app fetched twice from two stores. Merging them would
 * mean a list whose rows do not all support the one action the title promises.
 */
@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val downloads: DownloadRepository,
    private val installApp: InstallAppUseCase,
    private val registry: StoreRegistry,
) : ViewModel() {

    /**
     * The rows this screen is installing, and how it is going.
     *
     * It is screen state and not row state: the repository knows a download is `READY`, it does not
     * know that somebody pressed Install on it four hundred milliseconds ago. Keyed by id so that
     * two simultaneous installations do not overwrite each other's outcome.
     */
    private val installs = MutableStateFlow<Map<Long, RowInstallState>>(emptyMap())

    private val jobs = mutableMapOf<Long, Job>()

    private val confirmations = MutableStateFlow<DownloadsConfirmation?>(null)

    /**
     * The `Intent`s only a foreground UI may launch: the system's installation confirmation.
     *
     * A `SharedFlow` with no replay and not a state field, for the same reason as on the listing: an
     * intent is an **event**, and keeping it in the state would relaunch it on every recomposition
     * and every rotation.
     */
    private val _userActions = MutableSharedFlow<Intent>(extraBufferCapacity = 1)
    val userActions: SharedFlow<Intent> = _userActions.asSharedFlow()

    val uiState: StateFlow<DownloadsUiState> =
        combine(downloads.observeAll(), installs, confirmations) { rows, running, confirmation ->
            if (rows.isEmpty()) {
                DownloadsUiState.Empty
            } else {
                val items = rows.map { it.toItem(running[it.id] ?: RowInstallState.Idle) }
                DownloadsUiState.Ready(
                    active = items.filter { !it.readyToInstall && !it.settled },
                    readyToInstall = items.filter { it.readyToInstall },
                    history = items.filter { it.settled },
                )
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MILLIS),
            initialValue = DownloadsUiState.Loading,
        )

    /** What the screen is asking the user to confirm, if anything. */
    val confirmation: StateFlow<DownloadsConfirmation?> = confirmations

    /**
     * Installs a file already on disk.
     *
     * It goes through `InstallAppUseCase.resume` and not through some shorter path of its own: that
     * is the function that reads the version the row was downloading — not the one the rule would
     * choose now — and hands the file to the same seven-step verification every store's APK goes
     * through. A screen with its own installation path would be a store with a privileged one.
     */
    fun install(item: DownloadItem) {
        if (jobs[item.id]?.isActive == true) return
        installs.value += item.id to RowInstallState.Working
        jobs[item.id] = viewModelScope.launch {
            installApp.resume(storeId = item.storeId, ref = item.ref, downloadId = item.id)
                .collect { step -> apply(item.id, step) }
            // The row leaves `Working` even when nothing said how it ended: a flow that finishes
            // without an outcome — the assisted path, an unreachable listing — would otherwise leave
            // a spinner nobody can stop.
            if (installs.value[item.id] is RowInstallState.Working) installs.value -= item.id
            jobs -= item.id
        }
    }

    /**
     * Stops a transfer, at the user's request.
     *
     * It goes through [InstallAppUseCase.cancelDownload] — the same call the app's page makes — and
     * that matters twice over: the transfer lives in a worker, so anything short of telling the
     * worker would leave it running with a notification nobody can dismiss; and the meaning of the
     * word has to stay the same in the two places that use it. There it parks the download and
     * **keeps** what has come down; so it does here.
     *
     * No confirmation in front of it, and that is the same reasoning read the other way: nothing is
     * destroyed, so asking would be ceremony. What destroys is Delete on the row this leaves behind,
     * and that one asks.
     */
    fun cancel(item: DownloadItem) {
        viewModelScope.launch { installApp.cancelDownload(item.id) }
    }

    fun requestDelete(item: DownloadItem) {
        confirmations.value = DownloadsConfirmation.Delete(item.id, item.title)
    }

    fun requestClearHistory() {
        confirmations.value = DownloadsConfirmation.ClearHistory
    }

    fun dismissConfirmation() {
        confirmations.value = null
    }

    /**
     * Carries out what was being confirmed.
     *
     * Both branches are destructive and neither is undoable: deleting a staged APK costs the
     * transfer again, and an emptied history does not come back. That is why they arrive here
     * through a confirmation instead of straight from a tap.
     */
    fun confirm() {
        val pending = confirmations.value ?: return
        confirmations.value = null
        viewModelScope.launch {
            when (pending) {
                is DownloadsConfirmation.Delete -> downloads.deleteStaged(pending.id)
                DownloadsConfirmation.ClearHistory -> downloads.clearHistory()
            }
        }
    }

    private suspend fun apply(id: Long, step: InstallProgressStep) {
        when (step) {
            is InstallProgressStep.Install -> when (val inner = step.step) {
                is InstallStep.UserActionRequired -> _userActions.emit(inner.intent)
                is InstallStep.Installed -> installs.value -= id
                InstallStep.Cancelled -> installs.value -= id
                is InstallStep.Failed -> installs.value += id to RowInstallState.Failed(
                    AppError.InstallFailed(inner.statusCode, inner.message),
                )

                is InstallStep.Rejected -> installs.value += id to RowInstallState.Rejected
                is InstallStep.ContainerRejected -> installs.value += id to RowInstallState.Rejected

                // Verifying, unpacking, writing, committing, placing expansions: the row is already
                // showing "working", and four words changing under a button say less than the one
                // that stays.
                else -> Unit
            }

            is InstallProgressStep.Failed -> installs.value += id to RowInstallState.Failed(step.error)
            InstallProgressStep.Incompatible ->
                installs.value += id to RowInstallState.Failed(AppError.NotFound)

            // Resolving, downloading, the assisted path, a signature conflict: none of them can be
            // reached from here — the file is on disk and `resume` skips resolution — and inventing
            // a state for them would be drawing something that never happens.
            else -> Unit
        }
    }

    private fun DownloadStatus.toItem(install: RowInstallState) = DownloadItem(
        id = id,
        storeId = storeId,
        ref = ref,
        // A download outlives its listing, so the name can be gone. The package is the next best
        // thing the user recognises; the store's reference is a slug or a page id, and it is the
        // last resort precisely because it is nobody's name.
        title = title ?: packageName ?: ref.value,
        iconUrl = iconUrl,
        storeName = registry.adapter(storeId)?.metadata?.displayName ?: storeId.name,
        state = state,
        bytesDownloaded = bytesDownloaded,
        bytesTotal = bytesTotal,
        fraction = fraction,
        // The file has to be **there**, not merely remembered. What normally makes that true is not
        // this line but the write that goes with every deletion: the storage cleanup and the Delete
        // button both clear `file_path` and close the row, so a `READY` row with no file should not
        // exist. This is the guard for the day the two disagree anyway, and what it buys is an
        // Install button that is never over nothing.
        hasFile = file != null,
        installedAt = installedAt,
        createdAt = createdAt,
        error = error,
        install = install,
    )

    private companion object {
        const val SUBSCRIPTION_GRACE_MILLIS = 5_000L
    }
}

/** The two destructive gestures of this screen, each with what it is about to destroy. */
sealed interface DownloadsConfirmation {
    data class Delete(val id: Long, val title: String) : DownloadsConfirmation

    data object ClearHistory : DownloadsConfirmation
}

package com.multistore.feature.myapps

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.multistore.core.common.result.AppError
import com.multistore.core.common.version.VersionSelection
import com.multistore.core.data.repository.InstallStep
import com.multistore.core.data.repository.InstalledAppUpdate
import com.multistore.core.data.store.StoreRegistry
import com.multistore.core.domain.usecase.ObserveInstalledAppsUseCase
import com.multistore.core.domain.usecase.ObserveUpdatesUseCase
import com.multistore.core.domain.usecase.UninstallAppUseCase
import com.multistore.core.model.InstalledApp
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
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
 * A "My apps" row: what is in the database plus the store's name.
 *
 * The store name is declared by the adapter and not by `strings.xml`: "F-Droid" and "APKMirror" are
 * proper names, not interface text, and translating them would be wrong in all five languages. It stays
 * `null` for a row pointing at a store this build has not wired — that does not happen today, it will
 * if an adapter is ever removed leaving the installations in place, and it is better than a row that
 * disappears.
 */
data class InstalledAppItem(
    val app: InstalledApp,
    val storeName: String?,
    val update: UpdateState = UpdateState.UpToDate,
) {
    /** `true` if the listing it came from is known: without it there is no detail page to open. */
    val hasDetail: Boolean get() = app.sourceStoreId != null && app.sourceRef != null
}

/**
 * What to say about this app, as regards updates.
 *
 * Six states and not two, because "there is no update" has five different reasons and each leads the
 * user to a different gesture: resume a pause, remove a pin, choose a channel, or nothing. A two-valued
 * enum would blur them all into "up to date", which is true in one case only.
 */
sealed interface UpdateState {

    /** Nothing newer on the channel, and the comparison could actually be made. */
    data object UpToDate : UpdateState

    data class Available(val versionName: String) : UpdateState

    /** The user paused the notices. [available] says whether there was anything to notify about. */
    data class Paused(val available: Boolean) : UpdateState

    /** The user pinned the app, and the pin is holding [heldBack] back. */
    data class Pinned(val versionCode: Long, val heldBack: String) : UpdateState

    /**
     * The store does not publish the `versionCode`: there is no knowing whether something better exists.
     *
     * uptodown is entirely in here, and that is not a defect of ours: the site does not publish it
     * anywhere. Saying so is the only honest alternative to pretending the app is up to date.
     */
    data object Undeterminable : UpdateState

    /** No knowing which listing to update it from: no channel, or a channel gone from the catalogue. */
    data object NoChannel : UpdateState
}

/** How far a manually requested update check has got. */
sealed interface UpdateCheckUiState {

    data object Idle : UpdateCheckUiState

    data object Running : UpdateCheckUiState

    /** The check finished, but [stores] stores did not answer. */
    data class Incomplete(val stores: Int) : UpdateCheckUiState
}

/** How far an uninstall has got, which is always a conversation with the user. */
sealed interface UninstallUiState {

    data object Idle : UninstallUiState

    /** The confirmation dialog is open. */
    data class Confirming(val packageName: String, val label: String) : UninstallUiState

    /**
     * The uninstall has started.
     *
     * That includes waiting for the system confirmation screen, which is the longest part: the `Intent`
     * is launched by the UI, and until the user answers no step arrives.
     */
    data class InProgress(val packageName: String) : UninstallUiState

    data class Failed(val error: AppError, val systemMessage: String?) : UninstallUiState
}

sealed interface MyAppsUiState {

    data object Loading : MyAppsUiState

    data object Empty : MyAppsUiState

    data class Ready(
        val apps: List<InstalledAppItem>,
        val uninstall: UninstallUiState,
        val check: UpdateCheckUiState = UpdateCheckUiState.Idle,
    ) : MyAppsUiState {
        val updatable: Int get() = apps.count { it.update is UpdateState.Available }
    }
}

/**
 * "My apps": only what went through MultiStore.
 *
 * The scope is a deliberate decision and not a technical limitation — `QUERY_ALL_PACKAGES` is there and
 * would allow listing everything. Listing everything, though, would mean promising updates for apps
 * whose origin is unknown, and the multi-store update rule rests exactly on that origin.
 *
 * [reconcile] is not a detail: between two visits an app may have gone from the system settings, and
 * without reconciliation the list would show a ghost on which the only possible action — uninstalling —
 * would fail.
 */
@HiltViewModel
class MyAppsViewModel @Inject constructor(
    private val installedApps: ObserveInstalledAppsUseCase,
    private val updates: ObserveUpdatesUseCase,
    private val uninstallApp: UninstallAppUseCase,
    registry: StoreRegistry,
) : ViewModel() {

    private val uninstall = MutableStateFlow<UninstallUiState>(UninstallUiState.Idle)
    private val check = MutableStateFlow<UpdateCheckUiState>(UpdateCheckUiState.Idle)
    private var checkJob: Job? = null

    /**
     * The `Intent`s the UI has to launch: here, the system uninstall confirmation.
     *
     * Same shape as the detail page and for the same reason: since API 34 that activity cannot start
     * from the background, so only something that knows it is in the foreground may launch it — and a
     * ViewModel does not. A `SharedFlow` with no replay because an intent is an **event**: in the state
     * it would be relaunched on every recomposition and every rotation.
     */
    private val _userActions = MutableSharedFlow<Intent>(extraBufferCapacity = 1)
    val userActions: SharedFlow<Intent> = _userActions.asSharedFlow()

    /**
     * The list comes from `UpdateRepository` and no longer from `InstalledAppsRepository`.
     *
     * The two things a row has to say — which version is there and whether a newer one exists — are read
     * together or not at all: keeping them on two separate flows would mean combining, in this ViewModel,
     * two lists that can be one emission out of step, with a row saying "update available" next to an
     * already-updated version.
     */
    val uiState: StateFlow<MyAppsUiState> =
        combine(updates(), uninstall, check) { apps, uninstallState, checkState ->
            when {
                apps.isEmpty() -> MyAppsUiState.Empty
                else -> MyAppsUiState.Ready(
                    apps = apps.map { update ->
                        InstalledAppItem(
                            app = update.app,
                            storeName = update.app.sourceStoreId
                                ?.let { registry.adapter(it)?.metadata?.displayName },
                            update = update.toUiState(),
                        )
                    },
                    uninstall = uninstallState,
                    check = checkState,
                )
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MILLIS),
            initialValue = MyAppsUiState.Loading,
        )

    /**
     * Realigns the list with the device.
     *
     * It must be called on every return to the foreground and not only at startup: an uninstall done from
     * the system settings does not go through us, and `PackageEvents` — which the detail page listens to
     * — says something changed, not that our table is aligned.
     */
    fun reconcile() {
        viewModelScope.launch { installedApps.reconcile() }
    }

    /**
     * Checks now, because the user asked.
     *
     * `force = true`: a manual gesture has to do something even when the listings are formally fresh,
     * otherwise pressing the button and not pressing it would be indistinguishable.
     *
     * Two checks at once make no sense — they would query the same stores for the same result — so the
     * second is ignored rather than queued.
     */
    fun checkForUpdates() {
        if (checkJob?.isActive == true) return
        checkJob = viewModelScope.launch {
            check.value = UpdateCheckUiState.Running
            val report = updates.check(force = true)
            check.value = if (report.complete) {
                UpdateCheckUiState.Idle
            } else {
                UpdateCheckUiState.Incomplete(report.failures.size)
            }
        }
    }

    fun dismissCheckResult() {
        if (check.value is UpdateCheckUiState.Incomplete) check.value = UpdateCheckUiState.Idle
    }

    /**
     * Pauses or resumes notices for this app.
     *
     * It does not prevent installing it by hand from the detail page: pausing means "do not disturb me",
     * not "do not let me do it".
     */
    fun setIgnoreUpdates(item: InstalledAppItem, ignore: Boolean) {
        viewModelScope.launch { updates.setIgnoreUpdates(item.app.packageName, ignore) }
    }

    /**
     * Pins the app to the installed version, or removes the pin.
     *
     * The pin is **to the version installed right now**, not to a free choice: pinning to a number one
     * does not have yet would be an instruction to go up, that is the opposite of what a pin means.
     */
    fun setPinnedToInstalled(item: InstalledAppItem, pinned: Boolean) {
        viewModelScope.launch {
            updates.setPinnedVersionCode(
                packageName = item.app.packageName,
                versionCode = if (pinned) item.app.versionCode else null,
            )
        }
    }

    fun requestUninstall(item: InstalledAppItem) {
        if (uninstall.value is UninstallUiState.InProgress) return
        uninstall.value = UninstallUiState.Confirming(item.app.packageName, item.app.label)
    }

    fun dismissUninstall() {
        if (uninstall.value is UninstallUiState.InProgress) return
        uninstall.value = UninstallUiState.Idle
    }

    /**
     * Confirms the uninstall.
     *
     * The row disappears on its own: `InstallRepository.uninstall` calls `forget` **only** when the system
     * confirms, and Room re-emits. Removing it optimistically here would leave the list and the device
     * disagreeing for as long as the user looks at the system dialog, and disagreeing forever if they
     * cancel it.
     */
    fun confirmUninstall() {
        val confirming = uninstall.value as? UninstallUiState.Confirming ?: return
        val packageName = confirming.packageName

        viewModelScope.launch {
            uninstall.value = UninstallUiState.InProgress(packageName)
            uninstallApp(packageName).collect { step ->
                when (step) {
                    is InstallStep.UserActionRequired -> _userActions.emit(step.intent)
                    is InstallStep.Uninstalled -> uninstall.value = UninstallUiState.Idle
                    InstallStep.Cancelled -> uninstall.value = UninstallUiState.Idle
                    is InstallStep.Failed -> uninstall.value = UninstallUiState.Failed(
                        error = AppError.InstallFailed(step.statusCode, step.message),
                        systemMessage = step.message,
                    )

                    // Verification, writing and commit belong to installation: an uninstall does not go
                    // through them, and inventing a state for them would mean drawing something that
                    // never happens.
                    else -> Unit
                }
            }
        }
    }

    fun dismissFailure() {
        if (uninstall.value is UninstallUiState.Failed) uninstall.value = UninstallUiState.Idle
    }

    /**
     * From the rule's outcome to the sentence to show.
     *
     * The order of the branches puts the user's decisions before the rule's: a pause and a pin are things
     * they chose, and have to be said even when the rule would have something else to say — otherwise a
     * forgotten pause stays invisible forever.
     */
    private fun InstalledAppUpdate.toUiState(): UpdateState = when {
        app.ignoreUpdates -> UpdateState.Paused(available = suppressed)
        selection == null -> UpdateState.NoChannel
        available != null -> UpdateState.Available(available!!.versionName)
        selection is VersionSelection.Outcome.Pinned -> UpdateState.Pinned(
            versionCode = (selection as VersionSelection.Outcome.Pinned).pinnedVersionCode,
            heldBack = (selection as VersionSelection.Outcome.Pinned).heldBack.versionName,
        )
        // The uptodown case: the store does not publish the versionCode, so "up to date" would be an
        // observation we never made.
        (selection as? VersionSelection.Outcome.UpToDate)?.comparable == false ->
            UpdateState.Undeterminable
        else -> UpdateState.UpToDate
    }

    private companion object {
        const val SUBSCRIPTION_GRACE_MILLIS = 5_000L
    }
}

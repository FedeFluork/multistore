package com.multistore.app

import android.content.Intent
import com.multistore.core.common.coroutine.ApplicationScope
import com.multistore.core.data.repository.DownloadRepository
import com.multistore.core.data.repository.InstallRepository
import com.multistore.core.data.repository.InstallStep
import com.multistore.core.data.repository.SettingsRepository
import com.multistore.core.domain.usecase.ActiveInstallDrivers
import com.multistore.core.domain.usecase.InstallAppUseCase
import com.multistore.core.domain.usecase.InstallProgressStep
import com.multistore.core.model.DownloadState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Carries a download on to its installation when the listing that started it is no longer there.
 *
 * ### The gap it fills
 *
 * Since M1 the transfer lives in a worker and **survives the screen** — leaving a listing must not
 * throw eighteen megabytes away. What does not survive is the rest of the path: the flow that waits
 * for the file and hands it to the installer runs in the listing's scope, so walking away leaves the
 * APK in staging with nobody to install it. That is the app's behaviour and it is deliberate — a
 * system dialog appearing over whatever the user has moved on to is an interruption — but it was
 * also the *only* behaviour, and `auto_install_after_download` is what makes it a choice.
 *
 * ### Which downloads it is allowed to touch, and why each condition is there
 *
 * Four, and none of them is redundant.
 *
 *  1. **the row asked for it** — `pending_install`. A transfer the periodic check started with
 *     `auto_install_updates` off was asked to download *and stop*: proposing it here would overrule
 *     a setting the user has already answered.
 *  2. **no screen is driving it** — [ActiveInstallDrivers]. While the listing is on screen it is
 *     waiting for that same file, and two `PackageInstaller` sessions on one APK are two dialogs
 *     for one app.
 *  3. **it finished during this process's life.** Rows already `READY` at the first emission are
 *     remembered and never touched: they are downloads that ended at some unknown point in the
 *     past — yesterday, last week — and proposing them at the next launch is a dialog with no
 *     cause the user can see. This is what makes the feature "when the download finishes" rather
 *     than "when you next open the app".
 *  4. **it wins the claim** — `claimPendingInstall`, an `UPDATE … WHERE pending_install = 1` that
 *     SQLite settles. It closes the microseconds between reading condition 2 and the driver
 *     releasing it, and it is also what stops a loop: a confirmation the user dismisses leaves the
 *     row `READY` with the token spent.
 *
 * ### And the switch is re-read, not captured
 *
 * Both the switch and "would this device install silently anyway" are read **at the moment of
 * acting**. A value taken when the graph was built would make the Settings entry do nothing until
 * the next launch — the defect already corrected in M3 on the update scheduling and in M4 on the
 * challenge strategy — and the silent check specifically has to be live: the stored `true` may come
 * from before Shizuku was installed on this device.
 *
 * ### Why the intents leave through a channel
 *
 * From API 34 the system's confirmation cannot be started from the background, so this class must
 * not launch it: it hands it to [userActions], which `MainActivity` collects while STARTED. A
 * `Channel` and not a `SharedFlow` with replay, because an intent must be delivered **once** — a
 * replayed one would relaunch the dialog on every return to the foreground. Buffered, so a transfer
 * finishing while the app is away still gets its dialog when the user comes back.
 */
@Singleton
class AutoInstallCoordinator @Inject constructor(
    private val downloads: DownloadRepository,
    private val installApp: InstallAppUseCase,
    private val installs: InstallRepository,
    private val settings: SettingsRepository,
    private val drivers: ActiveInstallDrivers,
    @param:ApplicationScope private val scope: CoroutineScope,
) {

    private val intents = Channel<Intent>(capacity = INTENT_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** The confirmation dialogs to launch. Only something that knows it is in the foreground may. */
    val userActions: Flow<Intent> = intents.receiveAsFlow()

    /**
     * What was already waiting when the process started: never proposed.
     *
     * `null` until the first emission, and that is the difference between "nothing was waiting" and
     * "we have not looked yet". Reading it as the first would propose, on the very first emission,
     * every file left over from previous sessions.
     */
    private var preexisting: Set<Long>? = null

    private val handled = mutableSetOf<Long>()

    /**
     * One at a time.
     *
     * Two confirmation dialogs launched together is one of them covering the other, and the covered
     * one is dismissed by a tap meant for the first. The queue costs nothing: these are events that
     * happen minutes apart.
     */
    private val gate = Mutex()

    fun start() {
        scope.launch {
            downloads.observeActive().collect { rows ->
                val ready = rows.filter { it.state == DownloadState.READY }
                val known = preexisting
                if (known == null) {
                    preexisting = ready.map { it.id }.toSet()
                    return@collect
                }
                for (row in ready) {
                    if (row.id in known || row.id in handled) continue
                    if (!row.pendingInstall || drivers.isDriven(row.id)) continue
                    if (!shouldPropose()) continue
                    if (!downloads.claimPendingInstall(row.id)) continue
                    handled += row.id
                    launch { install(row.id, row.storeId, row.ref) }
                }
            }
        }
    }

    /**
     * Whether an installation started by itself would mean anything on this device right now.
     *
     * The switch is only offered where installing shows a prompt: with root or Shizuku selected
     * there is nothing to propose, the Settings row is disabled with that reason next to it, and
     * this is the same predicate — so a stored `true` from before a privileged channel appeared
     * does not silently change what happens.
     */
    private suspend fun shouldPropose(): Boolean {
        val installation = settings.installation.first()
        if (!installation.autoInstallAfterDownload) return false
        return !installs.installerAvailability().installsSilently(installation.preference)
    }

    private suspend fun install(
        id: Long,
        storeId: com.multistore.core.model.StoreId,
        ref: com.multistore.core.model.StoreAppRef,
    ) = gate.withLock {
        installApp.resume(storeId = storeId, ref = ref, downloadId = id).collect { step ->
            val inner = (step as? InstallProgressStep.Install)?.step
            if (inner is InstallStep.UserActionRequired) intents.send(inner.intent)
        }
    }

    private companion object {
        /**
         * Enough for the case that actually happens — a couple of transfers finishing while the app
         * is away — and not unbounded: a queue of confirmations older than the session is a stack of
         * dialogs nobody asked for on the next return. The dropped ones are not lost, they are on
         * the Downloads screen with their Install button.
         */
        const val INTENT_BUFFER = 4
    }
}

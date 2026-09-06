package com.multistore.core.domain.usecase

import android.content.Intent
import com.multistore.core.data.repository.InstallStep
import com.multistore.core.data.repository.InstalledAppUpdate
import com.multistore.core.data.repository.UpdateChannel
import com.multistore.core.data.repository.UpdateRepository
import com.multistore.core.model.OwnPackage
import com.multistore.core.model.UpdateAllUiState
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow

/**
 * How far an "update everything" has got.
 *
 * It is not an atomic operation and must not be told as one: with only `SessionInstaller` every app
 * asks for its own system confirmation, so five updates are five dialogs in a row. Saying where we
 * are is the only thing that makes that queue understandable rather than exhausting.
 *
 * [UserAction] is in here and not handled internally for the reason written all over this project:
 * since API 34 the system confirmation activity cannot start from the background, so only something
 * that knows it is in the foreground may launch it — and neither a use case nor a ViewModel does.
 * It travels out and a screen launches it.
 */
sealed interface UpdateAllStep {

    /** About to start the [done]-th of [total], named [label]. `done` is zero-based. */
    data class Progress(val done: Int, val total: Int, val label: String) : UpdateAllStep

    /** The system wants a confirmation. Whoever is in the foreground has to launch this. */
    data class UserAction(val intent: Intent) : UpdateAllStep

    /** [failed] includes cancellations: whoever said no to the system dialog. */
    data class Finished(val installed: Int, val failed: Int) : UpdateAllStep
}

/**
 * Updates everything that has something newer, one app at a time.
 *
 * ### Why it lives here and not in a ViewModel
 *
 * It used to live in `HomeViewModel`, and "My apps" — the screen that actually **lists** what is
 * updatable, one row at a time, with its own count — did not have it: somebody who got there after
 * seeing four "update available" rows had to go back to the Home to apply them.
 *
 * The obvious fix, copying the loop into the second ViewModel, is the one this project forbids by
 * construction: a `:feature:*` may not depend on another `:feature:*`, and two copies of a rule are
 * two copies that diverge. What they would have diverged on is not cosmetic — the MultiStore-last
 * ordering below, and the decision not to retry — so the loop moved down to where both can see it.
 *
 * ### In sequence, not together
 *
 * With only `SessionInstaller` every installation opens the system confirmation screen, and two of
 * those at once do not exist. The loop therefore waits for each to finish — the wait is inside
 * `collect`, because the install flow closes when the system reports the outcome.
 *
 * ### MultiStore goes last
 *
 * Updating itself kills the process halfway through the commit, and with it this loop: the apps
 * after it would never be touched, and the user would have no way of knowing which. Putting it at
 * the end costs a `sortedBy` and removes that case. No store publishes MultiStore today, so the line
 * is never walked — but the day one does, the normal path takes it without anybody having to
 * remember.
 */
class UpdateAllAppsUseCase @Inject constructor(
    private val updates: UpdateRepository,
    private val installApp: InstallAppUseCase,
    private val ownPackage: OwnPackage,
) {

    /**
     * The flow completes when everything has been attempted. It emits nothing at all when there is
     * nothing to update — not even a [UpdateAllStep.Finished] with two zeros, which would put "0
     * updated, 0 failed" on screen in answer to a button the user could not have pressed.
     */
    operator fun invoke(): Flow<UpdateAllStep> = flow {
        val targets = updates.observeAvailable()
            .first()
            .sortedBy { it.app.packageName == ownPackage.name }
        if (targets.isEmpty()) return@flow

        var installed = 0
        var failed = 0
        targets.forEachIndexed { index, update ->
            val channel = update.channel ?: return@forEachIndexed
            emit(
                UpdateAllStep.Progress(
                    done = index,
                    total = targets.size,
                    label = channel.title,
                ),
            )
            if (install(update, channel)) installed++ else failed++
        }
        emit(UpdateAllStep.Finished(installed = installed, failed = failed))
    }

    /**
     * A single app, from the registered channel.
     *
     * `explicitVersion` is the one the check found, not "the one the rule would pick now": between
     * the check and the tap the store may have published something else, and the user pressed a
     * button that stated a precise number.
     */
    private suspend fun kotlinx.coroutines.flow.FlowCollector<UpdateAllStep>.install(
        update: InstalledAppUpdate,
        channel: UpdateChannel,
    ): Boolean {
        var installed = false
        installApp(
            storeId = channel.storeId,
            ref = channel.ref,
            explicitVersion = update.available,
        ).collect { step ->
            when (step) {
                is InstallProgressStep.Install -> when (val inner = step.step) {
                    is InstallStep.UserActionRequired -> emit(UpdateAllStep.UserAction(inner.intent))
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
}

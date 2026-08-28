package com.multistore.core.updates

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.multistore.core.data.repository.InstalledAppUpdate
import com.multistore.core.data.store.StoreRegistry
import com.multistore.core.data.repository.InstallStep
import com.multistore.core.data.repository.SettingsRepository
import com.multistore.core.domain.usecase.InstallAppUseCase
import com.multistore.core.domain.usecase.InstallProgressStep
import com.multistore.core.domain.usecase.ObserveUpdatesUseCase
import com.multistore.core.domain.usecase.Unattended
import com.multistore.core.model.OwnPackage
import com.multistore.core.model.StoreId
import com.multistore.core.model.UpdateSettings
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

/**
 * The periodic update check.
 *
 * ### What it queries
 *
 * Only the channels of the apps installed through MultiStore, and not even those of the apps the user
 * has paused. It is not crawling: it is one request per app, on the stores read page by page, and
 * **a single sync** on the local-index ones. The pace is set by each store's rate limiter, and the
 * circuit breaker stays in the middle as always.
 *
 * ### Why it does not retry
 *
 * A `Result.retry()` would trigger WorkManager's backoff, i.e. would knock again at a door that has
 * just said no — on sites that declare a `Crawl-delay` and answer 429 to whoever ignores it. The next
 * period will come anyway, and meanwhile what the other stores answered is already in the catalogue.
 * It is the same choice as `DownloadWorker`, for the same reason.
 *
 * ### What it does by itself, and only if asked
 *
 * With the switches off — i.e. for whoever has never opened Settings — it stops at the updated
 * catalogue and the notice. `auto_download_updates` makes it download what it found,
 * `auto_install_updates` also install it. They are two switches and not one because the second has a
 * prerequisite the first does not: an installer that does not ask for confirmation. Without Shizuku
 * or root there is none, and an "automatic" update would be a system screen appearing on its own —
 * which from the background, from API 34, does not appear at all.
 *
 * The "silent only" constraint travels inside the installation plan and does not stay here: between
 * the moment of deciding and the moment of choosing the installer there is a whole download, and in
 * between Shizuku may have stopped. See `InstallPlan.requireSilent`.
 *
 * ### Why it waits for the downloads instead of just queueing them
 *
 * Waiting costs: WorkManager stops a worker after ten minutes, and on a slow connection with three
 * large updates one gets there. The choice is this one all the same, because the alternative —
 * queueing and leaving — would make installing impossible: between the end of the download and the
 * installation there would be nobody left.
 *
 * And the case where the ten minutes run out is not a fault. The transfer lives in its own worker,
 * with its own foreground service, and carries on; the file stays in staging; at the next period
 * `enqueue` returns **that same row**, `start` finds it already complete and the wait ends
 * immediately. What has been lost is a period, not a download.
 */
@HiltWorker
class UpdateCheckWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val updates: ObserveUpdatesUseCase,
    private val installApp: InstallAppUseCase,
    private val settings: SettingsRepository,
    private val notifications: UpdateNotifications,
    private val ownPackage: OwnPackage,
    private val registry: StoreRegistry,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // The report **is read**, and until recently nobody read it: `check()` returned which stores
        // had not answered and the line discarded it. It was not lost diagnostics — it was the most
        // useful news this worker produces, because the set of queried stores is exactly the set of the
        // installed apps' update channels.
        val report = updates.check()

        val preferences = settings.updates.first()
        val notifyPreferences = settings.notifications.first()
        val outcome = if (preferences.downloadsByItself) apply(preferences) else Outcome()

        // The list is re-read **after** the check and after what was done automatically, and from the
        // catalogue: the report says how many apps were queried, not which have something new, and what
        // has been installed in the meantime is no longer an available update. Announcing beforehand
        // would mean a notification listing precisely the apps just updated.
        val pending = updates.available().first()

        if (!preferences.muteNotifications) {
            notifications.showAvailable(pending.mapNotNull { it.channel?.title })
        }

        // The three pieces of news, each with its switch and its channel. They are shown **after** the
        // list of updates and in the order in which they happened: first what was done, then what is
        // waiting, then what did not answer.
        if (!notifyPreferences.muteInstallResult) {
            notifications.showInstallResult(outcome.installed, outcome.failed)
        }
        if (!notifyPreferences.muteDownloadComplete) {
            notifications.showReadyToInstall(outcome.readyToInstall)
        }
        if (!notifyPreferences.muteStoreAlerts) {
            notifications.showStoreAlerts(report.failures.keys.map(::storeName))
        }

        // Always `success`, even when a store did not answer: see the note on why there is no retry. A
        // failure here would add nothing — there is nobody to read it — and would make the work look
        // broken in `WorkInfo`.
        return Result.success()
    }

    /**
     * Downloads — and, if the user has asked, installs — what the check found.
     *
     * One app at a time, and MultiStore last. Sequential because two installations at once do not
     * exist anyway, and MultiStore last because updating oneself kills the process in the middle of
     * the commit: the apps after it would never be touched. Today none of the nine stores publishes
     * MultiStore, so the line is not travelled — but it is the same rule "update everything" follows on
     * the Home, and having only one is the point.
     */
    /**
     * What the round actually got up to.
     *
     * Three lists and not a boolean: they are three outcomes coexisting in the same round — two apps
     * updated, one downloaded waiting for a tap, one refused for a different signature — and
     * summarising them into "it went well / badly" would lose exactly what needs saying.
     */
    private data class Outcome(
        val installed: List<String> = emptyList(),
        val failed: List<String> = emptyList(),
        val readyToInstall: List<String> = emptyList(),
    )

    private suspend fun apply(preferences: UpdateSettings): Outcome {
        val unattended = Unattended(
            // The periodic work already has its network constraint, but that one applies to **this**
            // worker: the transfer runs in another, which takes its own constraint from here. Without
            // it, an automatic download would start on the mobile data of somebody who said no.
            requireUnmetered = !settings.network.first().meteredNetworkAllowed,
            downloadOnly = !preferences.autoInstall,
        )

        val installed = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val ready = mutableListOf<String>()

        updates.available().first()
            .sortedBy { it.app.packageName == ownPackage.name }
            .forEach { update ->
                // `app.label` and not `channel.title`: the name the app appears under on the device is
                // the one the user recognises, and on five stores out of nine the listing's title drags
                // "MOD APK" and the version number along with it.
                when (apply(update, unattended)) {
                    Applied.INSTALLED -> installed += update.app.label
                    Applied.FAILED -> failed += update.app.label
                    Applied.READY -> ready += update.app.label
                    Applied.NOTHING -> Unit
                }
            }

        return Outcome(installed = installed, failed = failed, readyToInstall = ready)
    }

    /** What happened to **one** app. */
    private enum class Applied { INSTALLED, FAILED, READY, NOTHING }

    private suspend fun apply(update: InstalledAppUpdate, unattended: Unattended): Applied {
        val channel = update.channel ?: return Applied.NOTHING
        val version = update.available ?: return Applied.NOTHING

        // `explicitVersion` and not "the one the rule would choose now": the check has just decided it,
        // and redoing the choice here would mean being able to download something different from what
        // was announced.
        //
        // The flow used to be consumed and nothing more, with the note that "there is nobody to show it
        // to". That was true and the opposite stayed true: precisely because there is nobody, the
        // outcome is the only thing that can say so afterwards. What happened does stay written where
        // it counts — `installed_apps`, the download's row — but "written" and "told" are not the same
        // thing, and an update refused for a different signature does not present itself again.
        var applied = Applied.NOTHING
        installApp(
            storeId = channel.storeId,
            ref = channel.ref,
            explicitVersion = version,
            unattended = unattended,
        ).collect { step ->
            applied = when (step) {
                // "Downloaded and stopped here" is the successful outcome of "download by yourself,
                // then I will deal with it": the file is there, verification passed, a person is missing.
                is InstallProgressStep.Downloaded -> Applied.READY
                is InstallProgressStep.Failed -> Applied.FAILED
                // This one is not a fault either — no silent installer, the file stays in staging —
                // and it is indistinguishable from the case above to whoever reads the notification.
                is InstallProgressStep.Install -> when (step.step) {
                    is InstallStep.Installed -> Applied.INSTALLED
                    InstallStep.SilentUnavailable -> Applied.READY
                    is InstallStep.Rejected -> Applied.FAILED
                    else -> applied
                }

                // The signature does not match the installed one, or the app does not run on this
                // device: two different reasons, one effect — that update will never arrive by itself,
                // and staying silent would make it invisible forever.
                is InstallProgressStep.SignerConflict -> Applied.FAILED
                InstallProgressStep.Incompatible -> Applied.FAILED
                else -> applied
            }
        }
        return applied
    }

    /**
     * The name a store presents itself with.
     *
     * The registry knows it; the `wireName` is the fallback for a store declared and not yet
     * registered, which is a case that should not happen and is not worth a silent notification.
     */
    private fun storeName(storeId: StoreId): String =
        registry.adapter(storeId)?.metadata?.displayName ?: storeId.wireName
}

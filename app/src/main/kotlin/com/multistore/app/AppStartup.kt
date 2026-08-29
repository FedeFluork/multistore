package com.multistore.app

import com.multistore.core.common.coroutine.ApplicationScope
import com.multistore.core.data.repository.DownloadRepository
import com.multistore.core.data.repository.InstallRepository
import com.multistore.core.data.repository.InstalledAppsRepository
import com.multistore.core.data.repository.MaintenanceRepository
import com.multistore.core.data.repository.RemoteConfigRepository
import com.multistore.core.data.repository.RemoteIndexRepository
import com.multistore.core.data.repository.StoreHealthRepository
import com.multistore.core.data.system.PackageEvents
import com.multistore.core.updates.UpdateNotice
import com.multistore.core.updates.UpdateScheduling
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * The seven repairs that have to run once per process, plus one scheduling.
 *
 * They are not initialisations: they are **repairs**. Each exists because the process can die at
 * any moment, and what is left afterwards is not wrong but incomplete.
 *
 *  1. `registerKnownStores()` — writes into `stores` the rows for the wired adapters, without
 *     touching those already there. Without it the circuit breaker would have nowhere to live and
 *     the user's choice of which stores to query nowhere to be read. It is also what instantiates
 *     every adapter, and therefore what registers their network profiles **before** a download can
 *     build a coarser one (see `NetworkModule`).
 *  2. `requeueInterrupted()` — a download killed halfway stays `RUNNING` in Room forever: the UI
 *     shows it in progress and nobody carries it forward.
 *  3. `reconcile()` — an app can disappear without going through us, uninstalled from the system
 *     settings. Without this, "My apps" would list ghosts and the update check would try to update
 *     them.
 *  4. `reconcileAbandonedSessions()` — a `PackageInstaller` session outlives the process that
 *     created it, but the receiver collecting its outcome does not: it stays committed, holds its
 *     staging in `/data/app` and reports to nobody. Measured on the emulator: 17.7 MB for a single
 *     interrupted confirmation.
 *  5. `pruneOldEvents()` — nobody deletes `health_events`. The DAO had the method from day one and
 *     had no callers: the table grew forever, and diagnostics meant for reading what went wrong
 *     **recently** get worse the longer it gets.
 *  6. `purgeStale()` — listings expired beyond the chosen retention, and staged APKs no row claims
 *     any more. It is a repair like the others and not generic hygiene: the case that made it
 *     necessary is **MultiStore's own APK**. `InstallSelfUpdateUseCase` writes it into
 *     `files/staging`, and after the commit the process is killed by the system — there is nobody
 *     who can ever delete it. Measured: 28.2 MB sitting there for a day, in a private folder no
 *     file manager can open. The right moment to notice is precisely this one, the next startup.
 *  7. `pruneHistory()` — the download rows survive an installation since M5/7, so the table feeding
 *     the Downloads screen's history is the one thing here that has no bound of its own. Each
 *     settled download trims itself; what only this pass catches is the ceiling having been
 *     **lowered** in Settings, which otherwise would take effect only at the next download.
 *
 * At the end of the same sequence, and not repairs: `refreshIfStale()` downloads `parsers.json` and
 * `index.json` if the cached copy is older than six hours. They are there because they need the
 * same scope and the same guarantee — once per launch, off the main thread.
 *
 * **The two documents do not behave the same way, though, and that is worth knowing here.**
 * `parsers.json` does not apply to this launch: the adapters built at step 1 already received their
 * configuration, and applying it now would mean interpreting with one configuration a page
 * downloaded with another. `index.json`, instead, applies immediately: it is content, not
 * configuration, and the Home observes it — if it only appeared after a restart, first launch would
 * stay the empty screen it exists to fill.
 *
 * They run in sequence and on the process scope, not an Activity's: the very reason they exist is
 * that no Activity is guaranteed.
 *
 * **The last three are not repairs**, and are not in the same sequence.
 *
 * `UpdateScheduling.start()` repairs nothing: it makes the update-check scheduling observe the
 * settings, for the life of the process.
 *
 * Reconciliation, on the other hand, is not enough done once. A package can change while the app is
 * running — another store, a sideload, `adb install` — and from then on "My apps" would announce a
 * version no longer on the phone, until the next launch. The signal already exists and is the
 * system's: [PackageEvents]. Observing it costs one idle coroutine, and the receiver is shared with
 * whoever already observes it. [UpdateNotice] is hooked to the same event, for the same reason
 * applied to the notification.
 *
 * [AutoInstallCoordinator] is the third, and its reason for being here is the sharpest of the
 * three: it has to see the **first** list of transfers this process produces. What is already
 * finished at that instant is a leftover from an earlier session and must never propose anything on
 * its own; everything that reaches that state afterwards happened while the user was here. Started
 * from an Activity it would see a first list that depends on which screen was opened.
 *
 * All three live here because the right scope is already here, and because this is the only point
 * that runs exactly once per launch regardless of which screen the user opens.
 */
@Singleton
class AppStartup @Inject constructor(
    private val storeHealth: StoreHealthRepository,
    private val downloads: DownloadRepository,
    private val installedApps: InstalledAppsRepository,
    private val installs: InstallRepository,
    private val remoteConfig: RemoteConfigRepository,
    private val remoteIndex: RemoteIndexRepository,
    private val maintenance: MaintenanceRepository,
    private val packageEvents: PackageEvents,
    private val updateNotice: UpdateNotice,
    private val updateScheduling: UpdateScheduling,
    private val autoInstall: AutoInstallCoordinator,
    @param:ApplicationScope private val scope: CoroutineScope,
) {

    fun run() {
        // Outside the repairs' `launch`: scheduling **observes** the settings and never finishes,
        // so putting it in that sequence would block everything after it. It has its own scope, and
        // it is the same one.
        updateScheduling.start()

        // Third of the things that are not repairs, and the reason it starts **here** rather than
        // in an Activity is the same as for the other two: it has to be watching before anything
        // happens. It also has to be watching from the first emission — that first list is what
        // tells apart a download that finished under the user's eyes from one left over from a
        // previous session, and only the second must never produce a dialog by itself.
        autoInstall.start()

        // This one too is outside the sequence, and for the same reason: it never finishes.
        scope.launch {
            packageEvents.changes.collect {
                runCatching { installedApps.reconcile() }
                // In the same place and for the same reason: a package changing is the only moment
                // when the update list can shrink without the periodic check being involved, and a
                // notification listing an app that has just been updated contradicts the screen the
                // user has just come from.
                runCatching { updateNotice.refresh() }
            }
        }

        scope.launch {
            // Each is independent of the others: a fault in reconciling packages must not prevent
            // requeueing downloads. Hence `runCatching`, and not to hide the error — which stays in
            // the respective repository's log.
            runCatching { storeHealth.registerKnownStores() }
            runCatching { downloads.requeueInterrupted() }
            runCatching { installedApps.reconcile() }
            runCatching { installs.reconcileAbandonedSessions() }
            runCatching { storeHealth.pruneOldEvents() }
            runCatching { maintenance.purgeStale() }
            // The history's ceiling, applied among the repairs and for the same reason they are
            // repairs: the rows survive an installation since M5/7, so an unbounded table is what
            // the process leaves behind. Each settled download also trims on its own, which covers
            // growth; what only this pass covers is the ceiling being **lowered** — from 500 to 50 —
            // where nothing would otherwise apply the new value until the next download.
            //
            // After `purgeStale` and not before: a pruned row can still own a file that
            // `keep_apk_after_install` kept, and that file becomes an orphan for the **next**
            // launch's sweep. Trimming first would leave it for a launch later still.
            runCatching { downloads.pruneHistory() }

            // Not a repair, and last on purpose: **what it downloads does not apply to this
            // launch**. The adapters built by `registerKnownStores()` a few lines above already
            // received their configuration; this prepares the next launch. Putting it first would
            // anticipate nothing and delay five things that are needed now.
            runCatching { remoteConfig.refreshIfStale() }

            // The index, by contrast, **does apply to this launch**, and that is the asymmetry
            // between the two documents: the selectors have already reached the adapters and
            // changing them now would mean interpreting with one configuration a page downloaded
            // with another, whereas the index is content the Home observes and redraws. It stays at
            // the end anyway: it is the only one of the seven things not needed before the user
            // touches something.
            runCatching { remoteIndex.refreshIfStale() }
        }
    }
}

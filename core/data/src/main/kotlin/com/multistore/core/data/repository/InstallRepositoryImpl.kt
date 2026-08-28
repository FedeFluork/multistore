package com.multistore.core.data.repository

import android.os.Build
import com.multistore.core.installer.InstallProgress
import com.multistore.core.installer.InstallRequest
import com.multistore.core.installer.InstallerSelector
import com.multistore.core.installer.StagedApk
import com.multistore.core.installer.container.ContainerContents
import com.multistore.core.installer.container.ContainerExtractor
import com.multistore.core.installer.container.ContainerReadResult
import com.multistore.core.installer.container.ContainerReader
import com.multistore.core.installer.container.ExpansionResult
import com.multistore.core.installer.container.ExtractionResult
import com.multistore.core.installer.container.SplitSelection
import com.multistore.core.installer.UninstallProgress
import com.multistore.core.installer.session.InstallSessionReconciler
import com.multistore.core.installer.verify.PreInstallVerifier
import com.multistore.core.model.DeviceProfile
import com.multistore.core.model.InstallerAvailability
import com.multistore.core.model.InstallerKind
import com.multistore.core.model.SecuritySettings
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

@Singleton
internal class InstallRepositoryImpl @Inject constructor(
    private val verifier: PreInstallVerifier,
    private val selector: InstallerSelector,
    private val installedApps: InstalledAppsRepository,
    private val settings: SettingsRepository,
    private val sessions: InstallSessionReconciler,
    private val containers: ContainerReader,
    private val extractor: ContainerExtractor,
    private val device: DeviceProfile,
) : InstallRepository {

    override suspend fun reconcileAbandonedSessions(): Int = sessions.abandonOrphans().sessions

    override suspend fun installerAvailability(): InstallerAvailability = selector.availability()

    override suspend fun requestInstallerPermission(kind: InstallerKind): Boolean =
        selector.requestPermission(kind)

    /**
     * Verifies and installs, **opening the file first if it is a container**.
     *
     * The order is that one and no other, and every step depends on the previous:
     *
     *  1. we look **inside** the delivered file. The extension does not decide: the XAPK apkcombo
     *     delivers is R2's `.apks` object renamed `.xapk` by the `content-disposition`, and the
     *     content is an XAPK. Three declarations, two named formats;
     *  2. we choose what to install **of that container on this device**: not everything, because a
     *     container carries every architecture and every density together;
     *  3. we extract, with the digest computed as the bytes come out;
     *  4. we verify: the seven steps on the base, plus the eighth demanding that every split belongs
     *     to the same app;
     *  5. we install **everything in the same session**, because base and splits separately are two
     *     installations and the second is refused;
     *  6. we place the game data, if there is any and if there is somebody who can.
     *
     * The normal case — an APK — crosses this function as before: step 1 says `SingleApk` and steps
     * 2, 3 and 6 do nothing.
     */
    override fun install(plan: InstallPlan): Flow<InstallStep> = flow {
        emit(InstallStep.Verifying)

        val opened = when (val contents = containers.read(plan.apk)) {
            is ContainerReadResult.Unreadable -> {
                emit(InstallStep.ContainerRejected(ContainerProblem.Unreadable(contents.reason)))
                return@flow
            }

            is ContainerReadResult.Read -> when (val c = contents.contents) {
                ContainerContents.SingleApk -> null
                is ContainerContents.Bundle -> when (val chosen = SplitSelection.select(c, device)) {
                    is SplitSelection.Selection.Incompatible -> {
                        emit(
                            InstallStep.ContainerRejected(
                                ContainerProblem.IncompatibleAbi(chosen.available),
                            ),
                        )
                        return@flow
                    }

                    is SplitSelection.Selection.Install -> {
                        emit(InstallStep.Unpacking(chosen.summary))
                        // Game data cannot be written without a privileged shell, and that is known
                        // **before** extracting nine hundred megabytes: stopping here costs a check,
                        // stopping afterwards costs the disk.
                        if (chosen.summary.hasExpansions && selector.expansionWriter() == null) {
                            emit(
                                InstallStep.ContainerRejected(
                                    ContainerProblem.ExpansionsNeedPrivilegedInstaller,
                                ),
                            )
                            return@flow
                        }
                        when (
                            val result =
                                extractor.extract(plan.apk, chosen.summary, Staging.splitsOf(plan.apk))
                        ) {
                            is ExtractionResult.Failed -> {
                                emit(
                                    InstallStep.ContainerRejected(
                                        ContainerProblem.Unreadable(result.reason),
                                    ),
                                )
                                return@flow
                            }

                            is ExtractionResult.NotEnoughSpace -> {
                                emit(
                                    InstallStep.ContainerRejected(
                                        ContainerProblem.NotEnoughSpace(result.needBytes, result.freeBytes),
                                    ),
                                )
                                return@flow
                            }

                            is ExtractionResult.Done -> result.bundle
                        }
                    }
                }
            }
        }

        val security: SecuritySettings = settings.security.first()
        // What is on the device **now**, read from the PackageManager: our table says only what was
        // there when we wrote it, and between then and now the user may have updated the app
        // elsewhere.
        val installed = plan.declaredPackageName?.let { installedApps.installedPackage(it) }

        val payload = opened?.let { bundle ->
            PreInstallVerifier.Payload(
                delivered = plan.apk,
                base = bundle.base.file,
                splits = bundle.apks.filter { it != bundle.base }.map { it.file },
            )
        } ?: PreInstallVerifier.Payload.of(plan.apk)

        val verification = verifier.verify(
            payload = payload,
            expectation = PreInstallVerifier.Expectation(
                declaredPackageName = plan.declaredPackageName,
                expectedSha256 = plan.expectedSha256,
                expectedSizeBytes = plan.expectedSizeBytes,
                expectedSignerSha256 = plan.expectedSignerSha256,
                installed = installed,
                deviceSdkInt = Build.VERSION.SDK_INT,
                allowDowngrade = plan.allowDowngrade,
                allowHashMismatch = security.allowUnverifiedHash,
                allowSignerMismatch = security.allowSignerMismatch,
            ),
        )
        val ok = verification as? PreInstallVerifier.VerificationOutcome.Ok
            ?: run {
                emit(InstallStep.Rejected(verification))
                return@flow
            }

        emit(InstallStep.Verified(ok))

        // The user's preference is read **here**, in one place only. The plan can carry one of its
        // own — nobody does today — and that one wins: it is the choice made for this installation,
        // not the general one.
        val preferred = plan.preferredInstaller ?: settings.installation.first().preference.kind
        val installer = if (plan.requireSilent) {
            selector.selectSilent(preferred) ?: run {
                emit(InstallStep.SilentUnavailable)
                return@flow
            }
        } else {
            selector.select(preferred)
        }
        // The digests are the ones computed **at extraction**, not recomputed now: the TOCTOU window
        // to close is precisely the one between the moment the file was written to disk and the moment
        // it enters the session, and recomputing them here would leave it open. The entry's name
        // **inside the session** is the extracted file's, not the zip entry's: the second can contain
        // a directory (`apks/base.apk`), and `openWrite` does not accept a path. The first has already
        // been cleaned by the extractor, which is also the only one that guaranteed it is unique.
        val apks = opened?.apks?.map { StagedApk(it.file.name, it.file, it.sha256) }
            ?: listOf(StagedApk(plan.apk.name, plan.apk, ok.info.fileSha256))

        installer.install(
            InstallRequest(
                packageName = ok.info.packageName,
                apks = apks,
                label = plan.label,
            ),
        ).collect { progress ->
            when (progress) {
                InstallProgress.Preparing -> Unit
                is InstallProgress.Writing -> emit(InstallStep.Writing(progress.bytesWritten, progress.bytesTotal))
                InstallProgress.Committing -> emit(InstallStep.Committing)
                is InstallProgress.UserActionRequired -> emit(InstallStep.UserActionRequired(progress.intent))
                InstallProgress.Installed -> {
                    // The game data **after** installation, and not before: if the installation fails
                    // there is no point in having written nine hundred megabytes, and the
                    // `Android/obb/<package>` directory can perfectly well be created for a package
                    // that does not exist yet — measured.
                    val expansions = opened?.expansions.orEmpty()
                    if (expansions.isNotEmpty()) {
                        emit(
                            InstallStep.PlacingExpansions(
                                files = expansions.size,
                                bytes = expansions.sumOf { it.file.length() },
                            ),
                        )
                        val writer = selector.expansionWriter()
                        val placed = writer?.place(ok.info.packageName, expansions)
                            ?: ExpansionResult.Failed(NO_EXPANSION_WRITER)
                        if (placed is ExpansionResult.Failed) {
                            emit(
                                InstallStep.ContainerRejected(
                                    ContainerProblem.ExpansionFailed(placed.reason),
                                ),
                            )
                            return@collect
                        }
                    }

                    // With no store nothing is recorded: see the note on `InstallPlan.storeId`. The
                    // only plan with no store is MultiStore's own update, and a row in `installed_apps`
                    // for us would be an invented provenance.
                    val storeId = plan.storeId
                    val ref = plan.ref
                    if (storeId != null && ref != null) {
                        installedApps.record(
                            packageName = ok.info.packageName,
                            label = plan.label,
                            storeId = storeId,
                            ref = ref,
                            listingId = plan.listingId,
                            apkSha256 = ok.info.fileSha256,
                            installerKind = installer.kind,
                        )
                    }
                    emit(InstallStep.Installed(ok.info.packageName, ok.info.versionCode))
                }

                is InstallProgress.Failed -> emit(InstallStep.Failed(progress.statusCode, progress.message))
                InstallProgress.Cancelled -> emit(InstallStep.Cancelled)
            }
        }

        // The extracted pieces are **derived data**: the container they come from is still there, and
        // reopening it costs less than keeping a second copy — on Firefox that is 250 MB. They are
        // thrown away whatever the outcome, because at this point they are either in the session or no
        // longer needed. If the flow were cancelled earlier, the startup sweep finds them.
        if (opened != null) Staging.splitsOf(plan.apk).deleteRecursively()
    }

    override fun uninstall(packageName: String): Flow<InstallStep> = flow {
        // Uninstalling goes through the preference too: with root or Shizuku the system confirmation
        // does not appear, and that is what the user asked for by choosing them. No `requireSilent`
        // here — an uninstall is always requested by somebody who is watching.
        val installer = selector.select(settings.installation.first().preference.kind)
        emitAll(
            installer.uninstall(packageName).map { progress ->
                when (progress) {
                    UninstallProgress.InProgress -> InstallStep.Committing
                    is UninstallProgress.UserActionRequired -> InstallStep.UserActionRequired(progress.intent)
                    UninstallProgress.Uninstalled -> {
                        // The row leaves only once the system has confirmed the uninstall: removing it
                        // beforehand would leave "My apps" and the device disagreeing if the user
                        // cancels the confirmation.
                        installedApps.forget(packageName)
                        InstallStep.Uninstalled(packageName)
                    }

                    is UninstallProgress.Failed -> InstallStep.Failed(progress.statusCode, progress.message)
                }
            },
        )
    }

    private companion object {
        const val NO_EXPANSION_WRITER = "no privileged channel for expansion data"
    }
}

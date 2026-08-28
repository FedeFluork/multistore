package com.multistore.core.domain.usecase

import com.multistore.core.common.result.AppError
import com.multistore.core.common.result.Outcome
import com.multistore.core.data.repository.AppDetail
import com.multistore.core.data.repository.AppDetailRepository
import com.multistore.core.data.repository.DownloadRepository
import com.multistore.core.data.repository.DownloadStatus
import com.multistore.core.data.repository.InstallPlan
import com.multistore.core.data.repository.InstallRepository
import com.multistore.core.data.repository.InstallStep
import com.multistore.core.data.repository.SettingsRepository
import com.multistore.core.model.AppVersion
import com.multistore.core.model.DownloadState
import com.multistore.core.model.InstallerKind
import com.multistore.core.model.Sha256
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.model.VersionRef
import com.multistore.store.api.DownloadHint
import com.multistore.store.api.DownloadResolution
import javax.inject.Inject
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** The visible stages of the whole path, from resolving the link to the installed app. */
sealed interface InstallProgressStep {

    data object Resolving : InstallProgressStep

    /**
     * [downloadId] is not an internal detail slipping out: it is what lets the UI **cancel**. Since
     * the transfer lives in a worker, interrupting the flow no longer stops it — and must not,
     * otherwise leaving a screen would throw the download away — so cancelling has to be an explicit
     * gesture, and an explicit gesture needs to know what to cancel.
     */
    data class Downloading(
        val downloadId: Long,
        val bytesDownloaded: Long,
        val bytesTotal: Long?,
    ) : InstallProgressStep

    /**
     * The file can only be obtained with a human gesture on the store's page.
     *
     * It carries everything needed to carry on — which page, what the user has to do, and **which
     * version** — because the path resumes in another screen (`:feature:webviewdownload`) and that
     * screen cannot derive them by itself without redoing the resolution, i.e. without asking the
     * store again.
     */
    data class UserAssistedDownload(
        val pageUrl: String,
        val hint: DownloadHint,
        val versionRef: VersionRef,
    ) : InstallProgressStep

    /**
     * The file is there and whole, and nobody will install it now.
     *
     * Only the unattended path with [Unattended.downloadOnly] emits it: it is the successful outcome
     * of "download by itself, then I will deal with it". A step of its own is needed because without
     * it, "downloaded and stopped here" and "the flow ended because something went wrong" would reach
     * the listener in the same way, i.e. as the end of the flow.
     */
    data class Downloaded(val downloadId: Long) : InstallProgressStep

    data class Install(val step: InstallStep) : InstallProgressStep

    data class Failed(val error: AppError) : InstallProgressStep

    /** The signature does not match the installed one: the only way is to uninstall and reinstall. */
    data class SignerConflict(val available: List<AppVersion>) : InstallProgressStep

    data object Incompatible : InstallProgressStep
}

/**
 * That nobody asked for this path **now**.
 *
 * `null` means "the user has just pressed something", and it is the normal case. When it is present,
 * what started it was the periodic check, and from there three consequences follow that it would be
 * wrong to leave to the caller:
 *
 *  - the transfer **waits** for a non-metered network, if the user has not allowed one: whoever has
 *    just pressed has already decided to spend that traffic, whoever is asleep has not;
 *  - the installation, if it happens, has to be **silent**. With only the system confirmation there
 *    would be nobody to see it — and from API 34 it would not even appear;
 *  - with [downloadOnly] we stop before installing, leaving the file ready on the listing.
 *
 * It is a type and not three booleans because the three values are not independent: "unattended" is
 * a single fact, and the three consequences are its reading.
 */
data class Unattended(
    /** Wait for an unmetered network before transferring. */
    val requireUnmetered: Boolean,
    /** Stop once the file is downloaded: the user will press "Install". */
    val downloadOnly: Boolean,
)

/**
 * The critical path, in a single function: resolve, download, verify, install, record.
 *
 * It stays together and not in pieces because the pieces are tied by constraints neither side can
 * enforce alone. The verified file and the installed file must be **the same**; the download must be
 * thrown away only after the installation has succeeded; and what is recorded in "My apps" must come
 * from the APK that was read, not from what the listing promised. Distributing these three
 * constraints across different callers means that sooner or later one ignores them.
 */
class InstallAppUseCase @Inject constructor(
    private val resolve: ResolveDownloadUseCase,
    private val downloads: DownloadRepository,
    private val installs: InstallRepository,
    private val details: AppDetailRepository,
    private val settings: SettingsRepository,
) {

    /**
     * Stops a transfer in progress, at the user's request.
     *
     * It lives here and not in the ViewModel because cancelling a download is the exact counterpart of
     * starting it, and the two have to stay in the same place: since the worker exists, interrupting
     * [invoke]'s flow no longer stops anything, and whoever discovered that late would leave orphan
     * downloads going on consuming network after the user said no.
     */
    suspend fun cancelDownload(downloadId: Long) = downloads.cancel(downloadId)

    /**
     * The download in progress for this app, started by anybody.
     *
     * It serves a screen reopening halfway through a transfer. Before the worker the question made no
     * sense — the download lived in the scope of whoever started it, so either that screen was there
     * or the download was not. Now the two survive separately, and without this observation the
     * listing would show "Install" above a download in progress, with the system notification saying
     * the opposite.
     */
    fun observeDownload(storeId: StoreId, ref: StoreAppRef): Flow<DownloadStatus?> =
        downloads.observeFor(storeId, ref)

    operator fun invoke(
        storeId: StoreId,
        ref: StoreAppRef,
        explicitVersion: AppVersion? = null,
        allowDowngrade: Boolean = false,
        preferredInstaller: InstallerKind? = null,
        unattended: Unattended? = null,
    ): Flow<InstallProgressStep> = channelFlow {
        send(InstallProgressStep.Resolving)

        val detail = details.detail(storeId, ref)
            ?: run {
                send(InstallProgressStep.Failed(AppError.NotFound))
                return@channelFlow
            }

        val resolved = when (val outcome = resolve(storeId, ref, explicitVersion)) {
            is ResolvedDownload.Direct -> outcome
            is ResolvedDownload.UserAssisted -> {
                // The assisted path carries on in `:feature:webviewdownload`, with the real page and the
                // user's real tap. From there it re-enters this same flow at verification, through
                // [resume]: no store has a privileged installation path.
                send(
                    InstallProgressStep.UserAssistedDownload(
                        pageUrl = outcome.pageUrl,
                        hint = outcome.hint,
                        versionRef = outcome.version.ref,
                    ),
                )
                return@channelFlow
            }

            is ResolvedDownload.SignerConflict -> {
                send(InstallProgressStep.SignerConflict(outcome.available))
                return@channelFlow
            }

            ResolvedDownload.Incompatible -> {
                send(InstallProgressStep.Incompatible)
                return@channelFlow
            }

            is ResolvedDownload.Unavailable -> {
                send(InstallProgressStep.Failed(outcome.error))
                return@channelFlow
            }
        }

        val downloadId = downloads.enqueue(
            storeId = storeId,
            ref = ref,
            versionRef = resolved.version.ref,
            packageName = detail.listing.summary.packageName,
            listingId = null,
            resolution = resolved.resolution,
        )

        downloadAndInstall(
            storeId = storeId,
            ref = ref,
            detail = detail,
            downloadId = downloadId,
            expectedSha256 = resolved.resolution.expectedSha256,
            expectedSize = resolved.resolution.expectedSize,
            // The signer the store recommends: without it, the **first** installation — the only one
            // establishing which signature chain we tie ourselves to — would be the only unverified
            // one.
            expectedSignerSha256 = resolved.version.signerSha256
                ?: detail.listing.preferredSignerSha256,
            allowDowngrade = allowDowngrade,
            preferredInstaller = preferredInstaller,
            unattended = unattended,
        )
    }

    /**
     * Queues and starts a download **already resolved by somebody else**.
     *
     * "Somebody else" is `:feature:webviewdownload`: on the store's real page the user performed the
     * gesture the store asks for, and the WebView intercepted the resulting URL together with the
     * cookies that make it valid. The file coming out has **no** privileged path: it goes back into
     * the queue like any other, and from [resume] it goes through the same verification pipeline.
     */
    suspend fun enqueueAssisted(
        storeId: StoreId,
        ref: StoreAppRef,
        versionRef: VersionRef,
        packageName: String?,
        resolution: DownloadResolution.Direct,
    ): Long {
        val id = downloads.enqueue(
            storeId = storeId,
            ref = ref,
            versionRef = versionRef,
            packageName = packageName,
            listingId = null,
            resolution = resolution,
        )
        // `requireUnmetered = false`: we arrive here from the store's page, where the user has just
        // performed the gesture the store asks for. It does not get more attended than that.
        downloads.start(id, requireUnmetered = false)
        return id
    }

    /**
     * Resumes from a download already queued, skipping resolution.
     *
     * It serves two cases that look different and are the same: the return from the assisted path,
     * where the URL was obtained by the WebView and going through `resolve` again would re-ask the
     * store; and the already downloaded file nobody installed — because the user cancelled at the
     * system confirmation, or because they left the app. Without this route, that file would stay in
     * staging forever and pressing "Install" would re-download it.
     */
    fun resume(
        storeId: StoreId,
        ref: StoreAppRef,
        downloadId: Long,
        allowDowngrade: Boolean = false,
        preferredInstaller: InstallerKind? = null,
    ): Flow<InstallProgressStep> = channelFlow {
        val status = downloads.get(downloadId)
            ?: run {
                send(InstallProgressStep.Failed(AppError.NotFound))
                return@channelFlow
            }
        val detail = details.detail(storeId, ref)
            ?: run {
                send(InstallProgressStep.Failed(AppError.NotFound))
                return@channelFlow
            }

        // The version that row was downloading, not the one the rule would choose now: between the
        // download and the installation the store may have published more, and the file on disk stays
        // the previous one. Taking the "current" version would give a signature and size check made
        // against a different file's metadata.
        val version = detail.listing.versions.firstOrNull { it.ref == status.versionRef }

        downloadAndInstall(
            storeId = storeId,
            ref = ref,
            detail = detail,
            downloadId = downloadId,
            expectedSha256 = downloads.expectedHash(downloadId) ?: version?.sha256,
            // **Only `bytesTotal`, not `version.sizeBytes`.** The two numbers look the same and are
            // not: the first is what the download's resolution declared as exact — or what the server
            // answered in the `Content-Length` — the second is a value to show on screen, which a
            // store may round. apkcombo rounds it to the megabyte, and using it as an expectation made
            // a complete file be declared incomplete.
            expectedSize = status.bytesTotal,
            expectedSignerSha256 = version?.signerSha256 ?: detail.listing.preferredSignerSha256,
            allowDowngrade = allowDowngrade,
            preferredInstaller = preferredInstaller,
            // No `unattended`: [resume] is always reached from a tap — the return from the WebView or
            // the button on a file already in staging. The periodic check does not come through here,
            // because `enqueue` gives it back by itself the partial download already open on the same
            // version.
            unattended = null,
        )
    }

    /**
     * Wait for the file and install it: the half of the path that does not depend on how the URL was
     * reached.
     *
     * It lives in one place because the three constraints governing it have to hold for **every**
     * store, including the one arrived from a WebView: the verified file and the installed file are
     * the same, the download is thrown away only on a successful installation, and what is recorded in
     * "My apps" comes from the APK that was read.
     */
    private suspend fun ProducerScope<InstallProgressStep>.downloadAndInstall(
        storeId: StoreId,
        ref: StoreAppRef,
        detail: AppDetail,
        downloadId: Long,
        expectedSha256: Sha256?,
        expectedSize: Long?,
        expectedSignerSha256: Sha256?,
        allowDowngrade: Boolean,
        preferredInstaller: InstallerKind?,
        unattended: Unattended?,
    ) {
        send(InstallProgressStep.Downloading(downloadId, 0, expectedSize))

        // `run` suspends until the end of the transfer, so progress has to be **observed** from
        // elsewhere: without this, the bar stays at 0 B for the whole duration of an 18 MB file, and on
        // a slow network the user concludes it has hung. It is also why this flow is a `channelFlow`
        // and not a `flow`: `emit` cannot be called from a child coroutine, `send` can.
        val progress = launch {
            downloads.observe(downloadId).collect { status ->
                if (status != null && status.state == DownloadState.RUNNING) {
                    send(
                        InstallProgressStep.Downloading(
                            downloadId = downloadId,
                            bytesDownloaded = status.bytesDownloaded,
                            bytesTotal = status.bytesTotal,
                        ),
                    )
                }
            }
        }
        // The transfer goes through the worker, not through here. The difference shows when the user
        // leaves the listing: this flow is cancelled, the worker is not, and on returning the file is
        // ready instead of having to start again. What is left to this use case is **waiting**, and
        // what is waited on is the state in Room — the same source that feeds the notification.
        downloads.start(downloadId, requireUnmetered = unattended?.requireUnmetered == true)
        val outcome = try {
            downloads.awaitCompletion(downloadId)
        } finally {
            // `cancelAndJoin` and not `cancel`: a progress update already in flight would arrive
            // **after** the next step and would take the UI back to "downloading" once it was done.
            progress.cancelAndJoin()
        }

        val file = when (outcome) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> {
                send(InstallProgressStep.Failed(outcome.error))
                return
            }
        }

        // "Download by itself" ends here: the file is whole and verified by the download, and it will
        // be the user on the listing who presses "Install". The download is **not** thrown away —
        // `discard` only comes after a successful installation — so that button will re-download
        // nothing.
        if (unattended?.downloadOnly == true) {
            send(InstallProgressStep.Downloaded(downloadId))
            return
        }

        var installed = false
        installs.install(
            InstallPlan(
                apk = file,
                storeId = storeId,
                ref = ref,
                label = detail.listing.summary.title,
                declaredPackageName = detail.listing.summary.packageName,
                expectedSha256 = expectedSha256,
                expectedSizeBytes = expectedSize,
                expectedSignerSha256 = expectedSignerSha256,
                allowDowngrade = allowDowngrade,
                preferredInstaller = preferredInstaller,
                // The constraint travels inside the plan and does not stay here: between this line and
                // the installer's selection there is a whole verification, and in between Shizuku may
                // have stopped.
                requireSilent = unattended != null,
            ),
        ).collect { step ->
            if (step is InstallStep.Installed) installed = true
            send(InstallProgressStep.Install(step))
        }

        // The staged file is thrown away **only** on a successful installation. Deleting it earlier
        // would mean, after a cancellation at the confirmation screen, re-downloading from scratch
        // tens of megabytes that were already there.
        //
        // "Throwing away" is no longer the only answer, and it is field 17. The default stays this one
        // — with the switch off it is deleted, i.e. what the app has always done — and the setting is
        // read **here** and not in the caller, for the same reason `show_nsfw_content` is read in
        // `SearchRepositoryImpl` and `block_user_assisted_challenge` in `ResolveDownloadUseCase`: a
        // setting passed as a parameter is a setting somebody eventually forgets to pass, and
        // forgetting produces no error — only a private directory that grows.
        if (installed) {
            if (settings.storage.first().keepApkAfterInstall) {
                downloads.retire(downloadId)
            } else {
                downloads.discard(downloadId)
            }
        }
    }
}

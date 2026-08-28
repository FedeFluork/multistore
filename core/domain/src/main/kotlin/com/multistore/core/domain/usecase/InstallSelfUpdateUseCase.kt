package com.multistore.core.domain.usecase

import android.content.Context
import com.multistore.core.common.result.AppError
import com.multistore.core.data.repository.InstallPlan
import com.multistore.core.data.repository.InstallRepository
import com.multistore.core.data.repository.InstallStep
import com.multistore.core.data.repository.SelfUpdateOffer
import com.multistore.core.data.repository.Staging
import com.multistore.core.model.Sha256
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import com.multistore.core.remoteconfig.SelfUpdateSource

/** The stages of a MultiStore self-update. */
sealed interface SelfUpdateStep {

    data class Downloading(val bytesDownloaded: Long, val bytesTotal: Long?) : SelfUpdateStep

    data class Install(val step: InstallStep) : SelfUpdateStep

    data class Failed(val error: AppError) : SelfUpdateStep
}

/**
 * Downloads and installs MultiStore's new version.
 *
 * ### The three things this path does **not** do differently from the others
 *
 * 1. **Verification is the same.** The APK goes to `InstallRepository.install`, so it goes through
 *    `PreInstallVerifier`'s seven steps. The fourth — the `packageName` match — compares against
 *    **our** name, which here is the one thing the index cannot declare differently without being
 *    refused. The fifth compares the signer with the **installed** one: an index signed with our
 *    Ed25519 key can declare an address, it cannot make a package signed by somebody else valid.
 * 2. **The file lives in `filesDir`**, like every other staged APK, and not in `cacheDir`: the system
 *    can empty the cache precisely between verification and commit.
 * 3. **No silent installation.** `requireSilent` stays `false` and no preferred installer is passed:
 *    updating oneself kills the process in the middle of the commit, and doing that without the user
 *    having just pressed a button would mean an app disappearing from under their fingers. It is the
 *    same reason "update everything" puts MultiStore last.
 */
class InstallSelfUpdateUseCase @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val downloader: SelfUpdateSource,
    private val install: InstallRepository,
) {

    operator fun invoke(offer: SelfUpdateOffer): Flow<SelfUpdateStep> = channelFlow {
        val destination = File(Staging.dir(context), STAGED_NAME)

        send(SelfUpdateStep.Downloading(0, offer.release.size))
        // `trySend` and not `send`: progress comes from a **synchronous** lambda, called by the loop
        // reading the bytes. Suspending there would stop the transfer at every buffer, and a lost
        // progress update is only a bar skipping a frame.
        val outcome = downloader.download(offer.release, destination) { received, total ->
            trySend(SelfUpdateStep.Downloading(received, total))
        }

        when (outcome) {
            is SelfUpdateSource.Outcome.Failed ->
                send(SelfUpdateStep.Failed(AppError.Network(outcome.cause)))

            // A hash mismatch is **not** negotiable here, and does not look at
            // `allow_unverified_hash`: that switch exists because among the nine stores there are some
            // publishing stale hashes next to legitimate files. We publish the index, and if the file
            // is not the one we declared there is no benevolent hypothesis to make.
            is SelfUpdateSource.Outcome.Mismatch ->
                send(
                    SelfUpdateStep.Failed(
                        AppError.IntegrityFailed("sha256 ${outcome.expected.take(HASH_CHARS)}…"),
                    ),
                )

            is SelfUpdateSource.Outcome.Success -> {
                val plan = InstallPlan(
                    apk = outcome.file,
                    storeId = null,
                    ref = null,
                    label = offer.release.versionName,
                    declaredPackageName = context.packageName,
                    expectedSha256 = Sha256.parseOrNull(outcome.sha256),
                    expectedSizeBytes = offer.release.size,
                    // No declared signer: the one to compare against is our **installed** one, which
                    // `PreInstallVerifier` reads from the `PackageManager`. A signature declared by the
                    // index would add nothing — the document and the expected value would come from the
                    // same hand.
                    expectedSignerSha256 = null,
                )
                install.install(plan).collect { step -> send(SelfUpdateStep.Install(step)) }
            }
        }
    }

    private companion object {
        /**
         * The file's name, and **not** the directory: that is known by [Staging], in `:core:data`.
         *
         * This class used to keep its own copy of `"staging"` too, and the copy was the problem: that
         * directory is **cleaned** by somebody else, at startup, and it is the only way this APK can
         * ever disappear — after the commit the process is killed, so there is nobody here who could
         * delete it. Two strings in two modules would be two strings that can diverge, and the symptom
         * would be a file nobody cleans: invisible by definition.
         */
        const val STAGED_NAME = "multistore-update.apk"

        /** How much of the expected hash goes into the message: enough to recognise it in a log. */
        const val HASH_CHARS = 12
    }
}

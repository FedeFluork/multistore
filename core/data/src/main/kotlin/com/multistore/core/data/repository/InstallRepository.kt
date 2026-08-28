package com.multistore.core.data.repository

import android.content.Intent
import com.multistore.core.installer.verify.PreInstallVerifier
import com.multistore.core.model.InstallerAvailability
import com.multistore.core.model.InstallerKind
import com.multistore.core.model.Sha256
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import java.io.File
import kotlinx.coroutines.flow.Flow

/**
 * What is about to be installed, and what the store had promised.
 *
 * The store's promises — [declaredPackageName], [expectedSha256], [expectedSignerSha256] — are
 * deliberately separate from the file: verification compares the two, and mixing them into a single
 * object would make it possible to build it from the file itself, i.e. to verify it against itself.
 */
data class InstallPlan(
    val apk: File,
    /**
     * Which store the file comes from, and `null` when it does not come from a store.
     *
     * The only case is MultiStore's own update, and the consequence is precise: with no store no row
     * is written in `installed_apps`. It is not a simplification — it is the only right answer. That
     * table says "MultiStore installed this app from this store, and will update it from there";
     * writing into it would mean an update channel pointing at a listing that does not exist, and
     * MultiStore appearing in "My apps" as though it had been taken from a catalogue.
     */
    val storeId: StoreId?,
    val ref: StoreAppRef?,
    val label: String,
    val declaredPackageName: String?,
    val expectedSha256: Sha256?,
    val expectedSizeBytes: Long?,
    val expectedSignerSha256: Sha256?,
    val listingId: Long? = null,
    /** The user has explicitly agreed to move down a version. */
    val allowDowngrade: Boolean = false,
    /**
     * The installer requested **for this installation**; if unavailable we descend the chain.
     * `null` = the one chosen in Settings.
     */
    val preferredInstaller: InstallerKind? = null,
    /**
     * Stop if there is no installer that does not ask for confirmation.
     *
     * It is set to `true` by whoever has nobody to show it to: the periodic check. It lives in the
     * plan and not only in the caller's head because it is the only way of making it true by
     * construction — whoever decides outside and then calls can, between the decision and the call,
     * see Shizuku switched off, and would end up with a confirmation screen launched from a worker.
     * From API 34 that screen does not start from the background at all, so the fault would be an
     * installation that does not happen and nobody saying so.
     */
    val requireSilent: Boolean = false,
)

/**
 * Why a split container could not be installed.
 *
 * They are five cases and not one error, and the reason is that they lead to five different things
 * to do: free space, look for another variant, install Shizuku, or nothing. A single message would
 * flatten them all onto "it did not work".
 */
sealed interface ContainerProblem {

    /** It is not a readable container, or it is a format we do not know how to open. */
    data class Unreadable(val reason: String) : ContainerProblem

    /**
     * The container carries native code and none of its architectures is the device's. [available]
     * serves the message: "this file only carries x86" explains, "incompatible" does not.
     */
    data class IncompatibleAbi(val available: List<String>) : ContainerProblem

    data class NotEnoughSpace(val needBytes: Long, val freeBytes: Long) : ContainerProblem

    /**
     * The container carries game data, and on this device there is nobody who can put it in place.
     *
     * It is not a fault: it is an Android restriction no permission overrides — see `ExpansionWriter`.
     * The installation stops **beforehand** instead of leaving an installed game that does not start.
     */
    data object ExpansionsNeedPrivilegedInstaller : ContainerProblem

    data class ExpansionFailed(val reason: String) : ContainerProblem
}

/** An installation's stages, from verification to outcome. */
sealed interface InstallStep {

    data object Verifying : InstallStep

    /**
     * The delivered file is a container, and it is being opened.
     *
     * It carries the summary because it is news for the user and not a detail: they downloaded 238 MB
     * and 180 will be installed: the difference is the architectures and densities that device will
     * never use, and without saying so it looks like space that vanished.
     */
    data class Unpacking(val summary: com.multistore.core.model.BundleSummary) : InstallStep

    /** The container could not be used, and [problem] says what can be done. */
    data class ContainerRejected(val problem: ContainerProblem) : InstallStep

    /**
     * The game data is being put in place.
     *
     * Before the copy and not after, and that is not pedantry: they are the largest files of the
     * whole operation — GTA Chinatown Wars's OBB measured on an1 is 906 MB uncompressed — and a screen
     * frozen for a minute on "installation complete" is an app that looks stuck.
     */
    data class PlacingExpansions(val files: Int, val bytes: Long) : InstallStep

    /**
     * Verification said no.
     *
     * It carries the complete outcome rather than a message: the difference between "wrong hash" and
     * "signature different from the installed one" decides what can be offered to the user — in the
     * second case, uninstalling and reinstalling with data loss is a real way out.
     */
    data class Rejected(val outcome: PreInstallVerifier.VerificationOutcome) : InstallStep

    /**
     * Verification passed, and it says **what** it was really able to verify.
     *
     * It is not a duplicate of [Verifying]: that one says it is happening, this one says what came
     * out of it. It is needed because "verified" and "not contradicted" are not the same thing —
     * where the store does not publish the packageName the comparison cannot be made (4 stores out
     * of 9), and the same where it publishes no hash — and the difference has to be told to the
     * user instead of being left inside an object nobody reads.
     */
    data class Verified(val outcome: PreInstallVerifier.VerificationOutcome.Ok) : InstallStep

    data class Writing(val bytesWritten: Long, val bytesTotal: Long) : InstallStep

    data object Committing : InstallStep

    /**
     * A user gesture is needed: the system's confirmation screen.
     *
     * The intent is returned rather than launched, because from API 34 that activity **does not
     * start from background**: the UI has to launch it when in the foreground.
     */
    data class UserActionRequired(val intent: Intent) : InstallStep

    data class Installed(val packageName: String, val versionCode: Long) : InstallStep

    /**
     * A silent installer was needed and there is none.
     *
     * A step of its own and not a [Failed]: nothing went wrong, and there is nothing to retry.
     * Verification passed, the file is in staging, and the only thing missing is a user gesture —
     * which they will indeed find as "Install" on an already completed download.
     */
    data object SilentUnavailable : InstallStep

    data class Uninstalled(val packageName: String) : InstallStep

    data class Failed(val statusCode: Int?, val message: String?) : InstallStep

    data object Cancelled : InstallStep
}

/**
 * Verifies and installs.
 *
 * The two steps sit together in a single repository because they **must** sit together: separating
 * them would let a caller install without having verified, and the pipeline is explicit that there
 * are no privileged paths.
 */
interface InstallRepository {

    fun install(plan: InstallPlan): Flow<InstallStep>

    /**
     * Which installers this device offers, and in what state.
     *
     * It lives here and not on `InstallerSelector` because the caller is the Settings screen, and a
     * `:feature:*` does not see `:core:installer`. It is not a flow: Shizuku can start or stop at any
     * moment, so the value is true at the instant it is read and observing it would give the illusion
     * of the opposite.
     */
    suspend fun installerAvailability(): InstallerAvailability

    /**
     * Asks the user for what is needed to make [kind] usable.
     *
     * **It must be called with the app in the foreground**: where there is something to ask, that
     * something is a window — Shizuku's dialog, the root manager's.
     */
    suspend fun requestInstallerPermission(kind: InstallerKind): Boolean

    fun uninstall(packageName: String): Flow<InstallStep>

    /**
     * Closes installation sessions left open by a previous process.
     *
     * It is called **once per process, at startup**, together with the other repairs: `mySessions`
     * cannot tell a live session from an orphan, so invoking it while an installation is in progress
     * would kill it. It returns how many it closed.
     */
    suspend fun reconcileAbandonedSessions(): Int
}

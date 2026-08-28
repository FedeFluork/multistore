package com.multistore.core.installer

import android.content.Intent
import com.multistore.core.model.InstallerKind
import java.io.File
import kotlinx.coroutines.flow.Flow

/**
 * Whoever knows how to install an APK.
 *
 * Three implementations foreseen — session, Shizuku, root — but only one mandatory. The app must
 * work fully with `SessionInstaller` alone: no feature may require Shizuku or root as a
 * prerequisite.
 */
interface Installer {

    val kind: InstallerKind

    /** `true` if it can install without the user confirming by hand. */
    val supportsSilent: Boolean

    suspend fun isAvailable(): Boolean

    /**
     * Asks the user for what is needed to make this installer available, and waits.
     *
     * It must be called **only with the app in the foreground**: where there is something to ask,
     * that something is a window — Shizuku's dialog, the root manager's. The default value is
     * [isAvailable] because for `SessionInstaller` there is nothing to ask: it is available and that
     * is that, and that is precisely the promise the app rests on.
     */
    suspend fun requestPermission(): Boolean = isAvailable()

    fun install(request: InstallRequest): Flow<InstallProgress>

    fun uninstall(packageName: String): Flow<UninstallProgress>

    /**
     * Who, in this channel, knows how to put game data into `Android/obb/<package>/`.
     *
     * `null` for the normal channel, and that is not a gap to be filled: on Android 11 and later that
     * directory is not writable by an app **even with `MANAGE_EXTERNAL_STORAGE`** — see the
     * measurement in [com.multistore.core.installer.container.ExpansionWriter]. Only the privileged
     * shell reaches it, so only `ShellInstaller` returns anything other than `null`.
     */
    val expansions: com.multistore.core.installer.container.ExpansionWriter? get() = null
}

/**
 * An APK ready for the session, with the digest of what is expected to be written.
 *
 * [sha256] is **not** redundant with the verification already done: it is recomputed while the bytes
 * enter the session. It is the closing of the TOCTOU window — between the moment a file is verified
 * and the moment it is installed, that file might no longer be the same, and the only way to rule
 * that out is to verify the very stream being handed over.
 *
 * It is not nullable, and cannot be: with a container the files to write are many, and a missing
 * digest on one of them would be a piece of the app installed without anyone having looked —
 * indistinguishable, from outside, from a checked one.
 *
 * [name] is the entry's name inside the session. It need not be the file's real name and need not
 * correspond to anything: `PackageInstaller` reads each APK's manifest to know which is the base and
 * which the split, and ignores what we called it. **It must be unique, though**, because two writes
 * with the same name are one overwriting the other.
 */
data class StagedApk(
    val name: String,
    val file: File,
    val sha256: com.multistore.core.model.Sha256,
)

/**
 * What to install: **one or more** APKs, in a single session.
 *
 * More than one because a split container is not installable piece by piece: a single app's base and
 * splits have to enter the **same** session, otherwise the system sees them as separate installations
 * and refuses the second. The single-element case remains the normal one, and has no different path.
 */
data class InstallRequest(
    val packageName: String,
    val apks: List<StagedApk>,
    val label: String = packageName,
) {
    val totalBytes: Long get() = apks.sumOf { it.file.length() }
}

sealed interface InstallProgress {

    data object Preparing : InstallProgress

    /** Bytes written into the session, out of how many. */
    data class Writing(val bytesWritten: Long, val bytesTotal: Long) : InstallProgress

    data object Committing : InstallProgress

    /**
     * A user gesture is needed: the system's confirmation screen.
     *
     * From API 34 this activity **does not start from background**: the intent has to be handed to
     * the UI, which launches it when in the foreground. Returning it as a state instead of launching
     * it here is what makes the difference manageable rather than a silent failure.
     */
    data class UserActionRequired(val intent: Intent) : InstallProgress

    data object Installed : InstallProgress

    data class Failed(val statusCode: Int?, val message: String?) : InstallProgress

    data object Cancelled : InstallProgress
}

sealed interface UninstallProgress {
    data object InProgress : UninstallProgress
    data class UserActionRequired(val intent: Intent) : UninstallProgress
    data object Uninstalled : UninstallProgress
    data class Failed(val statusCode: Int?, val message: String?) : UninstallProgress
}

package com.multistore.core.installer.shell

import com.multistore.core.model.InstallerKind
import java.io.OutputStream

/** What a command answered: the exit code and what it wrote. */
data class ShellResult(
    val exitCode: Int,
    /** Standard output and standard error together: `pm` writes errors to both. */
    val output: String,
) {
    val ok: Boolean get() = exitCode == 0
}

/**
 * A way of running commands with more privileges than the app's.
 *
 * The two implementations — `su` and Shizuku — differ **only** in how the process is born.
 * Everything above that (the `pm install-*` protocol, streaming the APK, computing the hash) is
 * identical, and that is why it lives in [ShellInstaller] instead of being duplicated across two
 * installers: two copies of that protocol would be two chances to get it wrong, and only one of the
 * two would be provable.
 *
 * ### Why the APK travels on standard input
 *
 * The staging file sits in `filesDir`, which is private to the app: Shizuku's `shell` user **cannot
 * read it**. Passing it a path would not work, and the obvious way out — copying the APK into a
 * world-readable directory — would mean putting a file we have just verified in the clear, for the
 * duration of the installation: anyone could replace it between verification and commit. With
 * `pm install-write … -` we write the bytes ourselves, from our own process, to the command's stdin:
 * no third party sees them and the window does not open.
 */
interface PrivilegedShell {

    val kind: InstallerKind

    /**
     * Whether this channel is usable **now**, without asking the user anything.
     *
     * It has to be cheap and silent: the Settings screen calls it too, to decide which entries to
     * show, and a screen that opens a root dialog as soon as it is opened would be unbearable. See
     * the two implementations' notes.
     */
    suspend fun isAvailable(): Boolean

    /**
     * Asks the user to grant this channel, and waits for the answer.
     *
     * It must be called only with the app in the foreground — both Shizuku's dialog and the root
     * manager's are real windows. `true` if after the request the channel is usable.
     */
    suspend fun requestPermission(): Boolean

    /**
     * Runs [command], optionally writing [stdin] to its standard input.
     *
     * [command] is a shell line, not an argv: both implementations hand it to an interpreter
     * (`su -c`, `sh -c`). Whoever builds it is responsible for what is interpolated into it — see
     * [ShellInstaller.requireShellSafe].
     */
    suspend fun exec(command: String, stdin: ((OutputStream) -> Unit)? = null): ShellResult
}

package com.multistore.core.installer.shell

import com.multistore.core.installer.InstallProgress
import com.multistore.core.installer.InstallRequest
import com.multistore.core.installer.Installer
import com.multistore.core.installer.StagedApk
import com.multistore.core.installer.container.ExpansionWriter
import com.multistore.core.installer.container.ShellExpansionWriter
import com.multistore.core.installer.UninstallProgress
import com.multistore.core.model.InstallerKind
import com.multistore.core.model.Sha256
import java.io.OutputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow

/**
 * Installs without asking for confirmation, talking to `pm` from a privileged process.
 *
 * There is only one, and it serves **both** Shizuku and root: what distinguishes them is how the
 * process is born, and [PrivilegedShell] knows that. The protocol — create a session, write the APK
 * into it, commit it — is identical, and keeping it in one place is also what makes it provable:
 * [ShellInstaller] is tested on the JVM with a fake shell, while `su` and Shizuku cannot be tested on
 * any emulator.
 *
 * ### The same TOCTOU window closed the same way
 *
 * `SessionInstaller` computes the SHA-256 **while** the bytes enter the session, and abandons it
 * before the commit if it does not match the verified one. The same holds here, and the point where
 * the digest is computed is the same one the bytes leave from: `pm install-write`'s standard input.
 * There is no "then" in which the file could change, because there is no file — there are bytes
 * leaving our process and entering the session.
 *
 * ### What it does not do
 *
 * It skips nothing of the verification pipeline. One reaches it from `InstallRepositoryImpl`, after
 * the seven steps, exactly as one reaches `SessionInstaller`: verification is identical for all nine
 * stores, with no privileged path and no exceptions.
 */
class ShellInstaller(
    private val shell: PrivilegedShell,
    /** Who will show up as the package's installer. It is ours, and has to be written: `pm` does not guess. */
    private val installerPackageName: String,
) : Installer {

    override val kind: InstallerKind get() = shell.kind

    override val supportsSilent: Boolean = true

    /** This channel has the `ext_obb_rw` group; the app does not. See [ExpansionWriter]. */
    override val expansions: ExpansionWriter = ShellExpansionWriter(shell)

    override suspend fun isAvailable(): Boolean = shell.isAvailable()

    override suspend fun requestPermission(): Boolean = shell.requestPermission()

    override fun install(request: InstallRequest): Flow<InstallProgress> = channelFlow {
        send(InstallProgress.Preparing)

        val installer = requireShellSafe(installerPackageName)
            ?: return@channelFlow send(InstallProgress.Failed(null, unsafe(installerPackageName)))

        val total = request.totalBytes

        // `-r`: it is as much an update as a first installation, and without it the second would
        // fail with INSTALL_FAILED_ALREADY_EXISTS. `-i`: without it the installer of record would be
        // `com.android.shell`, and "My apps" would say we did not install that package — i.e. exactly
        // the opposite of what happened. No `-d`: downgrading is decided by verification, not by
        // `pm`.
        //
        // No `-p` either, which reading it would look like "the package is this one": in
        // `PackageManagerShellCommand` that option puts the session into `MODE_INHERIT_EXISTING`,
        // i.e. turns it into an addition of splits to an already installed app. `pm` reads the
        // package name from the APK, and it is already the one verification compared with the
        // listing.
        val created = shell.exec("pm install-create -r -S $total -i $installer")
        val sessionId = SESSION_ID.find(created.output)?.groupValues?.get(1)?.toLongOrNull()
        if (!created.ok || sessionId == null) {
            send(InstallProgress.Failed(null, created.output.ifBlank { CREATE_FAILED }))
            return@channelFlow
        }

        // One `install-write` per APK, in the **same** session: that is how a container's base and
        // splits become one installation instead of many refused one by one. The name after the
        // session id is only the file's name inside the session — `pm` reads the manifest to know
        // which is the base — but it has to be **unique**, or the second write overwrites the
        // first.
        var alreadyWritten = 0L
        for (apk in request.apks) {
            val size = apk.file.length()
            val name = requireShellSafe(apk.name, FILE_SAFE)
                ?: return@channelFlow send(InstallProgress.Failed(null, unsafe(apk.name)))

            var digest: Sha256? = null
            val base = alreadyWritten
            val written = shell.exec("pm install-write -S $size $sessionId $name -") { output ->
                digest = streamApk(apk, output, base, total, this)
            }
            if (!written.ok || digest == null) {
                abandon(sessionId)
                send(InstallProgress.Failed(null, written.output.ifBlank { WRITE_FAILED }))
                return@channelFlow
            }
            if (digest != apk.sha256) {
                // The bytes delivered are not the ones verified. There is nothing to save: the
                // session is abandoned **before** the commit, and before writing the rest.
                abandon(sessionId)
                send(InstallProgress.Failed(null, hashMismatch(apk.name)))
                return@channelFlow
            }
            alreadyWritten += size
        }

        send(InstallProgress.Committing)
        val committed = shell.exec("pm install-commit $sessionId")
        if (committed.ok) {
            send(InstallProgress.Installed)
        } else {
            // No `install-abandon` here: a session that failed to commit is already closed, and
            // re-abandoning it would only print a second error more confusing than the first.
            send(InstallProgress.Failed(null, committed.output.ifBlank { COMMIT_FAILED }))
        }
    }

    override fun uninstall(packageName: String): Flow<UninstallProgress> = flow {
        emit(UninstallProgress.InProgress)
        val target = requireShellSafe(packageName)
            ?: return@flow emit(UninstallProgress.Failed(null, unsafe(packageName)))

        val result = shell.exec("pm uninstall $target")
        if (result.ok) {
            emit(UninstallProgress.Uninstalled)
        } else {
            emit(UninstallProgress.Failed(null, result.output.ifBlank { UNINSTALL_FAILED }))
        }
    }

    private suspend fun abandon(sessionId: Long) {
        runCatching { shell.exec("pm install-abandon $sessionId") }
    }

    /**
     * Copies the APK to the command's standard input, computing its digest **over the same bytes**.
     *
     * Progress goes through `trySend` and not `send` because this function does not suspend: it is
     * the body of a write to a stream, and turning it into a suspending function would mean being
     * cancellable with half a session written.
     */
    private fun streamApk(
        apk: StagedApk,
        output: OutputStream,
        alreadyWritten: Long,
        total: Long,
        progress: SendChannel<InstallProgress>,
    ): Sha256 {
        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        DigestInputStream(apk.file.inputStream().buffered(), digest).use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                written += read
                progress.trySend(InstallProgress.Writing(alreadyWritten + written, total))
            }
        }
        output.flush()
        return Sha256.ofBytes(digest.digest())
    }

    private companion object {

        /**
         * What can be interpolated into a shell line without thinking twice.
         *
         * Android package names are already limited to this alphabet, so the check takes nothing
         * legitimate away. It exists because those names come from a store's listing — i.e. from
         * downloaded HTML — and a shell line built by interpolation is the wrong place to trust a
         * remote string.
         */
        val SHELL_SAFE = Regex("[A-Za-z0-9_.]{1,255}")

        /**
         * The alphabet allowed for the **entry name** inside the session.
         *
         * Wider than [SHELL_SAFE] by two characters, `+` and `-`, because split names are written by
         * the store — `split_config.arm64_v8a.apk`, `Booster+1.2.apk` — and are not package names.
         * Wider by two characters and not "anything at all": this string still ends up interpolated
         * into a shell line.
         */
        val FILE_SAFE = Regex("[A-Za-z0-9_.+-]{1,255}")

        /** `Success: created install session [1234567]` */
        val SESSION_ID = Regex("""\[(\d+)]""")

        const val BUFFER_BYTES = 64 * 1024

        const val CREATE_FAILED = "pm install-create returned no session id"
        const val WRITE_FAILED = "pm install-write did not accept the APK"
        const val COMMIT_FAILED = "pm install-commit refused"
        const val UNINSTALL_FAILED = "pm uninstall refused"

        fun hashMismatch(name: String) =
            "the installed stream's hash differs from the verified one ($name)"

        fun requireShellSafe(value: String, alphabet: Regex = SHELL_SAFE): String? =
            value.takeIf { alphabet.matches(it) }

        fun unsafe(value: String) = "package name not usable in a shell line: $value"
    }
}

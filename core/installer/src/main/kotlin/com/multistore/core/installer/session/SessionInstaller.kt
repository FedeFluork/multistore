package com.multistore.core.installer.session

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import com.multistore.core.installer.InstallProgress
import com.multistore.core.installer.InstallRequest
import com.multistore.core.installer.Installer
import com.multistore.core.installer.StagedApk
import com.multistore.core.installer.UninstallProgress
import com.multistore.core.model.InstallerKind
import com.multistore.core.model.Sha256
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.DigestInputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * The installer that works on any device, with no setup.
 *
 * It uses `PackageInstaller` with the user's confirmation. It is the only one of the three requiring
 * nothing of the user before it can be used, and that is why it is the bottom of the
 * `ROOT -> SHIZUKU -> SESSION` chain: if the other two are absent, this one is always there.
 *
 * ### Three platform details invisible in JVM tests
 *
 * 1. **A mutable and explicit `PendingIntent` from API 31.** The system writes the outcome into it;
 *    a `FLAG_IMMUTABLE` makes the installation fail, and an implicit intent is refused.
 * 2. **`RECEIVER_NOT_EXPORTED` from API 33.** Registering a receiver without declaring it throws
 *    `SecurityException`. And it is right that it should not be exported: an installation's outcome
 *    is none of the other apps' business.
 * 3. **From API 34 the confirmation screen does not start from background.** That is why
 *    `STATUS_PENDING_USER_ACTION` is not launched here but returned as a state: it is the UI, which
 *    knows whether it is in the foreground, that decides when to show it.
 */
@Singleton
class SessionInstaller @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : Installer {

    override val kind: InstallerKind = InstallerKind.SESSION

    override val supportsSilent: Boolean = false

    /** Always. It is the premise the whole rest of the app rests on. */
    override suspend fun isAvailable(): Boolean = true

    override fun install(request: InstallRequest): Flow<InstallProgress> = callbackFlow {
        trySend(InstallProgress.Preparing)
        val installer = context.packageManager.packageInstaller
        val action = "${context.packageName}.INSTALL_RESULT.${request.packageName}.${System.nanoTime()}"
        val receiver = statusReceiver { status, message, intent ->
            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION ->
                    intent?.let { trySend(InstallProgress.UserActionRequired(it)) }

                PackageInstaller.STATUS_SUCCESS -> {
                    trySend(InstallProgress.Installed)
                    close()
                }

                PackageInstaller.STATUS_FAILURE_ABORTED -> {
                    trySend(InstallProgress.Cancelled)
                    close()
                }

                else -> {
                    trySend(InstallProgress.Failed(status, message))
                    close()
                }
            }
        }
        registerReceiver(receiver, action)

        var sessionId = -1
        try {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                .apply {
                    setAppPackageName(request.packageName)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        setInstallReason(android.content.pm.PackageManager.INSTALL_REASON_USER)
                    }
                    // The size is not cosmetic: `InstallSessionReconciler` re-reads it to say how
                    // many bytes it freed by closing an orphan session.
                    setSize(request.totalBytes)
                }
            sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                val total = request.totalBytes
                var alreadyWritten = 0L
                for (apk in request.apks) {
                    // The digest is compared **APK by APK and immediately**, not at the end: that
                    // way a mismatch on the first split abandons the session before writing the two
                    // hundred megabytes that come after.
                    val digest = writeApk(session, apk) { written ->
                        trySend(InstallProgress.Writing(alreadyWritten + written, total))
                    }
                    if (digest != apk.sha256) {
                        // The bytes delivered are not the ones verified. There is nothing to save:
                        // the session is abandoned before the commit.
                        session.abandon()
                        trySend(InstallProgress.Failed(statusCode = null, message = hashMismatch(apk.name)))
                        close()
                        return@use
                    }
                    alreadyWritten += apk.file.length()
                }
                trySend(InstallProgress.Committing)
                session.commit(resultIntent(action).intentSender)
            }
        } catch (e: Exception) {
            if (sessionId >= 0) runCatching { installer.abandonSession(sessionId) }
            trySend(InstallProgress.Failed(statusCode = null, message = e.message))
            close()
        }

        awaitClose { runCatching { context.unregisterReceiver(receiver) } }
    }

    override fun uninstall(packageName: String): Flow<UninstallProgress> = callbackFlow {
        trySend(UninstallProgress.InProgress)
        val action = "${context.packageName}.UNINSTALL_RESULT.$packageName.${System.nanoTime()}"
        val receiver = statusReceiver { status, message, intent ->
            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION ->
                    intent?.let { trySend(UninstallProgress.UserActionRequired(it)) }

                PackageInstaller.STATUS_SUCCESS -> {
                    trySend(UninstallProgress.Uninstalled)
                    close()
                }

                else -> {
                    trySend(UninstallProgress.Failed(status, message))
                    close()
                }
            }
        }
        registerReceiver(receiver, action)

        try {
            context.packageManager.packageInstaller.uninstall(packageName, resultIntent(action).intentSender)
        } catch (e: Exception) {
            trySend(UninstallProgress.Failed(statusCode = null, message = e.message))
            close()
        }

        awaitClose { runCatching { context.unregisterReceiver(receiver) } }
    }

    /**
     * Copies the APK into the session, computing its digest **over the same stream**.
     *
     * Verifying one file and then installing another is the TOCTOU window: here the bytes entering
     * the session are exactly those the hash is computed over, so there is no "then" in which
     * anything could change.
     */
    private fun writeApk(
        session: PackageInstaller.Session,
        apk: StagedApk,
        onProgress: (Long) -> Unit,
    ): Sha256 {
        val total = apk.file.length()
        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        session.openWrite(apk.name, 0, total).use { output ->
            DigestInputStream(apk.file.inputStream().buffered(), digest).use { input ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    written += read
                    onProgress(written)
                }
            }
            session.fsync(output)
        }
        return Sha256.ofBytes(digest.digest())
    }

    private fun resultIntent(action: String): PendingIntent {
        val intent = Intent(action).setPackage(context.packageName)
        // FLAG_MUTABLE is mandatory: the system writes the outcome inside this intent. With
        // FLAG_IMMUTABLE the installation fails, and the error message does not say so.
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(context, action.hashCode(), intent, flags)
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerReceiver(receiver: BroadcastReceiver, action: String) {
        val filter = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }

    private inline fun statusReceiver(
        crossinline onStatus: (status: Int, message: String?, userAction: Intent?) -> Unit,
    ): BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE) ?: return
            val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            @Suppress("DEPRECATION")
            val userAction = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            onStatus(status, message, userAction)
        }
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024

        fun hashMismatch(name: String) =
            "the installed stream's hash differs from the verified one ($name)"
    }
}

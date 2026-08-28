package com.multistore.core.installer.session

import android.content.Context
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import com.multistore.core.common.coroutine.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Closes `PackageInstaller` sessions left open by a process that no longer exists.
 *
 * It is the installation counterpart of `DownloadRepository.requeueInterrupted()`, and it comes from
 * a **measured** leak, not a hypothesised one: on the emulator, after a system confirmation left
 * halfway and the process dying, `dumpsys package installer` showed
 *
 * ```
 * Active Session 712557410:
 *   installerPackageName=com.multistore.debug  appPackageName=de.danoeh.antennapod
 *   sizeBytes=17700676  stageDir=/data/app/vmdl712557410.tmp
 *   mCommitted=true mSealed=true mDestroyed=false mFinalStatus=0
 * ```
 *
 * Seventeen megabytes in `/data/app` that nobody claims. The system collects them by itself, but
 * only after days: it is not a permanent leak, it is a slow one — and it accumulates precisely in the
 * case where it hurts most, i.e. on a full device.
 *
 * ### Why they are all abandoned, without looking at their age
 *
 * A session is tied to the process that created it by a `PendingIntent` delivering the outcome to a
 * receiver **registered at runtime** by [SessionInstaller]. That receiver dies with the process. So a
 * session outliving the process no longer has anyone to report to: whether it is confirmed or
 * refused, the result does not come back and the row in `installed_apps` does not get written
 * anyway.
 *
 * The limiting case is the one where the system's confirmation screen is still on screen while our
 * process restarts for something else (a worker, a notification). Abandoning makes that tap fail with
 * "app not installed". It is unpleasant, but it is the **honest** outcome: the alternative is an
 * installation that succeeds and that MultiStore knows nothing about, therefore absent from "My apps"
 * and outside update checking.
 *
 * The definitive way out is another job: a receiver **declared in the manifest**, which outlives the
 * process and knows how to pick the outcome up. Until there is one, this is the correct repair.
 *
 * **It is called once per process, at startup.** Calling it while an installation is in progress
 * would kill it: `mySessions` does not tell our live session from the orphans.
 */
@Singleton
class InstallSessionReconciler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {

    /** How many sessions were closed, and how many staging bytes they freed. */
    data class Outcome(val sessions: Int, val bytes: Long) {
        companion object {
            val Empty = Outcome(sessions = 0, bytes = 0L)
        }
    }

    suspend fun abandonOrphans(): Outcome = withContext(io) {
        val installer = context.packageManager.packageInstaller
        // `mySessions` returns only the sessions created by us: there is no way to touch another
        // app's, and indeed `abandonSession` on somebody else's session throws SecurityException.
        val mine = runCatching { installer.mySessions }.getOrElse {
            Log.w(TAG, "could not list the install sessions", it)
            return@withContext Outcome.Empty
        }
        var sessions = 0
        var bytes = 0L
        for (session in mine) {
            val abandoned = runCatching { installer.abandonSession(session.sessionId) }
            if (abandoned.isFailure) {
                Log.w(TAG, "session ${session.sessionId} could not be abandoned", abandoned.exceptionOrNull())
                continue
            }
            sessions++
            bytes += session.stagedBytes()
        }
        if (sessions > 0) {
            val orphans = if (sessions == 1) "1 orphaned session" else "$sessions orphaned sessions"
            Log.i(TAG, "abandoned $orphans, $bytes staging bytes")
        }
        Outcome(sessions = sessions, bytes = bytes)
    }

    /**
     * The bytes the session was holding, or zero where they cannot be known.
     *
     * `SessionInfo.getSize()` exists **from API 27** while the `minSdk` is 26. On 26 the number is
     * simply missing: it serves to describe the leak in the log, not to decide anything, and an
     * invented value would be worse than no value.
     */
    private fun PackageInstaller.SessionInfo.stagedBytes(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) size.coerceAtLeast(0L) else 0L

    private companion object {
        const val TAG = "InstallSessions"
    }
}

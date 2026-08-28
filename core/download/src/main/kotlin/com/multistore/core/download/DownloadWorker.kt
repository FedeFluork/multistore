package com.multistore.core.download

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * An APK's transfer, outside the screen that started it.
 *
 * Before this worker the download lived in the detail screen's `viewModelScope`: leaving the listing
 * cancelled it, and eighteen megabytes started over. The worker solves that, but above all it solves
 * a second, less visible one — **WorkManager stops an ordinary worker after ten minutes**, and a
 * 100 MB APK on a slow network gets there. A worker calling [setForeground] becomes a *long-running
 * worker* and that cap no longer applies. The foreground service here is not cosmetics: it is what
 * makes the download completable.
 *
 * ### What happens if the foreground is refused
 *
 * From Android 12 starting a foreground service **from the background** is forbidden, and
 * [setForeground] throws. The normal case does not get there — one downloads because the user has
 * just pressed a button, so the app is in the foreground — but automatic resumption at startup can
 * begin from a process the system woke up by itself. There the exception is caught and the download
 * carries on as an ordinary worker: the ten-minute cap applies again, which is worse than being in
 * the foreground but incomparably better than not downloading at all.
 *
 * ### Why the worker does not install
 *
 * It stops at the verified file in staging. Installation needs the system's confirmation screen,
 * which from API 34 **does not start from background**: launching it from here would mean discovering
 * the prohibition at the very moment the worker really runs on its own, i.e. in the case the worker
 * exists to handle.
 */
@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val task: DownloadTask,
    private val notifications: DownloadNotifications,
) : CoroutineWorker(appContext, params) {

    private val downloadId: Long get() = inputData.getLong(KEY_DOWNLOAD_ID, INVALID_ID)

    override suspend fun getForegroundInfo(): ForegroundInfo =
        foregroundInfo(label = task.label(downloadId), progress = null)

    override suspend fun doWork(): Result = coroutineScope {
        val id = downloadId
        if (id == INVALID_ID) return@coroutineScope Result.failure()

        val label = task.label(id)
        goForeground(label, progress = null)

        // The notification updates by reading the **persisted state**, not by intercepting the bytes:
        // it is the same source that feeds the bar on the listing, so the two cannot tell two
        // different stories. And it costs nothing — that state is already written in a conflated way,
        // not once per buffer.
        val notifier = launch {
            task.observeProgress(id).collect { progress ->
                if (progress != null && !progress.terminal) goForeground(label, progress)
            }
        }

        val succeeded = try {
            task.transfer(id)
        } finally {
            notifier.cancelAndJoin()
        }

        // `Result.failure()` and not `retry()`: an interrupted transfer leaves the row in `PAUSED`
        // with the bytes already taken, and whoever resumes it is a user gesture or
        // `requeueInterrupted()` at startup. Retrying here, with WorkManager's backoff, would mean
        // retrying in the background a download the user may no longer want — on a metered network,
        // what is more.
        if (succeeded) Result.success() else Result.failure()
    }

    private suspend fun goForeground(label: String?, progress: DownloadProgress?) {
        try {
            setForeground(foregroundInfo(label, progress))
        } catch (denied: IllegalStateException) {
            // `ForegroundServiceStartNotAllowedException` from API 31 is a subclass of it. It is not a
            // fault: the download carries on with no notification, under the ten-minute cap.
        }
    }

    private fun foregroundInfo(label: String?, progress: DownloadProgress?): ForegroundInfo {
        val notification = notifications.build(
            label = label,
            progress = progress,
            cancelIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id),
        )
        val notificationId = notifications.notificationId(downloadId)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // The type has to be declared: from Android 14 a foreground service with no type does not
            // start at all. `DATA_SYNC` is the right one for a file transfer, and it is the same one
            // this module's manifest declares.
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    companion object {
        const val KEY_DOWNLOAD_ID: String = "download_id"
        private const val INVALID_ID = -1L
    }
}

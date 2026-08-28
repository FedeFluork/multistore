package com.multistore.core.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whoever sets a download's worker going (and stops it).
 *
 * It lives in an object of its own and not inside the repository for a testing reason: the
 * repository is tested on the JVM with Room in memory, and `WorkManager.getInstance` demands Android
 * initialisation. With scheduling behind an injectable type, a test can pass one that records the
 * calls instead of running WorkManager.
 */
interface DownloadScheduler {

    /**
     * @param requireUnmetered wait for a **non**-metered network before starting.
     *
     * The parameter has no default value, and that is deliberate: whoever adds a point from which a
     * transfer starts has to say which side they are on, and the compiler asks them. The rule is
     * simple — `false` when the user has just pressed something, because they have already decided to
     * spend that traffic and postponing it to Wi-Fi would be deciding for them; `true` when the
     * transfer starts **by itself** (an automatic resumption, an update check) and the user has not
     * consented to metered networks.
     *
     * Today every call passes `false`, because every transfer is born of a tap. The automatic case
     * arrives with `UpdateCheckWorker`, and it is precisely so as not to have to remind it of this
     * decision then that the parameter exists now.
     */
    fun start(downloadId: Long, requireUnmetered: Boolean)

    fun cancel(downloadId: Long)
}

@Singleton
class WorkManagerDownloadScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : DownloadScheduler {

    override fun start(downloadId: Long, requireUnmetered: Boolean) {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(Data.Builder().putLong(DownloadWorker.KEY_DOWNLOAD_ID, downloadId).build())
            .setConstraints(
                // The constraint is decided by **the caller**, not by this object: the same queue serves
                // a user's tap and — when it exists — an update check starting at night, and the two
                // deserve different answers on the same network.
                Constraints.Builder()
                    .setRequiredNetworkType(
                        if (requireUnmetered) NetworkType.UNMETERED else NetworkType.CONNECTED,
                    )
                    .build(),
            )
            .addTag(TAG)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            workName(downloadId),
            // `KEEP` and not `REPLACE`: two taps on the same button must not produce two transfers on
            // the same staging file, where chance would decide the winner.
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    override fun cancel(downloadId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(downloadId))
    }

    private fun workName(downloadId: Long) = "$TAG:$downloadId"

    private companion object {
        const val TAG = "multistore-download"
    }
}

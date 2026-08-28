package com.multistore.core.updates

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/**
 * How often to check, and under what conditions.
 *
 * A type of its own instead of three loose parameters, because the three values go together: changing
 * one and rescheduling with the other two stale would give a check running under conditions the user
 * no longer chose.
 */
data class UpdateSchedule(
    val period: Duration,
    /** Wait for an **unmetered** network. */
    val requireUnmetered: Boolean,
    val requireCharging: Boolean,
)

/**
 * Whoever sets the periodic check going (and stops it).
 *
 * An interface for the same reason as `DownloadScheduler`: `WorkManager.getInstance` demands Android
 * initialisation, and what has to be proven — which constraints are built from the settings — is
 * better proven against a double that records the calls.
 */
interface UpdateScheduler {

    fun schedule(schedule: UpdateSchedule)

    /** Removes the periodic check. It is what "manual only" means. */
    fun cancel()
}

@Singleton
class WorkManagerUpdateScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : UpdateScheduler {

    /**
     * Schedules, or reschedules.
     *
     * `ExistingPeriodicWorkPolicy.UPDATE` and not `KEEP`: with `KEEP`, changing the interval in
     * Settings would have no effect until the user uninstalls the app — i.e. the Settings entry would
     * be a switch that does nothing. `UPDATE` replaces the request **keeping the period already
     * begun**, so it does not postpone the next check every time the app starts; with `REPLACE` a user
     * who opens the app often would never see a daily check fire.
     */
    override fun schedule(schedule: UpdateSchedule) {
        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(schedule.period.toJavaDuration())
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(
                        if (schedule.requireUnmetered) NetworkType.UNMETERED else NetworkType.CONNECTED,
                    )
                    .setRequiresCharging(schedule.requireCharging)
                    .build(),
            )
            .addTag(TAG)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    override fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    companion object {
        const val WORK_NAME: String = "multistore-update-check"
        private const val TAG = "multistore-updates"
    }
}

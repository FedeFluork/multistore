package com.multistore.core.updates

import com.multistore.core.common.coroutine.ApplicationScope
import com.multistore.core.data.repository.SettingsRepository
import com.multistore.core.model.UpdateSettings
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the scheduling aligned with the settings, for the process's whole life.
 *
 * ### Why it observes instead of just scheduling
 *
 * A scheduling done once at startup would be correct until the user touches anything. The moment they
 * change the interval, or switch on "only while charging", or allow metered networks, the old
 * scheduling would stay in force until the app restarts: i.e. three Settings entries that seem to do
 * nothing. Observing costs one idle coroutine.
 *
 * ### Why it reads metered networks too
 *
 * A periodic job's network constraint is fixed when it is enqueued, not when it starts.
 * `metered_network_allowed` is therefore as much an input to the scheduling as the interval — and if
 * it changes, the job has to be re-enqueued with the new constraint.
 *
 * ### Why `distinctUntilChanged` comes **after** the computation and not before
 *
 * Different settings do not always make different schedulings: with the interval on "only when I ask"
 * the plan is `null` whatever "only while charging" and metered networks say, and without the filter
 * every touch of those two switches would re-enqueue — or rather re-cancel — the same job. Filtering
 * the **plans** instead of the settings is what makes the difference visible to the filter.
 *
 * The incoming settings are already distinct on their own: `SettingsLocalDataSource` projects each
 * group with its own `distinctUntilChanged`, so changing the theme does not even reach here.
 */
@Singleton
class UpdateScheduling @Inject constructor(
    private val settings: SettingsRepository,
    private val scheduler: UpdateScheduler,
    @param:ApplicationScope private val scope: CoroutineScope,
) {

    fun start() {
        scope.launch {
            combine(settings.updates, settings.network) { updates, network ->
                planOf(updates, meteredAllowed = network.meteredNetworkAllowed)
            }
                .distinctUntilChanged()
                .collect { plan -> if (plan == null) scheduler.cancel() else scheduler.schedule(plan) }
        }
    }

    /** `null` when the user has chosen "only when I ask". */
    private fun planOf(updates: UpdateSettings, meteredAllowed: Boolean): UpdateSchedule? {
        val period = updates.interval.period ?: return null
        return UpdateSchedule(
            period = period,
            requireUnmetered = !meteredAllowed,
            requireCharging = updates.onlyWhenCharging,
        )
    }
}

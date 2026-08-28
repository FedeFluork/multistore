package com.multistore.core.updates

import com.google.common.truth.Truth.assertThat
import com.multistore.core.model.NetworkSettings
import com.multistore.core.model.UpdateInterval
import com.multistore.core.testing.FakeSettingsRepository
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

/**
 * The periodic check's scheduling.
 *
 * What has to be proven here is not WorkManager — that is proven by whoever writes it — but the
 * **translation between three settings and a periodic job's constraints**. It is the point at which a
 * Settings entry becomes a behaviour, and at which it easily becomes nothing: a scheduling done once
 * at startup would work until the user touches anything, and from then on the three entries would
 * seem to do nothing.
 */
class UpdateSchedulingTest {

    private val settings = FakeSettingsRepository()
    private val scheduler = RecordingScheduler()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() = scope.cancel()

    private fun scheduling() = UpdateScheduling(settings, scheduler, scope)

    @Test
    fun `the default is daily, on a non-metered network`() = runTest {
        scheduling().start()

        // No setting touched: it is what whoever installs the app and never opens Settings sees. The
        // daily period comes from the proto's zero value, and the non-metered network from the fact
        // that `metered_network_allowed` starts off.
        assertThat(scheduler.scheduled).containsExactly(
            UpdateSchedule(period = 1.days, requireUnmetered = true, requireCharging = false),
        )
        assertThat(scheduler.cancellations).isEqualTo(0)
    }

    @Test
    fun `'only when I ask' removes the periodic job instead of slowing it`() = runTest {
        settings.updates.value = settings.updates.value.copy(interval = UpdateInterval.MANUAL)

        scheduling().start()

        // Not a very long period: a job that must not start must not **be there**.
        assertThat(scheduler.scheduled).isEmpty()
        assertThat(scheduler.cancellations).isEqualTo(1)
    }

    @Test
    fun `allowing metered networks changes the network constraint`() = runTest {
        settings.network.value = NetworkSettings(meteredNetworkAllowed = true)

        scheduling().start()

        assertThat(scheduler.scheduled.single().requireUnmetered).isFalse()
    }

    @Test
    fun `'only while charging' becomes a constraint, not a check inside the worker`() = runTest {
        settings.updates.value = settings.updates.value.copy(onlyWhenCharging = true)

        scheduling().start()

        // If it were a check inside the worker, the system would wake it up anyway only for it to give
        // up: the constraint is what saves it from waking.
        assertThat(scheduler.scheduled.single().requireCharging).isTrue()
    }

    @Test
    fun `changing the interval reschedules, without waiting for an app restart`() = runTest {
        scheduling().start()

        settings.setUpdateInterval(UpdateInterval.EVERY_6_HOURS)

        assertThat(scheduler.scheduled.map { it.period })
            .containsExactly(1.days, 6.hours)
            .inOrder()
    }

    @Test
    fun `switching to manual while the app runs removes the job`() = runTest {
        scheduling().start()

        settings.setUpdateInterval(UpdateInterval.MANUAL)

        assertThat(scheduler.cancellations).isEqualTo(1)
    }

    @Test
    fun `a setting that does not change the plan reschedules nothing`() = runTest {
        settings.updates.value = settings.updates.value.copy(interval = UpdateInterval.MANUAL)
        scheduling().start()

        // With the check on "only when I ask" the plan is "no job", whatever "only while charging" and
        // metered networks say. Without the filter **on the plans**, each of these touches would
        // re-cancel a job that is already gone.
        settings.setUpdateOnlyWhenCharging(true)
        settings.setMeteredNetworkAllowed(true)

        assertThat(scheduler.cancellations).isEqualTo(1)
        assertThat(scheduler.scheduled).isEmpty()
    }

    @Test
    fun `two settings leading to the same plan do not enqueue it twice`() = runTest {
        scheduling().start()

        // Setting the same interval again is not a change: `enqueueUniquePeriodicWork` would do no
        // visible damage, but would still write to WorkManager's database.
        settings.setUpdateInterval(UpdateInterval.DAILY)

        assertThat(scheduler.scheduled).hasSize(1)
    }

    private class RecordingScheduler : UpdateScheduler {
        val scheduled = mutableListOf<UpdateSchedule>()
        var cancellations = 0
            private set

        override fun schedule(schedule: UpdateSchedule) {
            scheduled += schedule
        }

        override fun cancel() {
            cancellations++
        }
    }
}

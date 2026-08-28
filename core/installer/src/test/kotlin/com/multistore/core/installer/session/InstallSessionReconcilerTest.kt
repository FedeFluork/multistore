package com.multistore.core.installer.session

import android.content.Context
import android.content.pm.PackageInstaller
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Repairing orphan sessions.
 *
 * What has to be proven is not `abandonSession` — that is the system — but the fact that after the
 * pass **nothing remains**: counting the closed sessions without checking they are gone would give a
 * right number and a still-occupied disk.
 *
 * **What these tests do not cover, and why it is not a gap that can be closed here.**
 * `Outcome.bytes` stays at zero under Robolectric: `PackageInstaller`'s shadow does not carry
 * `SessionParams.setSize()` into the `SessionInfo` it later returns. A test asserting it would prove
 * the shadow's limit, not our code. That number is observed on a device — and that is where it was
 * observed: `dumpsys package installer` gave `sizeBytes=17700676` for the orphan session that gave
 * rise to this class.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class InstallSessionReconcilerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val installer: PackageInstaller = context.packageManager.packageInstaller
    private val reconciler = InstallSessionReconciler(context, UnconfinedTestDispatcher())

    @Test
    fun `with no open sessions it does nothing and does not blow up`() = runTest {
        assertThat(reconciler.abandonOrphans()).isEqualTo(InstallSessionReconciler.Outcome.Empty)
    }

    @Test
    fun `it closes every session left open`() = runTest {
        openSession("de.danoeh.antennapod", sizeBytes = 17_700_676L)
        openSession("org.mozilla.fennec_fdroid", sizeBytes = 60_000_000L)

        val outcome = reconciler.abandonOrphans()

        assertThat(outcome.sessions).isEqualTo(2)
        // The count alone would not be enough: the reason this repair exists is to free `/data/app`,
        // and that is only visible from the list that remains.
        assertThat(installer.mySessions).isEmpty()
    }

    @Test
    fun `a second pass finds nothing more`() = runTest {
        openSession("de.danoeh.antennapod", sizeBytes = 17_700_676L)

        reconciler.abandonOrphans()

        // Idempotence: `AppStartup` runs on every process creation, and a process that restarts often
        // must not count the same repair twice.
        assertThat(reconciler.abandonOrphans().sessions).isEqualTo(0)
    }

    private fun openSession(packageName: String, sizeBytes: Long): Int {
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(packageName)
        params.setSize(sizeBytes)
        return installer.createSession(params)
    }
}

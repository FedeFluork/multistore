package com.multistore.app

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.multistore.core.common.result.Outcome
import com.multistore.core.data.repository.AppDetail
import com.multistore.core.data.repository.DownloadStatus
import com.multistore.core.data.repository.InstallStep
import com.multistore.core.domain.usecase.ActiveInstallDrivers
import com.multistore.core.domain.usecase.InstallAppUseCase
import com.multistore.core.domain.usecase.ResolveDownloadUseCase
import com.multistore.core.data.store.StoreRegistry
import com.multistore.core.model.DownloadState
import com.multistore.core.model.InstallerKind
import com.multistore.core.model.InstallerAvailability
import com.multistore.core.model.InstallerPreference
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.model.StoreListingDetail
import com.multistore.core.model.StoreListingSummary
import com.multistore.core.model.VersionRef
import com.multistore.core.common.version.VersionSelection
import com.multistore.core.testing.FakeAppDetailRepository
import com.multistore.core.testing.FakeDownloadRepository
import com.multistore.core.testing.FakeInstallRepository
import com.multistore.core.testing.FakeSettingsRepository
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Which finished download the shell may carry on to its installation by itself.
 *
 * The decision is the whole of this class's logic — `resume` is `InstallAppUseCase`'s and is proven
 * where it lives — so most of these tests read the **claim**: taking it is the act of deciding, and
 * it is atomic precisely because two candidates can want the same file.
 *
 * The four conditions are tested one at a time because they are independent, and each of them, if
 * dropped, produces a different wrong behaviour: a dialog for a download the user asked not to
 * install, two dialogs for one app, a dialog for a file left over from last week, and a loop after a
 * dismissed confirmation.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AutoInstallCoordinatorTest {

    private val store = StoreId.FDROID
    private val ref = StoreAppRef("org.example.app")

    private val downloads = FakeDownloadRepository()
    private val installs = FakeInstallRepository()
    private val settings = FakeSettingsRepository()
    private val details = FakeAppDetailRepository()
    private val drivers = ActiveInstallDrivers()

    @Test
    fun `a download that finishes while the app is open is carried on`() = runTest {
        settingOn()
        val coordinator = start(this)
        downloads.active.value = listOf(ready(id = 1))

        assertThat(downloads.claims).containsExactly(1L)
    }

    @Test
    fun `a file already waiting when the process started is left alone`() = runTest {
        settingOn()
        // A download that ended at some unknown point in the past — yesterday, last week. Proposing
        // it at the next launch would be a dialog with no cause the user can see, which is the
        // opposite of "when the download finishes".
        downloads.active.value = listOf(ready(id = 1))
        start(this)

        assertThat(downloads.claims).isEmpty()
    }

    @Test
    fun `a transfer asked to download and stop is not carried on`() = runTest {
        settingOn()
        val coordinator = start(this)
        // The periodic check with `auto_install_updates` off: the user has already answered this
        // question, and overruling it here would be answering it for them.
        downloads.active.value = listOf(ready(id = 1, pendingInstall = false))

        assertThat(downloads.claims).isEmpty()
    }

    @Test
    fun `a download a listing is still driving is left to that listing`() = runTest {
        settingOn()
        details.details.value = detail()
        downloads.completion = Outcome.Success(File("app.apk"))
        // The listing is inside `downloadAndInstall`, waiting: that is the window in which the
        // register says "a screen has this one".
        downloads.completionDelay = 10.seconds
        downloads.active.value = listOf(ready(id = 1).copy(state = DownloadState.RUNNING))
        val coordinator = start(this)
        // Unconfined on purpose: the listing has to be **already inside** `downloadAndInstall` when
        // the row turns ready. Queued on the standard dispatcher it would not have registered yet,
        // and the test would pass for the wrong reason — the register empty because nobody had
        // started, not because the rule works.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            installApp().resume(store, ref, 1L).collect { }
        }

        downloads.active.value = listOf(ready(id = 1))

        // Two `PackageInstaller` sessions on one APK are two confirmation dialogs for one app. The
        // assertion is made **before** the virtual clock is advanced: once the listing's own wait
        // ends it spends the claim itself, which is the right thing and a different fact.
        assertThat(downloads.claims).isEmpty()
        assertThat(drivers.isDriven(1L)).isTrue()
    }

    @Test
    fun `with the switch off nothing is proposed`() = runTest {
        val coordinator = start(this)
        downloads.active.value = listOf(ready(id = 1))

        assertThat(downloads.claims).isEmpty()
    }

    @Test
    fun `with a silent installer chosen there is no prompt to propose`() = runTest {
        settingOn()
        // The stored `true` may date from before Shizuku was installed on this device: the predicate
        // has to be read now, and it has to be the same one the Settings row disables itself with.
        installs.availability = InstallerAvailability(
            supported = setOf(InstallerKind.SESSION, InstallerKind.SHIZUKU),
            usable = setOf(InstallerKind.SESSION, InstallerKind.SHIZUKU),
            silent = setOf(InstallerKind.SHIZUKU),
        )
        val coordinator = start(this)
        downloads.active.value = listOf(ready(id = 1))

        assertThat(downloads.claims).isEmpty()
    }

    @Test
    fun `an explicit choice of the system installer keeps the switch meaningful`() = runTest {
        settingOn()
        settings.installation.value =
            settings.installation.value.copy(preference = InstallerPreference.SESSION)
        // Root is there and usable, and it still does not matter: whoever chose the confirmation
        // asked to see it, so there **is** a prompt to propose. The same asymmetry as
        // `InstallerSelector.selectSilent`.
        installs.availability = InstallerAvailability(
            supported = setOf(InstallerKind.SESSION, InstallerKind.ROOT),
            usable = setOf(InstallerKind.SESSION, InstallerKind.ROOT),
            silent = setOf(InstallerKind.ROOT),
        )
        val coordinator = start(this)
        downloads.active.value = listOf(ready(id = 1))

        assertThat(downloads.claims).containsExactly(1L)
    }

    @Test
    fun `a claim already spent stops it happening twice`() = runTest {
        settingOn()
        // What a dismissed confirmation leaves behind: the row stays `READY` with the token gone.
        // Without this, every redraw of the list would propose it again.
        downloads.claimSucceeds = false
        val coordinator = start(this)
        downloads.active.value = listOf(ready(id = 1))

        assertThat(installs.plans).isEmpty()
    }

    @Test
    fun `the confirmation reaches the only thing allowed to launch it`() = runTest {
        settingOn()
        details.details.value = detail()
        downloads.completion = Outcome.Success(File("app.apk"))
        installs.installSteps = listOf(InstallStep.UserActionRequired(Intent("android.intent.action.VIEW")))
        val coordinator = start(this)

        downloads.active.value = listOf(ready(id = 1))

        // The coordinator does not start it: from API 34 the system's confirmation cannot be started
        // from the background, so it hands it to whoever knows it is in the foreground.
        assertThat(coordinator.userActions.first().action).isEqualTo("android.intent.action.VIEW")
    }

    // --- infrastructure ---------------------------------------------------------------------

    private fun settingOn() {
        settings.installation.value =
            settings.installation.value.copy(autoInstallAfterDownload = true)
    }

    private fun installApp() = InstallAppUseCase(
        resolve = ResolveDownloadUseCase(StoreRegistry(emptySet()), details, settings),
        downloads = downloads,
        installs = installs,
        details = details,
        settings = settings,
        drivers = drivers,
    )

    private fun start(scope: kotlinx.coroutines.test.TestScope): AutoInstallCoordinator {
        val coordinator = AutoInstallCoordinator(
            downloads = downloads,
            installApp = installApp(),
            installs = installs,
            settings = settings,
            drivers = drivers,
            scope = CoroutineScope(UnconfinedTestDispatcher(scope.testScheduler)),
        )
        coordinator.start()
        return coordinator
    }

    private fun ready(id: Long, pendingInstall: Boolean = true) = DownloadStatus(
        id = id,
        storeId = store,
        ref = ref,
        versionRef = VersionRef("v1"),
        packageName = ref.value,
        state = DownloadState.READY,
        bytesDownloaded = 100,
        bytesTotal = 100,
        file = File("app.apk"),
        error = null,
        pendingInstall = pendingInstall,
    )

    private fun detail() = AppDetail(
        listing = StoreListingDetail(
            summary = StoreListingSummary(
                storeId = store,
                ref = ref,
                title = "Example",
                packageName = ref.value,
            ),
            versions = emptyList(),
        ),
        installed = null,
        selection = VersionSelection.Outcome.NothingInstallable,
        stale = false,
    )
}

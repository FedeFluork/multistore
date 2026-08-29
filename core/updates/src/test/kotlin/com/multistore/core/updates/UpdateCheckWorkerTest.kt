package com.multistore.core.updates

import android.app.NotificationManager
import android.content.Context
import androidx.core.content.getSystemService
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.google.common.truth.Truth.assertThat
import com.multistore.core.common.result.AppError
import com.multistore.core.common.result.Outcome
import com.multistore.core.common.version.VersionSelection
import com.multistore.core.data.repository.AppDetail
import com.multistore.core.data.repository.InstallPlan
import com.multistore.core.data.repository.InstallRepository
import com.multistore.core.data.repository.InstallStep
import com.multistore.core.data.repository.InstalledAppUpdate
import com.multistore.core.data.repository.UpdateChannel
import com.multistore.core.data.repository.UpdateCheckReport
import com.multistore.core.data.store.StoreRegistry
import com.multistore.core.domain.usecase.ActiveInstallDrivers
import com.multistore.core.domain.usecase.InstallAppUseCase
import com.multistore.core.domain.usecase.ObserveUpdatesUseCase
import com.multistore.core.domain.usecase.ResolveDownloadUseCase
import com.multistore.core.model.AppVersion
import com.multistore.core.model.InstalledApp
import com.multistore.core.installer.verify.PreInstallVerifier
import com.multistore.core.model.ArtifactType
import com.multistore.core.model.InstallerAvailability
import com.multistore.core.model.InstallerKind
import com.multistore.core.model.OwnPackage
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreListingDetail
import com.multistore.core.model.StoreListingSummary
import com.multistore.core.model.StoreId
import com.multistore.core.model.VersionRef
import com.multistore.core.testing.FakeAppDetailRepository
import com.multistore.core.testing.FakeDownloadRepository
import com.multistore.core.testing.FakeInstallRepository
import com.multistore.core.testing.FakeInstalledAppsRepository
import com.multistore.core.testing.FakeStoreAdapter
import com.multistore.core.testing.FakeSettingsRepository
import com.multistore.core.testing.FakeUpdateRepository
import com.multistore.store.api.DownloadResolution
import com.multistore.store.api.StoreResult
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The periodic check's worker.
 *
 * What has to be proven is not "WorkManager works" but the decisions the worker takes on its own:
 * that the check is made, that the notification says what the **catalogue** says and not what the
 * report says, and that the three switches — silence, download by itself, install by itself — are
 * really read, otherwise they would be Settings entries that change nothing.
 *
 * The last is also the one that can do the most damage: an `auto_install_updates` forgetting
 * `requireSilent` would ask for a confirmation on a switched-off screen.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class UpdateCheckWorkerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val settings = FakeSettingsRepository()
    private val updates = FakeUpdateRepository()
    private val installedApps = FakeInstalledAppsRepository()
    private val downloads = FakeDownloadRepository()
    private val installs = FakeInstallRepository()
    private val details = FakeAppDetailRepository()

    @Test
    fun `the check runs, and the notification lists what there is to update`() {
        updates.state.value = listOf(update("AntennaPod"), update("Firefox"))

        val result = runWorker()

        assertThat(result).isInstanceOf(ListenableWorker.Result.Success::class.java)
        assertThat(updates.checks).isEqualTo(1)
        val posted = shownNotification()
        assertThat(posted).isNotNull()
        assertThat(posted).contains("AntennaPod")
        assertThat(posted).contains("Firefox")
    }

    @Test
    fun `silencing the notices does not stop the check, it stops the notification`() {
        updates.state.value = listOf(update("AntennaPod"))
        settings.updates.value = settings.updates.value.copy(muteNotifications = true)

        runWorker()

        // The two things are deliberately separate: whoever silences the notices wants to stop being
        // disturbed, not to stop seeing the updates on the Home.
        assertThat(updates.checks).isEqualTo(1)
        assertThat(shownNotification()).isNull()
    }

    @Test
    fun `nothing to update, no notification`() {
        updates.state.value = emptyList()

        runWorker()

        assertThat(shownNotification()).isNull()
    }

    @Test
    fun `with the switches off it downloads and installs nothing`() {
        readyToInstall()
        updates.state.value = listOf(update("AntennaPod"))

        runWorker()

        // It is what whoever installs the app and never opens Settings sees: the check warns, and that
        // is all. Downloading tens of megabytes by itself without anyone having asked would be the
        // easiest thing to get wrong here.
        assertThat(downloads.started).isEmpty()
        assertThat(installs.plans).isEmpty()
    }

    @Test
    fun `'download by itself' downloads and stops there`() {
        readyToInstall()
        updates.state.value = listOf(update("AntennaPod"))
        settings.updates.value = settings.updates.value.copy(autoDownload = true)

        runWorker()

        assertThat(downloads.started).hasSize(1)
        // The file stays in staging and the listing will offer "Install": stopping here is the switch's
        // point, not a limit of it.
        assertThat(installs.plans).isEmpty()
    }

    @Test
    fun `a transfer born by itself waits for a non-metered network`() {
        readyToInstall()
        updates.state.value = listOf(update("AntennaPod"))
        settings.updates.value = settings.updates.value.copy(autoDownload = true)

        runWorker()

        // Nobody pressed anything: postponing to Wi-Fi is not deciding for the user, it is not deciding
        // for them.
        assertThat(downloads.startedUnmetered).hasSize(1)
    }

    @Test
    fun `whoever allowed metered networks does not wait`() {
        readyToInstall()
        updates.state.value = listOf(update("AntennaPod"))
        settings.updates.value = settings.updates.value.copy(autoDownload = true)
        settings.network.value = settings.network.value.copy(meteredNetworkAllowed = true)

        runWorker()

        assertThat(downloads.started).hasSize(1)
        assertThat(downloads.startedUnmetered).isEmpty()
    }

    @Test
    fun `'install by itself' demands a silent installer, and writes it in the plan`() {
        readyToInstall()
        updates.state.value = listOf(update("AntennaPod"))
        settings.updates.value = settings.updates.value.copy(autoInstall = true)

        runWorker()

        // It is not a formality: without it, with `SessionInstaller` alone the worker would open the
        // system's confirmation screen from the background — which from API 34 does not start, so the
        // fault would be an installation that does not happen with nobody saying so.
        assertThat(installs.plans.single().requireSilent).isTrue()
    }

    @Test
    fun `installing by itself implies downloading by itself`() {
        readyToInstall()
        updates.state.value = listOf(update("AntennaPod"))
        settings.updates.value = settings.updates.value.copy(autoInstall = true)

        runWorker()

        // The two switches are distinct because the second has a prerequisite the first does not, not
        // because one can install without downloading.
        assertThat(downloads.started).hasSize(1)
    }

    @Test
    fun `the notification lists what remains, not what has just been installed`() {
        readyToInstall()
        updates.state.value = listOf(update("AntennaPod"))
        settings.updates.value = settings.updates.value.copy(autoInstall = true)

        // An installed app disappears from the available updates: it is what the real catalogue
        // re-emits, and without this piece the test could not tell the two reads apart.
        runWorker(installRepository = ForgettingInstallRepository())

        // Reading the list before applying would mean a notification announcing precisely the apps just
        // updated — and staying there saying the false thing until touched.
        assertThat(installs.plans).hasSize(1)
        assertThat(shownNotification()).isNull()
    }

    @Test
    fun `MultiStore updates last, because updating oneself kills the process`() {
        readyToInstall()
        updates.state.value = listOf(update("MultiStore", OWN_PACKAGE), update("AntennaPod"))
        settings.updates.value = settings.updates.value.copy(autoInstall = true)

        runWorker()

        // `mapNotNull`: `InstallPlan.ref` is nullable, because MultiStore's own update does not come
        // from a store. Here the plans all come from the periodic check, which always has a store — if
        // one day one arrived without, the row would disappear from the list instead of making the test
        // fail, and it is why just below the number of plans is also checked.
        assertThat(installs.plans).hasSize(2)
        assertThat(installs.plans.mapNotNull { it.ref?.value })
            .containsExactly("AntennaPod", "MultiStore")
            .inOrder()
    }

    @Test
    fun `a store that does not answer does not make the job look broken`() {
        updates.report = UpdateCheckReport(
            checked = 1,
            failures = mapOf(StoreId.APKMIRROR to AppError.RateLimited(null)),
        )

        // A `retry` would trigger WorkManager's backoff, i.e. would knock again at a door that has just
        // said no. The next period will come anyway.
        assertThat(runWorker()).isInstanceOf(ListenableWorker.Result.Success::class.java)
    }

    // --- the three notices: what happened while nobody was watching ----------------------------

    @Test
    fun `a store that does not answer becomes a notice, not only a report line`() {
        updates.report = UpdateCheckReport(
            checked = 1,
            failures = mapOf(StoreId.FDROID to AppError.RateLimited(null)),
        )

        runWorker()

        // The report was already produced by `check()` and nobody read it. The news matters because the
        // queried stores **are** the installed apps' channels: a silent store means apps that silently
        // stop updating.
        assertThat(shownNotification(channel = "stores")).contains("1 store did not answer")
    }

    @Test
    fun `the stores notice can be switched off`() {
        settings.notifications.value =
            settings.notifications.value.copy(muteStoreAlerts = true)
        updates.report = UpdateCheckReport(
            checked = 1,
            failures = mapOf(StoreId.FDROID to AppError.RateLimited(null)),
        )

        runWorker()

        assertThat(shownNotification(channel = "stores")).isNull()
    }

    @Test
    fun `a successful unattended installation says so`() {
        readyToInstall()
        updates.state.value = listOf(update("AntennaPod"))
        settings.updates.value = settings.updates.value.copy(autoDownload = true, autoInstall = true)

        runWorker(ForgettingInstallRepository())

        assertThat(shownNotification(channel = "installs")).contains("1 app updated")
    }

    @Test
    fun `a failed installation says so, and it is the case that counts most`() {
        readyToInstall()
        updates.state.value = listOf(update("AntennaPod"))
        // The signature does not match: the update will never arrive by itself, and staying silent
        // would make it invisible forever.
        installs.installSteps = listOf(
            InstallStep.Rejected(
                PreInstallVerifier.VerificationOutcome.SignerMismatchWithInstalled(
                    installed = null,
                    actual = emptyList(),
                ),
            ),
        )
        settings.updates.value = settings.updates.value.copy(autoDownload = true, autoInstall = true)

        runWorker()

        // The title tells the **worst case**: a "1 app updated" above a failure hidden in the body
        // would be the way of making sure nobody reads it.
        assertThat(shownNotification(channel = "installs"))
            .contains("1 app could not be updated")
    }

    @Test
    fun `downloading without installing says the file is waiting for a tap`() {
        readyToInstall()
        updates.state.value = listOf(update("AntennaPod"))
        // "Download by itself" without "install by itself": the file is there, verification passed, and
        // a person is missing. Without this line the app would silently wait for a gesture nobody knows
        // they have to make.
        settings.updates.value = settings.updates.value.copy(autoDownload = true, autoInstall = false)

        runWorker()

        assertThat(shownNotification(channel = "installs")).contains("1 app ready to install")
    }

    @Test
    fun `the installation notice can be switched off without switching off the updates one`() {
        readyToInstall()
        updates.state.value = listOf(update("AntennaPod"))
        settings.notifications.value = settings.notifications.value.copy(
            muteInstallResult = true,
            muteDownloadComplete = true,
        )
        settings.updates.value = settings.updates.value.copy(autoDownload = true, autoInstall = true)

        runWorker(ForgettingInstallRepository())

        // The four switches are independent: whoever silences the outcomes did not ask to lose the list
        // of what there is to update, and vice versa.
        assertThat(shownNotification(channel = "installs")).isNull()
    }

    // --- infrastructure -----------------------------------------------------------------------

    /**
     * A store that really resolves a link.
     *
     * Without it, every installation would end in `NotFound` and the switch tests would pass **even
     * with the switch on**, i.e. would prove nothing.
     */
    private class ResolvingAdapter : FakeStoreAdapter(id = StoreId.FDROID) {
        override suspend fun getDownloadLink(
            ref: StoreAppRef,
            version: VersionRef?,
        ): StoreResult<DownloadResolution> = StoreResult.Success(
            DownloadResolution.Direct(
                url = "https://example.test/app.apk",
                headers = emptyMap(),
                fileName = "app.apk",
                artifactType = ArtifactType.APK,
                expectedSha256 = null,
                expectedSize = 1_000L,
            ),
        )
    }

    private val registry = StoreRegistry(setOf(ResolvingAdapter()))

    /** Everything needed for an installation to be able to reach the end. */
    private fun readyToInstall() {
        details.details.value = AppDetail(
            listing = StoreListingDetail(
                summary = StoreListingSummary(
                    storeId = StoreId.FDROID,
                    ref = StoreAppRef("qualsiasi"),
                    title = "Example",
                    packageName = "org.example.app",
                ),
                versions = listOf(AppVersion("2.0", 2, VersionRef("v2"))),
            ),
            installed = null,
            selection = VersionSelection.Outcome.Offer(
                AppVersion("2.0", 2, VersionRef("v2")),
                isUpdate = true,
            ),
            stale = false,
        )
        downloads.completion = Outcome.Success(java.io.File("/dev/null"))
        installs.installSteps = listOf(InstallStep.Installed("org.example.app", 2))
        installs.availability = InstallerAvailability(
            supported = setOf(InstallerKind.SESSION, InstallerKind.SHIZUKU),
            usable = setOf(InstallerKind.SESSION, InstallerKind.SHIZUKU),
            silent = setOf(InstallerKind.SHIZUKU),
        )
    }

    /** An `InstallRepository` that removes the app from the updates, as the real catalogue does. */
    private inner class ForgettingInstallRepository : InstallRepository by installs {
        override fun install(plan: InstallPlan): Flow<InstallStep> =
            installs.install(plan).onCompletion { updates.state.value = emptyList() }
    }

    private fun runWorker(
        installRepository: InstallRepository = installs,
    ): ListenableWorker.Result {
        val worker = TestListenableWorkerBuilder<UpdateCheckWorker>(context)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker = UpdateCheckWorker(
                        appContext = appContext,
                        params = workerParameters,
                        updates = ObserveUpdatesUseCase(updates, installedApps),
                        installApp = InstallAppUseCase(
                            resolve = ResolveDownloadUseCase(registry, details, settings),
                            downloads = downloads,
                            installs = installRepository,
                            details = details,
                            settings = settings,
                            drivers = ActiveInstallDrivers(),
                        ),
                        settings = settings,
                        notifications = UpdateNotifications(appContext),
                        ownPackage = OwnPackage(OWN_PACKAGE),
                        registry = registry,
                    )
                },
            )
            .build()
        return runBlocking { worker.doWork() }
    }

    /**
     * The published notification's text, or `null` if there is none.
     *
     * The worker can now publish up to four in the same round, so "the first" is no longer a question
     * with an answer: it is chosen by **channel**, which is also how the user tells them apart in the
     * system settings.
     */
    private fun shownNotification(channel: String = "updates"): String? {
        val manager = requireNotNull(context.getSystemService<NotificationManager>())
        val notification = shadowOf(manager).allNotifications
            .firstOrNull { it.channelId == channel }
            ?: return null
        return buildString {
            append(notification.extras.getCharSequence("android.title"))
            append(' ')
            append(notification.extras.getCharSequence("android.text"))
        }
    }

    private fun update(
        title: String,
        packageName: String = "org.example.${title.lowercase()}",
    ) = InstalledAppUpdate(
        app = InstalledApp(
            packageName = packageName,
            label = title,
            versionName = "1.0",
            versionCode = 1,
            signerSha256 = null,
            installedAt = Instant.fromEpochSeconds(1_756_000_000),
            installerKind = InstallerKind.SESSION,
        ),
        channel = UpdateChannel(
            storeId = StoreId.FDROID,
            ref = StoreAppRef(title),
            listingId = 1L,
            title = title,
            iconUrl = null,
        ),
        selection = VersionSelection.Outcome.Offer(
            version = AppVersion(versionName = "2.0", versionCode = 2, ref = VersionRef("v2")),
            isUpdate = true,
        ),
    )

    private companion object {
        const val OWN_PACKAGE = "com.multistore.test"
    }
}

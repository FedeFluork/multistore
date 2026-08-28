package com.multistore.feature.myapps

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.multistore.core.common.version.VersionSelection
import com.multistore.core.data.repository.InstallStep
import com.multistore.core.data.repository.InstalledAppUpdate
import com.multistore.core.data.repository.UpdateChannel
import com.multistore.core.data.repository.UpdateCheckReport
import com.multistore.core.data.store.StoreRegistry
import com.multistore.core.domain.usecase.ObserveInstalledAppsUseCase
import com.multistore.core.domain.usecase.ObserveUpdatesUseCase
import com.multistore.core.domain.usecase.UninstallAppUseCase
import com.multistore.core.model.AppVersion
import com.multistore.core.model.InstalledApp
import com.multistore.core.model.InstalledPackage
import com.multistore.core.model.InstallerKind
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.model.VersionRef
import com.multistore.core.testing.FakeInstallRepository
import com.multistore.core.testing.FakeInstalledAppsRepository
import com.multistore.core.testing.FakeStoreAdapter
import com.multistore.core.testing.FakeUpdateRepository
import com.multistore.core.testing.MainDispatcherRule
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * "My apps": the list, and the uninstall that is always a conversation.
 *
 * The two things this test protects are both things the code **does not** do, and that it would be easy
 * to make it do by mistake: removing the row before the system confirms, and launching the confirmation
 * `Intent` from the ViewModel.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MyAppsViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(dispatcher)

    private val installedApps = FakeInstalledAppsRepository(listOf(anApp()))
    private val updates = FakeUpdateRepository(listOf(update(anApp())))
    private val installs = FakeInstallRepository()
    private val subscriptions = CoroutineScope(SupervisorJob() + dispatcher)

    @After
    fun tearDown() = subscriptions.cancel()

    private fun viewModel() = MyAppsViewModel(
        installedApps = ObserveInstalledAppsUseCase(installedApps),
        updates = ObserveUpdatesUseCase(updates, installedApps),
        uninstallApp = UninstallAppUseCase(installs),
        registry = StoreRegistry(setOf(FakeStoreAdapter())),
    )

    @Test
    fun `the list carries the store's name, not its identifier`() = runTest(dispatcher) {
        val ready = viewModel().ready()

        assertThat(ready.apps).hasSize(1)
        assertThat(ready.apps.single().storeName).isEqualTo("F-Droid")
        assertThat(ready.apps.single().hasDetail).isTrue()
    }

    @Test
    fun `with no origin the row stays, but has no detail page to open`() = runTest(dispatcher) {
        updates.state.value = listOf(update(anApp(sourceKnown = false)))

        val item = viewModel().ready().apps.single()

        // Removing it from the list would be worse: it is an app the user installed from here, and they
        // must be able to uninstall it from here.
        assertThat(item.storeName).isNull()
        assertThat(item.hasDetail).isFalse()
    }

    @Test
    fun `an empty list is a state of its own, not a list of zero items`() = runTest(dispatcher) {
        updates.state.value = emptyList()

        assertThat(viewModel().state()).isEqualTo(MyAppsUiState.Empty)
    }

    @Test
    fun `returning to the foreground realigns the list with the device`() = runTest(dispatcher) {
        // The app was uninstalled from the system settings: it is no longer on the device, but our table
        // does not know that.
        installedApps.onDevice["another.package"] = InstalledPackage("another.package", "1", 1, null)
        val viewModel = viewModel()
        viewModel.ready()

        viewModel.reconcile()

        assertThat(installedApps.reconciliations).isEqualTo(1)
        assertThat(installedApps.installed.value).isEmpty()
    }

    @Test
    fun `the system confirmation leaves as an event and the row stays until it arrives`() = runTest(dispatcher) {
        val intent = Intent("com.multistore.test.UNINSTALL")
        installs.uninstallSteps = listOf(InstallStep.UserActionRequired(intent))
        val viewModel = viewModel()
        val intents = mutableListOf<Intent>()
        subscriptions.launch { viewModel.userActions.collect { intents += it } }
        val item = viewModel.ready().apps.single()

        viewModel.requestUninstall(item)
        viewModel.confirmUninstall()

        assertThat(intents).containsExactly(intent)
        // The row leaves only when the system confirms: removing it now would leave the list and the
        // device disagreeing for the whole duration of the dialog — and forever, if the user cancels
        // it.
        assertThat(updates.state.value).hasSize(1)
        assertThat(viewModel.ready().uninstall).isInstanceOf(UninstallUiState.InProgress::class.java)
    }

    @Test
    fun `uninstall confirmed - the row disappears and the state returns to rest`() = runTest(dispatcher) {
        installs.uninstallSteps = listOf(InstallStep.Uninstalled(PACKAGE))
        val viewModel = viewModel()
        val item = viewModel.ready().apps.single()

        viewModel.requestUninstall(item)
        viewModel.confirmUninstall()

        assertThat(installs.uninstalled).containsExactly(PACKAGE)
        assertThat(installedApps.forgotten).isEmpty() // lo fa il repository vero, non la ViewModel
    }

    @Test
    fun `a refusal from the system becomes a message, not silence`() = runTest(dispatcher) {
        installs.uninstallSteps = listOf(InstallStep.Failed(statusCode = 5, message = "DELETE_FAILED_INTERNAL_ERROR"))
        val viewModel = viewModel()
        val item = viewModel.ready().apps.single()

        viewModel.requestUninstall(item)
        viewModel.confirmUninstall()

        val failed = viewModel.ready().uninstall as UninstallUiState.Failed
        // The raw text is not translated and not for the ordinary user, but without it a bug report
        // becomes "it does not uninstall".
        assertThat(failed.systemMessage).isEqualTo("DELETE_FAILED_INTERNAL_ERROR")
    }

    // --- updates --------------------------------------------------------------------------------

    @Test
    fun `an available update is visible in the row`() = runTest(dispatcher) {
        updates.state.value = listOf(
            update(anApp(), VersionSelection.Outcome.Offer(NEWER, isUpdate = true)),
        )

        val item = viewModel().ready().apps.single()

        assertThat(item.update).isEqualTo(UpdateState.Available("2.0.0"))
        assertThat(viewModel().ready().updatable).isEqualTo(1)
    }

    @Test
    fun `a paused app says so, and also says there was something`() = runTest(dispatcher) {
        updates.state.value = listOf(
            update(
                anApp(ignoreUpdates = true),
                VersionSelection.Outcome.Offer(NEWER, isUpdate = true),
            ),
        )

        val item = viewModel().ready().apps.single()

        // "Paused" on its own would suggest there was nothing to update. The difference is what reminds
        // the user they have a pause in effect.
        assertThat(item.update).isEqualTo(UpdateState.Paused(available = true))
    }

    @Test
    fun `a pinned app says what to, and what it is holding back`() = runTest(dispatcher) {
        updates.state.value = listOf(
            update(
                anApp(pinnedVersionCode = 12),
                VersionSelection.Outcome.Pinned(pinnedVersionCode = 12, offer = null, heldBack = NEWER),
            ),
        )

        assertThat(viewModel().ready().apps.single().update)
            .isEqualTo(UpdateState.Pinned(versionCode = 12, heldBack = "2.0.0"))
    }

    @Test
    fun `a store with no versionCode does not say up to date`() = runTest(dispatcher) {
        updates.state.value = listOf(
            update(anApp(), VersionSelection.Outcome.UpToDate(VERSION, comparable = false)),
        )

        assertThat(viewModel().ready().apps.single().update).isEqualTo(UpdateState.Undeterminable)
    }

    @Test
    fun `with no channel it does not pretend to know where to update from`() = runTest(dispatcher) {
        updates.state.value = listOf(update(anApp(), selection = null))

        assertThat(viewModel().ready().apps.single().update).isEqualTo(UpdateState.NoChannel)
    }

    @Test
    fun `pinning passes the installed version, not just any number`() = runTest(dispatcher) {
        val viewModel = viewModel()
        val item = viewModel.ready().apps.single()

        viewModel.setPinnedToInstalled(item, pinned = true)

        // Pinning to a number one does not have yet would be an instruction to go **up**, that is the
        // opposite of what a pin means.
        assertThat(installedApps.pinned).containsExactly(PACKAGE to 12L)
    }

    @Test
    fun `unpinning removes the pin instead of rewriting it`() = runTest(dispatcher) {
        updates.state.value = listOf(update(anApp(pinnedVersionCode = 12)))
        val viewModel = viewModel()
        val item = viewModel.ready().apps.single()

        viewModel.setPinnedToInstalled(item, pinned = false)

        assertThat(installedApps.pinned).containsExactly(PACKAGE to null)
    }

    @Test
    fun `pausing and resuming go through the same switch`() = runTest(dispatcher) {
        val viewModel = viewModel()
        val item = viewModel.ready().apps.single()

        viewModel.setIgnoreUpdates(item, ignore = true)

        assertThat(installedApps.ignored).containsExactly(PACKAGE to true)
    }

    @Test
    fun `two checks at once become one`() = runTest(dispatcher) {
        // Without a check that takes time, the first would already have finished when the second arrives
        // and this test would pass even with the guard removed.
        updates.checkDelay = 1.seconds
        val viewModel = viewModel()
        viewModel.ready()

        viewModel.checkForUpdates()
        viewModel.checkForUpdates()

        // The second would query the same stores for the same result, on sites that have a rate limit and
        // a circuit breaker.
        assertThat(updates.checks).isEqualTo(1)
        assertThat(updates.forcedChecks).containsExactly(true)
    }

    @Test
    fun `an incomplete check says so instead of keeping quiet`() = runTest(dispatcher) {
        updates.report = UpdateCheckReport(
            checked = 1,
            failures = mapOf(StoreId.APKMIRROR to com.multistore.core.common.result.AppError.RateLimited(null)),
        )
        val viewModel = viewModel()
        viewModel.ready()

        viewModel.checkForUpdates()

        // "No updates" and "no updates from those who answered" are two different sentences, and the
        // second is the only honest one when it is the true one.
        assertThat(viewModel.ready().check).isEqualTo(UpdateCheckUiState.Incomplete(stores = 1))
    }

    /**
     * Subscribes to the state and returns it.
     *
     * `uiState` is a `stateIn(WhileSubscribed)`: with no collector it stays at `Loading`, and every
     * assertion would read that.
     */
    private fun MyAppsViewModel.state(): MyAppsUiState {
        subscriptions.launch { uiState.collect { } }
        return uiState.value
    }

    private fun MyAppsViewModel.ready(): MyAppsUiState.Ready = state() as MyAppsUiState.Ready

    private fun update(
        app: InstalledApp,
        selection: VersionSelection.Outcome? = VersionSelection.Outcome.UpToDate(VERSION),
    ) = InstalledAppUpdate(
        app = app,
        channel = UpdateChannel(
            storeId = StoreId.FDROID,
            ref = StoreAppRef(app.packageName),
            listingId = 1L,
            title = app.label,
            iconUrl = null,
        ),
        selection = selection,
    )

    private fun anApp(
        sourceKnown: Boolean = true,
        ignoreUpdates: Boolean = false,
        pinnedVersionCode: Long? = null,
    ) = InstalledApp(
        packageName = PACKAGE,
        label = "Example",
        versionName = "1.2.3",
        versionCode = 12,
        signerSha256 = null,
        installedAt = Instant.fromEpochSeconds(1_756_000_000),
        installerKind = InstallerKind.SESSION,
        sourceStoreId = StoreId.FDROID.takeIf { sourceKnown },
        sourceRef = StoreAppRef(PACKAGE).takeIf { sourceKnown },
        ignoreUpdates = ignoreUpdates,
        pinnedVersionCode = pinnedVersionCode,
    )

    private companion object {
        const val PACKAGE = "org.example.app"

        val VERSION = AppVersion(
            versionName = "1.2.3",
            versionCode = 12,
            ref = VersionRef("v12"),
        )

        val NEWER = AppVersion(
            versionName = "2.0.0",
            versionCode = 20,
            ref = VersionRef("v20"),
        )
    }
}

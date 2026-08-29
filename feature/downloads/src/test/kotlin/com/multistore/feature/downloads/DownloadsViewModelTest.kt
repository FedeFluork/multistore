package com.multistore.feature.downloads

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.multistore.core.data.repository.DownloadStatus
import com.multistore.core.data.store.StoreRegistry
import com.multistore.core.domain.usecase.ActiveInstallDrivers
import com.multistore.core.domain.usecase.InstallAppUseCase
import com.multistore.core.domain.usecase.ResolveDownloadUseCase
import com.multistore.core.model.DownloadState
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.model.VersionRef
import com.multistore.core.testing.FakeAppDetailRepository
import com.multistore.core.testing.FakeDownloadRepository
import com.multistore.core.testing.FakeInstallRepository
import com.multistore.core.testing.FakeSettingsRepository
import com.multistore.core.testing.MainDispatcherRule
import java.io.File
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * The Downloads screen's state.
 *
 * What is worth testing here is the **grouping** and the two destructive gestures. The grouping is
 * not cosmetic: the three questions a row can answer — is it moving, is it waiting for me, is it
 * history — are answered by three different columns, and getting one wrong produces a screen that
 * offers Install over a file that is not there or hides one that is.
 */
class DownloadsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val downloads = FakeDownloadRepository()
    private val installs = FakeInstallRepository()
    private val settings = FakeSettingsRepository()
    private val details = FakeAppDetailRepository()

    private fun viewModel() = DownloadsViewModel(
        downloads = downloads,
        installApp = InstallAppUseCase(
            resolve = ResolveDownloadUseCase(StoreRegistry(emptySet()), details, settings),
            downloads = downloads,
            installs = installs,
            details = details,
            settings = settings,
            drivers = ActiveInstallDrivers(),
        ),
        registry = StoreRegistry(emptySet()),
    )

    @Test
    fun `with nothing downloaded the screen is empty, not loading forever`() = runTest {
        // `Empty` and not `Loading`: an empty list is an answer, and a screen that stayed on
        // "loading" for a user who has never downloaded anything would be waiting for something that
        // is never going to arrive.
        viewModel().uiState.test {
            assertThat(awaitItem()).isEqualTo(DownloadsUiState.Empty)
        }
    }

    @Test
    fun `the three groups answer three different questions`() = runTest {
        downloads.active.value = listOf(
            row(id = 1, state = DownloadState.RUNNING, title = "Firefox"),
            row(id = 2, state = DownloadState.READY, title = "F-Droid", file = File("2.apk")),
            row(id = 3, state = DownloadState.DONE, title = "Telegram", installedAt = AT),
            row(id = 4, state = DownloadState.FAILED, title = "Duolingo"),
        )

        val state = viewModel().uiState.awaitReady()

        assertThat(state.active.map { it.id }).containsExactly(1L)
        assertThat(state.readyToInstall.map { it.id }).containsExactly(2L)
        // Both terminal states are history, and the failed one belongs there just as much: "which
        // apps have I taken from where, and how did it go" is the question this list answers.
        assertThat(state.history.map { it.id }).containsExactly(3L, 4L).inOrder()
    }

    @Test
    fun `a ready row whose file is gone offers nothing, and is not called in progress`() = runTest {
        // The two facts are decided by two different columns — the state says the transfer ended,
        // the path says whether anything is left to install — and reading only the first gives an
        // Install button over nothing, while reading only the second files it under "In progress",
        // where nothing is going to progress.
        downloads.active.value = listOf(row(id = 1, state = DownloadState.READY, file = null))

        val state = viewModel().uiState.awaitReady()

        assertThat(state.readyToInstall).isEmpty()
        assertThat(state.active).isEmpty()
        assertThat(state.history.map { it.id }).containsExactly(1L)
    }

    @Test
    fun `a download outliving its listing still has a name`() = runTest {
        // A sync can delete the listing of a withdrawn package while the file is coming down. The
        // package is the next thing the user recognises; the store's own reference is nobody's name
        // and is the last resort.
        downloads.active.value = listOf(
            row(id = 1, state = DownloadState.DONE, title = null, packageName = "org.mozilla.firefox"),
            row(id = 2, state = DownloadState.DONE, title = null, packageName = null),
        )

        val history = viewModel().uiState.awaitReady().history

        assertThat(history.first { it.id == 1L }.title).isEqualTo("org.mozilla.firefox")
        assertThat(history.first { it.id == 2L }.title).isEqualTo("app-2")
    }

    @Test
    fun `deleting a staged file asks first`() = runTest {
        downloads.active.value =
            listOf(row(id = 1, state = DownloadState.READY, file = File("1.apk")))
        val viewModel = viewModel()
        val item = viewModel.uiState.awaitReady().readyToInstall.single()

        viewModel.requestDelete(item)

        // Nothing has happened yet: it is the only gesture in the app that throws away a whole,
        // verified file, and putting it back costs the transfer again.
        assertThat(downloads.deleted).isEmpty()
        assertThat(viewModel.confirmation.value).isInstanceOf(DownloadsConfirmation.Delete::class.java)

        viewModel.confirm()
        assertThat(downloads.deleted).containsExactly(1L)
        assertThat(viewModel.confirmation.value).isNull()
    }

    @Test
    fun `dismissing the question changes nothing`() = runTest {
        downloads.active.value =
            listOf(row(id = 1, state = DownloadState.READY, file = File("1.apk")))
        val viewModel = viewModel()
        val item = viewModel.uiState.awaitReady().readyToInstall.single()

        viewModel.requestDelete(item)
        viewModel.dismissConfirmation()
        viewModel.confirm()

        // `confirm` after a dismissal must not carry out what was dismissed: the pending question is
        // the state, and there is no longer one.
        assertThat(downloads.deleted).isEmpty()
    }

    @Test
    fun `emptying the history asks first too`() = runTest {
        downloads.active.value = listOf(row(id = 1, state = DownloadState.DONE, installedAt = AT))
        val viewModel = viewModel()
        viewModel.uiState.awaitReady()

        viewModel.requestClearHistory()
        assertThat(downloads.historyCleared).isEqualTo(0)

        viewModel.confirm()
        assertThat(downloads.historyCleared).isEqualTo(1)
    }

    @Test
    fun `each state offers only the gestures it can carry out`() = runTest {
        // The button table, read as one: a moving transfer can be stopped and nothing else; a parked
        // one has a partial file worth throwing away but nothing whole to install; a finished one
        // has both; history has neither. Getting any cell wrong puts a button over something that
        // cannot happen — Delete on a file the worker is writing, Install on a partial APK.
        downloads.active.value = listOf(
            row(id = 1, state = DownloadState.QUEUED),
            row(id = 2, state = DownloadState.RUNNING, file = File("2.part")),
            row(id = 3, state = DownloadState.PAUSED, file = File("3.part")),
            row(id = 4, state = DownloadState.READY, file = File("4.apk")),
            row(id = 5, state = DownloadState.INSTALLING, file = File("5.apk")),
            row(id = 6, state = DownloadState.DONE, installedAt = AT),
        )

        val byId = viewModel().uiState.awaitReady()
            .let { it.active + it.readyToInstall + it.history }
            .associateBy { it.id }

        assertThat(byId.filterValues { it.cancellable }.keys).containsExactly(1L, 2L)
        assertThat(byId.filterValues { it.deletable }.keys).containsExactly(3L, 4L)
        assertThat(byId.filterValues { it.readyToInstall }.keys).containsExactly(4L)
        // `INSTALLING` offers nothing: a `PackageInstaller` session is reading that file, and both
        // gestures would pull it out from under it.
        val installing = byId.getValue(5L)
        assertThat(installing.cancellable || installing.deletable).isFalse()
    }

    @Test
    fun `cancelling stops the worker and leaves something to delete`() = runTest {
        downloads.active.value =
            listOf(row(id = 1, state = DownloadState.RUNNING, file = File("1.part")))
        val viewModel = viewModel()
        val item = viewModel.uiState.awaitReady().active.single()

        viewModel.cancel(item)

        // Through the repository, not by dropping a coroutine: the transfer lives in a worker, so
        // anything short of telling it would leave it running with a notification nobody can
        // dismiss. And no confirmation in front — nothing is destroyed, the partial file stays.
        assertThat(downloads.cancelled).containsExactly(1L)
        assertThat(viewModel.confirmation.value).isNull()
        assertThat(downloads.deleted).isEmpty()
    }

    @Test
    fun `a cancelled transfer is not a dead end`() = runTest {
        // What cancelling leaves is a `PAUSED` row with a partial file, and this screen cannot
        // restart a transfer — only the app's page can. Without a way to throw that file away the
        // row would sit here for good, so the Delete this asserts is the other half of the button
        // above: remove it and cancelling becomes a one-way door.
        downloads.active.value =
            listOf(row(id = 1, state = DownloadState.PAUSED, file = File("1.part")))
        val viewModel = viewModel()
        val item = viewModel.uiState.awaitReady().active.single()

        assertThat(item.deletable).isTrue()
        viewModel.requestDelete(item)
        viewModel.confirm()

        assertThat(downloads.deleted).containsExactly(1L)
    }

    @Test
    fun `installing a row goes through the shared path and reports its failure on that row`() =
        runTest {
            downloads.active.value = listOf(
                row(id = 1, state = DownloadState.READY, file = File("1.apk")),
                row(id = 2, state = DownloadState.READY, file = File("2.apk")),
            )
            val viewModel = viewModel()
            val first = viewModel.uiState.awaitReady().readyToInstall.first { it.id == 1L }

            // No listing on disk: `resume` cannot know which version this row was downloading, and
            // says so. What matters here is **where** it says it — on the row that was pressed, and
            // not on the other one, which two simultaneous installations would make indistinguishable
            // with a single screen-wide flag.
            viewModel.install(first)

            val after = viewModel.uiState.awaitReady().readyToInstall
            assertThat(after.first { it.id == 1L }.install)
                .isInstanceOf(RowInstallState.Failed::class.java)
            assertThat(after.first { it.id == 2L }.install).isEqualTo(RowInstallState.Idle)
        }

    // --- infrastructure ---------------------------------------------------------------------

    private suspend fun kotlinx.coroutines.flow.StateFlow<DownloadsUiState>.awaitReady(): DownloadsUiState.Ready {
        var ready: DownloadsUiState.Ready? = null
        test {
            while (true) {
                val item = awaitItem()
                if (item is DownloadsUiState.Ready) {
                    ready = item
                    break
                }
            }
            cancelAndIgnoreRemainingEvents()
        }
        return requireNotNull(ready)
    }

    private fun row(
        id: Long,
        state: DownloadState,
        title: String? = "App $id",
        packageName: String? = "org.example.app$id",
        file: File? = null,
        installedAt: Instant? = null,
    ) = DownloadStatus(
        id = id,
        storeId = StoreId.FDROID,
        ref = StoreAppRef("app-$id"),
        versionRef = VersionRef("v1"),
        packageName = packageName,
        state = state,
        bytesDownloaded = 50,
        bytesTotal = 100,
        file = file,
        error = null,
        title = title,
        installedAt = installedAt,
        createdAt = AT,
        updatedAt = AT,
    )

    private companion object {
        val AT: Instant = Instant.fromEpochMilliseconds(1_700_000_000_000)
    }
}

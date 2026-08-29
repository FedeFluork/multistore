package com.multistore.app

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.multistore.core.data.repository.DownloadStatus
import com.multistore.core.model.DownloadState
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.model.VersionRef
import com.multistore.core.testing.FakeDownloadRepository
import com.multistore.core.testing.MainDispatcherRule
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * The shell's view of the downloads: the progress card, and the dot on the bar.
 *
 * The two are deliberately **not** the same state, and that is most of what is worth testing here.
 * They also fail in opposite ways: a card that would not go away is a nuisance, a dot that goes away
 * with it is the app going quiet exactly when the user has stopped watching.
 */
class DownloadOverlayViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val downloads = FakeDownloadRepository()

    private fun viewModel() = DownloadOverlayViewModel(downloads)

    @Test
    fun `hiding the card does not clear the badge`() = runTest {
        downloads.active.value = listOf(row(id = 1, state = DownloadState.RUNNING))
        val viewModel = viewModel()
        viewModel.badge.test {
            // Already `true` on the first item, not the `initialValue`: the unconfined dispatcher
            // starts the collection immediately, so `stateIn` has the row before anybody reads it.
            assertThat(awaitItem()).isTrue()

            viewModel.visible.test { awaitItem() } // subscribe, so `dismiss` has something to hide
            viewModel.dismiss()

            // The struck-through eye says "get this out of my way", not "forget this download". A
            // badge filtered by the hidden set would turn the one gesture into the other, and would
            // leave nothing at all saying a transfer is still running.
            expectNoEvents()
            assertThat(viewModel.badge.value).isTrue()
        }
    }

    @Test
    fun `a paused transfer does not wear the dot`() = runTest {
        // Somebody stopped it, on this screen or on the app's page. A dot for it would be a notice
        // about a decision already taken — and one the user cannot clear from the tab it sits on.
        downloads.active.value =
            listOf(row(id = 1, state = DownloadState.PAUSED, file = File("1.part")))

        viewModel().badge.test {
            assertThat(awaitItem()).isFalse()
            expectNoEvents()
        }
    }

    @Test
    fun `a ready row wears the dot only while its file is really there`() = runTest {
        // A row that says ready with nothing on disk should not exist: every deletion path closes
        // the row. If the two ever disagreed the badge would be the symptom — a dot nobody can
        // clear, on a tab where nothing explains it — so it reads the file and not the state alone.
        downloads.active.value = listOf(row(id = 1, state = DownloadState.READY, file = null))

        viewModel().badge.test {
            assertThat(awaitItem()).isFalse()

            downloads.active.value =
                listOf(row(id = 1, state = DownloadState.READY, file = File("1.apk")))
            assertThat(awaitItem()).isTrue()
        }
    }

    @Test
    fun `the dot goes when the last transfer ends`() = runTest {
        downloads.active.value = listOf(row(id = 1, state = DownloadState.RUNNING))
        val viewModel = viewModel()

        viewModel.badge.test {
            assertThat(awaitItem()).isTrue()

            // `observeActive` excludes the terminal states, so an installed download simply leaves
            // the list: there is nothing left to be told about.
            downloads.active.value = emptyList()
            assertThat(awaitItem()).isFalse()
        }
    }

    @Test
    fun `a new download brings the card back after it was hidden`() = runTest {
        downloads.active.value = listOf(row(id = 1, state = DownloadState.RUNNING))
        val viewModel = viewModel()

        viewModel.visible.test {
            assertThat(awaitItem()).hasSize(1)

            viewModel.dismiss()
            assertThat(awaitItem()).isEmpty()

            // The hidden set is kept by **id** for exactly this: the count would be back to one and
            // a flag would keep the card down, but this is a different download.
            downloads.active.value = listOf(row(id = 2, state = DownloadState.RUNNING))

            // Where it settles, not the very next emission: pruning the hidden set is a second
            // collector on the same flow, so between the two resumptions `combine` emits once with
            // the list it had before. Harmless — it is one frame, and Compose coalesces it — but it
            // means `awaitItem()` here would be reading a value nobody ever sees.
            assertThat(viewModel.visible.value.map { it.id }).containsExactly(2L)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun row(
        id: Long,
        state: DownloadState,
        file: File? = null,
    ) = DownloadStatus(
        id = id,
        storeId = StoreId.FDROID,
        ref = StoreAppRef("app-$id"),
        versionRef = VersionRef("v1"),
        packageName = "org.example.app$id",
        state = state,
        bytesDownloaded = 50,
        bytesTotal = 100,
        file = file,
        error = null,
        title = "App $id",
    )
}

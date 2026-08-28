package com.multistore.core.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The worker that carries a download through, outside the screen that started it.
 *
 * What is worth testing here is not the transfer — that has its own thirteen tests against a fake
 * server — but the **contract with WorkManager**: that the id arrives, that a missing id does not
 * become an infinite retry, and above all that an interrupted transfer answers `failure` and not
 * `retry`. That last one is the difference between "the user will resume if they want to" and "the
 * system will retry by itself, perhaps on mobile data".
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DownloadWorkerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private class FakeTask(private val succeeds: Boolean) : DownloadTask {
        var transferred = mutableListOf<Long>()
        override suspend fun label(id: Long): String? = "Example"
        override suspend fun transfer(id: Long): Boolean {
            transferred += id
            return succeeds
        }

        override fun observeProgress(id: Long): Flow<DownloadProgress?> =
            flowOf(DownloadProgress(bytesDownloaded = 10, bytesTotal = 100, terminal = false))
    }

    private fun worker(task: DownloadTask, id: Long?): DownloadWorker {
        val builder = TestListenableWorkerBuilder<DownloadWorker>(context)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker =
                        DownloadWorker(appContext, workerParameters, task, DownloadNotifications(appContext))
                },
            )
        if (id != null) {
            builder.setInputData(Data.Builder().putLong(DownloadWorker.KEY_DOWNLOAD_ID, id).build())
        }
        return builder.build()
    }

    @Test
    fun `successful transfer - the worker reports success`() = runTest {
        val task = FakeTask(succeeds = true)

        val result = worker(task, DOWNLOAD_ID).doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        assertThat(task.transferred).containsExactly(DOWNLOAD_ID)
    }

    @Test
    fun `interrupted transfer - failure and not retry`() = runTest {
        val task = FakeTask(succeeds = false)

        val result = worker(task, DOWNLOAD_ID).doWork()

        // `retry()` would make WorkManager retry with its backoff, in the background and possibly on a
        // metered network, a download the user may no longer want. The row stays in `PAUSED` with the
        // bytes already taken: resuming it is their gesture, or `requeueInterrupted()` at the next
        // launch.
        assertThat(result).isEqualTo(ListenableWorker.Result.failure())
    }

    @Test
    fun `with no id there is nothing to download`() = runTest {
        val task = FakeTask(succeeds = true)

        val result = worker(task, id = null).doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.failure())
        assertThat(task.transferred).isEmpty()
    }

    private companion object {
        const val DOWNLOAD_ID = 7L
    }
}

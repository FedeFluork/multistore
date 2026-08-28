package com.multistore.core.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.multistore.core.data.FakeIndexedStoreAdapter
import com.multistore.core.data.store.RequestLogRecorder
import com.multistore.core.data.store.StoreRegistry
import com.multistore.core.database.MultiStoreDatabase
import com.multistore.core.model.InstallerAvailability
import com.multistore.core.model.InstallerKind
import com.multistore.core.model.StoreId
import com.multistore.core.model.OwnPackage
import com.multistore.core.remoteconfig.FetchAttempt
import com.multistore.core.remoteconfig.RemoteConfigStatus
import javax.inject.Provider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The request log and the report that pulls it out.
 *
 * Two things are proven here and not elsewhere because here is everything needed to demonstrate them:
 * that **the switch really commands** — a function ignoring its own switch is exactly how a setting
 * becomes decorative — and that the report holds up in the case where there is nothing to tell, which
 * is the case of somebody who has just installed the app and the only one in which it is read without
 * knowing what to expect.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DiagnosticsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: MultiStoreDatabase

    private val clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(1_787_000_000_000L)
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, MultiStoreDatabase::class.java)
            // Room's executors are pinned to the calling thread, and without that this test is
            // flaky rather than wrong. `record` launches into a scope on virtual time, but the
            // write leaves it for Room's own thread pool: `advanceUntilIdle` sees nothing pending,
            // returns, and `recentEvents` reads a table that has not been written yet. The real
            // thread usually wins the race, so it fails only under load — during a full `build`,
            // and never when the test is re-run on its own, which is the shape that sends you
            // looking at the wrong change.
            .setQueryExecutor(Runnable::run)
            .setTransactionExecutor(Runnable::run)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    private fun health(): StoreHealthRepositoryImpl = StoreHealthRepositoryImpl(
        StoreRegistry(setOf(FakeIndexedStoreAdapter(StoreId.FDROID))),
        db.storeDao(),
        clock,
        Dispatchers.Unconfined,
    )

    private fun TestScope.recorder(
        settings: SettingsRepository,
        healthRepository: StoreHealthRepository,
    ) = RequestLogRecorder(
        health = Provider { healthRepository },
        settings = Provider { settings },
        scope = TestScope(UnconfinedTestDispatcher(testScheduler)),
    )

    @Test
    fun `with the switch off nothing is recorded`() = runTest {
        val healthRepository = health()
        val recorder = recorder(LocalSettings(logRequests = false), healthRepository)

        recorder.record(StoreId.FDROID, "GET", "https://f-droid.org/index-v2.json", 200, 120.milliseconds)
        testScheduler.advanceUntilIdle()

        // The fault log stays as it always was. The cost of the switch being off is a flow read, not
        // an extra row in the database.
        assertThat(healthRepository.recentEvents()).isEmpty()
    }

    @Test
    fun `with the switch on the request ends up in the log, whole`() = runTest {
        val healthRepository = health()
        val recorder = recorder(LocalSettings(logRequests = true), healthRepository)

        recorder.record(StoreId.PDALIFE, "GET", "https://pdalife.com/search/telegram/", 200, 812.milliseconds)
        testScheduler.advanceUntilIdle()

        val event = healthRepository.recentEvents().single()
        assertThat(event.storeId).isEqualTo(StoreId.PDALIFE)
        assertThat(event.kind).isEqualTo("request")
        // The whole address, code included: a row saying only "pdalife answered" would answer none of
        // the questions the log is switched on for.
        assertThat(event.detail).isEqualTo("GET https://pdalife.com/search/telegram/ → 200")
        assertThat(event.durationMillis).isEqualTo(812)
    }

    @Test
    fun `the report describes the sections even when nothing has happened`() = runTest {
        val healthRepository = health()
        val report = DiagnosticsRepositoryImpl(
            context = context,
            health = healthRepository,
            installs = NoInstalls(
                InstallerAvailability(
                    supported = setOf(InstallerKind.SESSION),
                    usable = setOf(InstallerKind.SESSION),
                ),
            ),
            config = NoRemoteConfig(),
            settings = LocalSettings(),
            ownPackage = OwnPackage(context.packageName),
            clock = clock,
        ).report()

        // The sections are all there even with an empty database: it is the case of somebody who has
        // just installed the app, and it is the only one in which the report is read without knowing
        // what to expect.
        assertThat(report).contains("--- app")
        assertThat(report).contains("--- device")
        assertThat(report).contains("--- installers")
        assertThat(report).contains("--- stores")
        assertThat(report).contains("--- remoteConfig")
        assertThat(report).contains("--- settings")
        // "none recorded" and not a missing section: without this line the reader does not know
        // whether the log is empty or the report forgot to write it.
        assertThat(report).contains("none recorded")
        assertThat(report).contains("active: compiled defaults")
    }

    @Test
    fun `the report carries the recorded events, newest first`() = runTest {
        val healthRepository = health()
        healthRepository.recordEvent(StoreId.FDROID, kind = "index_stale")
        healthRepository.recordEvent(
            storeId = StoreId.PDALIFE,
            kind = "request",
            detail = "GET https://pdalife.com/search/telegram/ → 200",
            durationMillis = 812,
        )

        val report = DiagnosticsRepositoryImpl(
            context = context,
            health = healthRepository,
            installs = NoInstalls(),
            config = NoRemoteConfig(),
            settings = LocalSettings(),
            ownPackage = OwnPackage(context.packageName),
            clock = clock,
        ).report()

        assertThat(report).contains("events (2, newest first)")
        assertThat(report).contains("index_stale")
        assertThat(report).contains("812ms")
    }

    /** A device offering only the system confirmation, and installing nothing. */
    private class NoInstalls(
        private val availability: InstallerAvailability = InstallerAvailability(),
    ) : InstallRepository {
        override fun install(plan: InstallPlan): Flow<InstallStep> = flowOf()
        override suspend fun installerAvailability(): InstallerAvailability = availability
        override suspend fun requestInstallerPermission(kind: InstallerKind): Boolean = false
        override fun uninstall(packageName: String): Flow<InstallStep> = flowOf()
        override suspend fun reconcileAbandonedSessions(): Int = 0
    }

    /** No document downloaded: the compiled defaults, which is the state at first launch. */
    private class NoRemoteConfig : RemoteConfigRepository {
        override val status: Flow<RemoteConfigStatus> = flowOf(RemoteConfigStatus())
        override suspend fun refreshIfStale(): FetchAttempt? = null
        override suspend fun refreshNow(): FetchAttempt? = null
    }
}

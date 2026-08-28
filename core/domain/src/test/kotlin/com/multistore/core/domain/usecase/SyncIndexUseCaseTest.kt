package com.multistore.core.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.multistore.core.common.result.AppError
import com.multistore.core.common.result.Outcome
import com.multistore.core.data.repository.IndexState
import com.multistore.core.data.repository.IndexSyncProgress
import com.multistore.core.data.repository.IndexSyncReport
import com.multistore.core.data.repository.SettingsRepository
import com.multistore.core.data.repository.StoreIndexRepository
import com.multistore.core.data.repository.StoreTaxonomy
import com.multistore.core.model.AppearanceSettings
import com.multistore.core.model.InstallSettings
import com.multistore.core.model.InstallerPreference
import com.multistore.core.model.NetworkSettings
import com.multistore.core.model.SecuritySettings
import com.multistore.core.model.StoreId
import com.multistore.core.model.ThemeMode
import com.multistore.core.model.UpdateInterval
import com.multistore.core.model.UpdateSettings
import com.multistore.store.api.IndexSyncMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The user's decision about metered traffic, made executable.
 *
 * "Automatic if the network is not metered, ask if it is": the case making the test necessary is the
 * third, i.e. that a consent given **once** does not become a permanent setting. Downloading 18 MB
 * now is not consenting to do it again every week, and the difference between the two is not visible
 * by reading the code — it is visible on the bill.
 */
class SyncIndexUseCaseTest {

    private val store = StoreId.FDROID

    private class FakeIndexRepository : StoreIndexRepository {
        var syncs = 0
        var lastForce: Boolean? = null
        var result: Outcome<IndexSyncReport> = Outcome.Success(
            IndexSyncReport(StoreId.FDROID, IndexSyncMode.FULL, 1, 0, "1", upToDate = false),
        )

        override fun observeState(storeId: StoreId): Flow<IndexState?> = flowOf(null)
        override suspend fun state(storeId: StoreId): IndexState? = null
        override fun observeTaxonomy(storeId: StoreId): Flow<StoreTaxonomy> = flowOf(StoreTaxonomy())
        override suspend fun taxonomy(storeId: StoreId): StoreTaxonomy = StoreTaxonomy()

        override suspend fun sync(
            storeId: StoreId,
            force: Boolean,
            onProgress: (IndexSyncProgress) -> Unit,
        ): Outcome<IndexSyncReport> {
            syncs++
            lastForce = force
            return result
        }
    }

    private fun useCase(
        index: FakeIndexRepository = FakeIndexRepository(),
        meteredAllowed: Boolean = false,
        onMeteredNetwork: Boolean = false,
    ) = SyncIndexUseCase(index, DomainSettings(metered = meteredAllowed)) { onMeteredNetwork }

    @Test
    fun `non-metered network - it starts by itself`() = runTest {
        val index = FakeIndexRepository()

        val outcome = useCase(index, onMeteredNetwork = false)(store)

        assertThat(outcome).isInstanceOf(SyncRequestOutcome.Completed::class.java)
        assertThat(index.syncs).isEqualTo(1)
    }

    @Test
    fun `metered network - it stops and asks, downloading nothing`() = runTest {
        val index = FakeIndexRepository()

        val outcome = useCase(index, onMeteredNetwork = true)(store)

        assertThat(outcome).isInstanceOf(SyncRequestOutcome.NeedsMeteredConsent::class.java)
        // The point: it is not that it fails after trying, it is that it does not try.
        assertThat(index.syncs).isEqualTo(0)
    }

    @Test
    fun `metered network with the user's consent - it starts this time and no more`() = runTest {
        val index = FakeIndexRepository()
        val sync = useCase(index, onMeteredNetwork = true)

        assertThat(sync(store, userConsented = true))
            .isInstanceOf(SyncRequestOutcome.Completed::class.java)
        // The next call starts asking again: the consent was for that sync, not forever. Storing it
        // would be changing a user setting on their behalf.
        assertThat(sync(store)).isInstanceOf(SyncRequestOutcome.NeedsMeteredConsent::class.java)
        assertThat(index.syncs).isEqualTo(1)
    }

    @Test
    fun `setting on - a metered network no longer stops anything`() = runTest {
        val index = FakeIndexRepository()

        val outcome = useCase(index, meteredAllowed = true, onMeteredNetwork = true)(store)

        assertThat(outcome).isInstanceOf(SyncRequestOutcome.Completed::class.java)
    }

    @Test
    fun `a repository error stays an error, not a consent request`() = runTest {
        val index = FakeIndexRepository().apply {
            result = Outcome.Failure(AppError.Network(null))
        }

        val outcome = useCase(index)(store)

        assertThat(outcome).isInstanceOf(SyncRequestOutcome.Failed::class.java)
    }

    @Test
    fun `force reaches the repository`() = runTest {
        val index = FakeIndexRepository()

        useCase(index)(store, force = true)

        assertThat(index.lastForce).isTrue()
    }
}

package com.multistore.core.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.multistore.core.common.result.AppError
import com.multistore.core.common.result.Outcome
import com.multistore.core.common.version.VersionSelection
import com.multistore.core.data.repository.AppDetail
import com.multistore.core.data.repository.AppDetailRepository
import com.multistore.core.data.store.StoreRegistry
import com.multistore.core.model.AppVersion
import com.multistore.core.model.ArtifactType
import com.multistore.core.model.ContentKind
import com.multistore.core.model.Sha256
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.model.StoreListingDetail
import com.multistore.core.model.StoreListingSummary
import com.multistore.core.model.VersionRef
import com.multistore.store.api.DownloadHint
import com.multistore.store.api.DownloadMode
import com.multistore.store.api.DownloadResolution
import com.multistore.store.api.HashAvailability
import com.multistore.store.api.NetworkTier
import com.multistore.store.api.PagedResult
import com.multistore.store.api.SearchFilters
import com.multistore.store.api.StoreAdapter
import com.multistore.store.api.StoreCapabilities
import com.multistore.store.api.StoreError
import com.multistore.store.api.StoreMetadata
import com.multistore.store.api.StoreResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The download's resolution: which version, and how to reach it.
 *
 * Two of the four outcomes are what makes this class useful, and neither is an error: the
 * **signature conflict**, whose answer is a precise action rather than a message, and the
 * **preflight**, which exists because on one of the nine stores about half the binaries answer 500
 * and discovering it after the tap is worse than discovering it before.
 */
class ResolveDownloadUseCaseTest {

    private val store = StoreId.FDROID
    private val ref = StoreAppRef("org.example.app")

    private class FakeAdapter(
        override val id: StoreId,
        private val link: StoreResult<DownloadResolution>,
        private val reachable: Boolean = true,
        downloadMode: DownloadMode = DownloadMode.DIRECT,
    ) : StoreAdapter {
        var preflights = 0
        var linkRequests = 0

        override val metadata = StoreMetadata("Fake", "https://fake.test", "en-US", "fake.test")
        override val capabilities = StoreCapabilities(
            search = true, trending = false, recent = false, versionHistory = true,
            providesPackageName = true, providesRating = false, providesScreenshots = false,
            providesChangelog = false, providesHash = HashAvailability.ALWAYS,
            providesSignerFingerprint = true, supportsSplits = false,
            downloadMode = downloadMode, networkTier = NetworkTier.OKHTTP,
            userAgent = "MultiStoreTest/1.0", supportedFilters = emptySet(),
            contentKinds = setOf(ContentKind.APP),
        )

        override suspend fun search(query: String, filters: SearchFilters, page: Int) =
            StoreResult.Success(PagedResult.empty<StoreListingSummary>())

        override suspend fun getAppDetails(ref: StoreAppRef) = StoreResult.Unsupported

        override suspend fun getDownloadLink(ref: StoreAppRef, version: VersionRef?): StoreResult<DownloadResolution> {
            linkRequests++
            return link
        }
        override suspend fun healthCheck() = StoreResult.Success(Unit)

        override suspend fun preflight(resolution: DownloadResolution): StoreResult<Boolean> {
            preflights++
            return StoreResult.Success(reachable)
        }
    }

    private class FakeDetails(private val detail: AppDetail?) : AppDetailRepository {
        override fun observe(storeId: StoreId, ref: StoreAppRef): Flow<AppDetail?> = flowOf(detail)
        override suspend fun detail(storeId: StoreId, ref: StoreAppRef): AppDetail? = detail

        override suspend fun loadVersionHistory(
            storeId: StoreId,
            ref: StoreAppRef,
        ): Outcome<Unit> = Outcome.Success(Unit)

        override suspend fun refresh(storeId: StoreId, ref: StoreAppRef, force: Boolean) =
            Outcome.Success(Unit)
    }

    private fun version(code: Long, signer: Sha256? = null) = AppVersion(
        versionName = "1.$code",
        versionCode = code,
        ref = VersionRef("v$code"),
        artifactType = ArtifactType.APK,
        signerSha256 = signer,
    )

    private fun detail(selection: VersionSelection.Outcome, versions: List<AppVersion>) = AppDetail(
        listing = StoreListingDetail(
            summary = StoreListingSummary(
                storeId = store,
                ref = ref,
                title = "Example",
                packageName = ref.value,
            ),
            versions = versions,
        ),
        installed = null,
        selection = selection,
        stale = false,
    )

    private val assisted = DownloadResolution.UserAssisted(
        pageUrl = "https://fake.test/app/download",
        hint = DownloadHint.CHOOSE_A_MIRROR,
    )

    private val direct = DownloadResolution.Direct(
        url = "https://fake.test/app.apk",
        headers = emptyMap(),
        fileName = "app.apk",
        artifactType = ArtifactType.APK,
        expectedSha256 = null,
        expectedSize = 1234L,
    )

    private fun useCase(
        adapter: FakeAdapter,
        detail: AppDetail?,
        settings: DomainSettings = DomainSettings(),
    ) = ResolveDownloadUseCase(StoreRegistry(setOf(adapter)), FakeDetails(detail), settings)

    @Test
    fun `version offered and link resolved - it downloads`() = runTest {
        val offered = version(10)
        val adapter = FakeAdapter(store, StoreResult.Success(direct))

        val resolved = useCase(
            adapter,
            detail(VersionSelection.Outcome.Offer(offered, isUpdate = false), listOf(offered)),
        )(store, ref)

        val outcome = resolved as ResolvedDownload.Direct
        assertThat(outcome.version.versionCode).isEqualTo(10)
        assertThat(outcome.resolution.url).isEqualTo(direct.url)
    }

    @Test
    fun `signature conflict - it is not an error, it is a choice to offer`() = runTest {
        val available = listOf(version(10, Sha256.parseOrNull("aa".repeat(32))))
        val adapter = FakeAdapter(store, StoreResult.Success(direct))

        val resolved = useCase(
            adapter,
            detail(VersionSelection.Outcome.SignerConflict(available), available),
        )(store, ref)

        // Presenting it as `Unavailable` would hide the only possible action — uninstall and
        // reinstall, losing the data — and leave the user with a greyed-out button for no reason.
        assertThat(resolved).isInstanceOf(ResolvedDownload.SignerConflict::class.java)
        // And the link of a version that will not be installed is not requested.
        assertThat(adapter.preflights).isEqualTo(0)
    }

    @Test
    fun `no version runs on this device - an outcome of its own, not a generic error`() = runTest {
        val adapter = FakeAdapter(store, StoreResult.Success(direct))

        val resolved = useCase(
            adapter,
            detail(VersionSelection.Outcome.Incompatible, emptyList()),
        )(store, ref)

        assertThat(resolved).isEqualTo(ResolvedDownload.Incompatible)
    }

    @Test
    fun `dead binary - the preflight catches it before the tap`() = runTest {
        val offered = version(10)
        val adapter = FakeAdapter(store, StoreResult.Success(direct), reachable = false)

        val resolved = useCase(
            adapter,
            detail(VersionSelection.Outcome.Offer(offered, isUpdate = false), listOf(offered)),
        )(store, ref)

        assertThat(adapter.preflights).isEqualTo(1)
        assertThat(resolved).isInstanceOf(ResolvedDownload.Unavailable::class.java)
    }

    @Test
    fun `a version chosen by hand overrides the rule`() = runTest {
        val offered = version(10)
        val older = version(3)
        val adapter = FakeAdapter(store, StoreResult.Success(direct))

        val resolved = useCase(
            adapter,
            detail(VersionSelection.Outcome.Offer(offered, isUpdate = false), listOf(offered, older)),
        )(store, ref, explicit = older)

        assertThat((resolved as ResolvedDownload.Direct).version.versionCode).isEqualTo(3)
    }

    @Test
    fun `the store answers badly - a translated error, not an exception`() = runTest {
        val offered = version(10)
        val adapter = FakeAdapter(store, StoreResult.Failure(StoreError.NotFound))

        val resolved = useCase(
            adapter,
            detail(VersionSelection.Outcome.Offer(offered, isUpdate = false), listOf(offered)),
        )(store, ref)

        assertThat(resolved).isInstanceOf(ResolvedDownload.Unavailable::class.java)
    }

    @Test
    fun `a listing we do not have - nothing is invented`() = runTest {
        val adapter = FakeAdapter(store, StoreResult.Success(direct))

        assertThat(useCase(adapter, detail = null)(store, ref))
            .isInstanceOf(ResolvedDownload.Unavailable::class.java)
    }

    @Test
    fun `assisted path off - it is said, and nothing is asked of the store`() = runTest {
        val offered = version(10)
        val adapter = FakeAdapter(
            store,
            StoreResult.Success(assisted),
            downloadMode = DownloadMode.USER_ASSISTED_ONLY,
        )

        val resolved = useCase(
            adapter,
            detail(VersionSelection.Outcome.Offer(offered, isUpdate = false), listOf(offered)),
            settings = DomainSettings(blockUserAssistedChallenge = true),
        )(store, ref)

        // An error of **its own**, not a generic `Blocked`: "the store is barring our way" cannot be
        // solved anywhere, this one is solved with a switch, and without saying so the setting would
        // make a button disappear with no explanation.
        assertThat((resolved as ResolvedDownload.Unavailable).error)
            .isEqualTo(AppError.UserAssistanceDisabled)
        // A store that can only produce that outcome must not be queried to be told so.
        assertThat(adapter.linkRequests).isEqualTo(0)
    }

    @Test
    fun `assisted path on - it is the default, and it passes`() = runTest {
        val offered = version(10)
        val adapter = FakeAdapter(
            store,
            StoreResult.Success(assisted),
            downloadMode = DownloadMode.USER_ASSISTED_ONLY,
        )

        val resolved = useCase(
            adapter,
            detail(VersionSelection.Outcome.Offer(offered, isUpdate = false), listOf(offered)),
        )(store, ref)

        // Proto3's zero value has to be this one: `allow_user_assisted_challenge` "default true",
        // written at the positive, would have started **off** — with uptodown's and pdalife's downloads
        // inaccessible to whoever does not open Settings.
        assertThat(resolved).isInstanceOf(ResolvedDownload.UserAssisted::class.java)
    }

    @Test
    fun `a direct store resolving to assisted still respects the switch`() = runTest {
        val offered = version(10)
        // `DIRECT_WITH_FALLBACK`: the capability is not enough to predict the outcome, so the early
        // exit does not fire and the decision is left to the check on the real branch.
        val adapter = FakeAdapter(
            store,
            StoreResult.Success(assisted),
            downloadMode = DownloadMode.DIRECT_WITH_FALLBACK,
        )

        val resolved = useCase(
            adapter,
            detail(VersionSelection.Outcome.Offer(offered, isUpdate = false), listOf(offered)),
            settings = DomainSettings(blockUserAssistedChallenge = true),
        )(store, ref)

        assertThat(adapter.linkRequests).isEqualTo(1)
        assertThat((resolved as ResolvedDownload.Unavailable).error)
            .isEqualTo(AppError.UserAssistanceDisabled)
    }
}

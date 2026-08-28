package com.multistore.core.testing

import com.multistore.core.model.AppVersion
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.model.StoreListingDetail
import com.multistore.core.model.StoreListingSummary
import com.multistore.core.model.VersionRef
import com.multistore.store.api.DownloadMode
import com.multistore.store.api.DownloadResolution
import com.multistore.store.api.HashAvailability
import com.multistore.store.api.IndexToken
import com.multistore.store.api.IndexedStoreAdapter
import com.multistore.store.api.NetworkTier
import com.multistore.store.api.PagedResult
import com.multistore.store.api.SearchFilters
import com.multistore.store.api.SearchSource
import com.multistore.store.api.StoreAdapter
import com.multistore.store.api.StoreCapabilities
import com.multistore.store.api.StoreCatalogInfo
import com.multistore.store.api.StoreIndexSnapshot
import com.multistore.store.api.StoreMetadata
import com.multistore.store.api.StoreResult

/**
 * An adapter that talks to nobody.
 *
 * It serves whoever has to build a `StoreRegistry` in a test without dragging in a real adapter: the
 * ViewModels ask the registry two things only — who publishes a local index, and what a store is
 * called — and neither needs the network.
 *
 * It lives in `:core:testing` and not in each module because otherwise there would be seven copies:
 * it is the same reason [ScreenshotTest] lives there. The module is consumed only via
 * `testImplementation`, so none of this ends up in the APK.
 *
 * Every operation answers [StoreResult.Unsupported]: **a test reaching one of them without having
 * redefined it is testing something it did not mean to**, and it is better it notices by reading an
 * unexpected result than by receiving a fake success.
 */
open class FakeStoreAdapter(
    override val id: StoreId = StoreId.FDROID,
    override val metadata: StoreMetadata = fakeStoreMetadata(),
    override val capabilities: StoreCapabilities = fakeStoreCapabilities(),
) : StoreAdapter {

    override suspend fun search(
        query: String,
        filters: SearchFilters,
        page: Int,
    ): StoreResult<PagedResult<StoreListingSummary>> = StoreResult.Unsupported

    override suspend fun getAppDetails(ref: StoreAppRef): StoreResult<StoreListingDetail> =
        StoreResult.Unsupported

    override suspend fun getDownloadLink(
        ref: StoreAppRef,
        version: VersionRef?,
    ): StoreResult<DownloadResolution> = StoreResult.Unsupported

    override suspend fun healthCheck(): StoreResult<Unit> = StoreResult.Success(Unit)

    override suspend fun getVersions(ref: StoreAppRef): StoreResult<List<AppVersion>> =
        StoreResult.Unsupported
}

/**
 * An adapter declaring it publishes a local index.
 *
 * It is what makes `StoreRegistry.indexedStores` non-empty, and therefore the only way of getting the
 * Home to its interesting state instead of to `NoIndexedStore`.
 */
class FakeIndexedStoreAdapter(
    id: StoreId = StoreId.FDROID,
    metadata: StoreMetadata = fakeStoreMetadata(),
) : FakeStoreAdapter(
    id = id,
    metadata = metadata,
    capabilities = fakeStoreCapabilities(searchSource = SearchSource.LOCAL_INDEX),
),
    IndexedStoreAdapter {

    override suspend fun openIndex(current: IndexToken?): StoreResult<StoreIndexSnapshot> =
        StoreResult.Unsupported

    override fun mergeEntry(previous: String?, patch: String): String? = patch

    override fun projectEntry(payload: String): StoreListingDetail? = null

    override fun projectCatalog(payload: String): StoreCatalogInfo? = null
}

fun fakeStoreMetadata(
    displayName: String = "F-Droid",
    host: String = "f-droid.org",
): StoreMetadata = StoreMetadata(
    displayName = displayName,
    baseUrl = "https://$host",
    listingLanguage = "en",
    host = host,
)

/**
 * Minimal but **honest** capabilities: everything off except search.
 *
 * A capability declared `true` and not populated makes the real adapters' contract test fail; here
 * there is no contract test to fail, but the same discipline stops a test relying on a promise the
 * fake does not keep.
 */
fun fakeStoreCapabilities(
    searchSource: SearchSource = SearchSource.REMOTE,
    userAgent: String = "MultiStore-Test/1.0",
): StoreCapabilities = StoreCapabilities(
    search = true,
    trending = false,
    recent = false,
    versionHistory = false,
    providesPackageName = true,
    providesRating = false,
    providesScreenshots = false,
    providesChangelog = false,
    providesHash = HashAvailability.NONE,
    providesSignerFingerprint = false,
    supportsSplits = false,
    downloadMode = DownloadMode.DIRECT,
    networkTier = NetworkTier.OKHTTP,
    userAgent = userAgent,
    supportedFilters = emptySet(),
    searchSource = searchSource,
)

package com.multistore.store.fdroid

import com.multistore.core.common.di.StoreWorkDir
import com.multistore.core.model.ArtifactType
import com.multistore.core.model.ContentKind
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.model.StoreListingDetail
import com.multistore.core.model.StoreListingSummary
import com.multistore.core.model.VersionRef
import com.multistore.core.network.http.StoreHttpClient
import com.multistore.core.network.http.StoreHttpClients
import com.multistore.core.network.http.StoreNetworkProfile
import com.multistore.store.api.DownloadMode
import com.multistore.store.api.DownloadResolution
import com.multistore.store.api.FilterCapability
import com.multistore.store.api.HashAvailability
import com.multistore.store.api.IndexStaleness
import com.multistore.store.api.IndexSyncMode
import com.multistore.store.api.IndexToken
import com.multistore.store.api.IndexedStoreAdapter
import com.multistore.store.api.NetworkTier
import com.multistore.store.api.PagedResult
import com.multistore.store.api.SearchFilters
import com.multistore.store.api.SearchSource
import com.multistore.store.api.StoreCapabilities
import com.multistore.store.api.StoreCatalogInfo
import com.multistore.store.api.StoreError
import com.multistore.store.api.StoreIndexSnapshot
import com.multistore.store.api.StoreMetadata
import com.multistore.store.api.StoreResult
import com.multistore.store.common.StoreErrors
import com.multistore.store.common.storeCall
import com.multistore.store.fdroid.api.FdroidSearchApi
import com.multistore.store.fdroid.index.EmptyIndexSnapshot
import com.multistore.store.fdroid.index.EntryDocument
import com.multistore.store.fdroid.index.FdroidIndexClient
import com.multistore.store.fdroid.index.FdroidIndexSnapshot
import com.multistore.store.fdroid.index.IndexStreamReader
import com.multistore.store.fdroid.index.JsonMergePatch
import com.multistore.store.fdroid.index.PackagePayload
import com.multistore.store.fdroid.index.PackageProjection
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.Request

/**
 * The F-Droid adapter.
 *
 * It is the only one of the nine stores with a real contract, and that is why the project started
 * here: if the critical path does not work with a source that publishes a signed index, a hash on
 * every version and a one-hop download, it will not work with any of the other eight.
 *
 * ### Why search and detail do not go through this adapter
 *
 * `searchSource` is `LOCAL_INDEX`. The remote search API exists — the "App Search API" F-Droid
 * documents, on a host of its own: `search.f-droid.org/api/search_apps?q=` — but it returns **10
 * results and no more**: `page`, `per_page` and `limit` are ignored (measured: `page=1`, `2` and `3`
 * give the same response), and the fields are four, with no `packageName` and no version. It is not
 * a search to build a screen on.
 *
 * The complete index, which we need anyway for updates and detail, makes local search instant,
 * pageable, offline and free of rate-limit cost. The remote API finds its place elsewhere:
 * **covering the window before the first sync finishes**, when the local index is still empty (see
 * `FdroidSearchApi`).
 *
 * Consequently [search], [getAppDetails] and [getVersions] return `Unsupported`: the answers exist,
 * but `:core:data` gives them by reading what [projectEntry] produced. It is not a hole in the
 * contract, it is what the capability declares — and the contract test checks exactly that the two
 * coincide.
 */
@Singleton
class FdroidStoreAdapter @Inject constructor(
    private val config: FdroidConfig,
    clients: StoreHttpClients,
    @StoreWorkDir private val workDir: File,
    // Clock and dispatchers are injected. This one serves `maxAge`, which is the only thing in here
    // whose outcome depends on what day it is.
    private val clock: Clock = Clock.System,
) : IndexedStoreAdapter {

    private val http: StoreHttpClient = clients.forStore(
        StoreId.FDROID,
        StoreNetworkProfile(
            userAgent = config.userAgent,
            permitsPerSecond = config.permitsPerSecond,
            burst = config.burst,
        ),
    )

    private val json = IndexStreamReader.DEFAULT_JSON
    private val projection = PackageProjection(repoUrl = config.repoUrl)
    private val indexClient = FdroidIndexClient(config = config, http = http, workDir = workDir)
    private val searchApi = FdroidSearchApi(http = http, baseUrl = config.searchApiUrl)

    override val id: StoreId = StoreId.FDROID

    override val metadata: StoreMetadata = config.metadata

    /**
     * Declared on the basis of what the index really contains, not of what would be convenient. In
     * particular: `providesHash = ALWAYS` because the versions map's key **is** the file's SHA-256
     * on all 12,871 entries, 0 discrepancies; and `providesRating = false` because F-Droid has no
     * ratings and inventing them would be worse than not showing them.
     */
    override val capabilities: StoreCapabilities = StoreCapabilities(
        search = true,
        searchSource = SearchSource.LOCAL_INDEX,
        trending = false,
        recent = true,
        versionHistory = true,
        providesPackageName = true,
        providesRating = false,
        providesScreenshots = true,
        providesChangelog = true,
        providesHash = HashAvailability.ALWAYS,
        providesSignerFingerprint = true,
        supportsSplits = false,
        downloadMode = DownloadMode.DIRECT,
        networkTier = NetworkTier.OKHTTP,
        userAgent = config.userAgent,
        supportedFilters = setOf(
            FilterCapability.CONTENT_KIND,
            FilterCapability.CATEGORY,
            FilterCapability.SORT_NAME,
            FilterCapability.SORT_RECENTLY_UPDATED,
            FilterCapability.SORT_RECENTLY_ADDED,
            FilterCapability.MIN_SDK,
            FilterCapability.ANTI_FEATURES,
        ),
        contentKinds = setOf(ContentKind.APP, ContentKind.GAME),
        // The index does not expire on a clock: it expires when `entry.json` carries a new
        // timestamp, and that comparison costs 2.6 KB. A short TTL here would not make the data
        // fresher, it would only make a catalogue that is exactly what is published look "expired".
        listingTtl = 7.days,
    )

    // --- Search ---------------------------------------------------------------------------

    /**
     * The **fallback** search, the one that answers when the local index is not there yet.
     *
     * It returns at most [FdroidSearchApi.HARD_RESULT_CAP] results, with no version and no
     * pagination, because that is all the remote API gives. Callers must prefer the local index
     * whenever it is populated — [SearchSource.LOCAL_INDEX] says so — and mark results arriving from
     * here as partial.
     *
     * The [filters] are **ignored**: the API accepts none, and applying them to ten already
     * truncated results would give the impression of a filter working on a set that is not the right
     * set. It is not an omission, it is what `supportedFilters` describes: the local index applies
     * those filters.
     */
    override suspend fun search(
        query: String,
        filters: SearchFilters,
        page: Int,
    ): StoreResult<PagedResult<StoreListingSummary>> =
        if (page > 0) StoreResult.Success(PagedResult.empty(page)) else searchApi.search(query)

    // --- Operations served by the local index, not by the network -----------------------------

    override suspend fun getAppDetails(ref: StoreAppRef): StoreResult<StoreListingDetail> =
        StoreResult.Unsupported

    /**
     * This package's page on f-droid.org.
     *
     * It is the only method of this adapter that speaks of a web page: everything else lives in the
     * index. The ref **is** the `packageName`, which is also that URL's last segment.
     */
    override fun listingUrl(ref: StoreAppRef): String? =
        ref.value.takeIf { it.isNotBlank() }?.let(config::webListingUrl)

    override suspend fun getVersions(ref: StoreAppRef) = StoreResult.Unsupported

    override suspend fun getRecent(page: Int) = StoreResult.Unsupported

    // --- Download -----------------------------------------------------------------------

    /**
     * Builds the file's URL. No network: everything needed is inside the [VersionRef].
     *
     * The file name is **not** reconstructed from `packageName` and `versionCode`: 45 entries out of
     * 12,871 use `<pkg>_<versionCode>_<githash>.apk`, and a "clever" construction would fail on
     * exactly those, silently and only in production.
     */
    override suspend fun getDownloadLink(
        ref: StoreAppRef,
        version: VersionRef?,
    ): StoreResult<DownloadResolution> = storeCall {
        if (version == null) {
            return@storeCall StoreResult.Failure(
                StoreError.Unsupported(
                    "F-Droid serves the complete index: the caller picks the version to " +
                        "download, since it already has all of them in front of it.",
                ),
            )
        }
        val decoded = FdroidRefs.decode(version)
            ?: return@storeCall StoreResult.Failure(
                StoreErrors.parseFailure("VersionRef", version.value),
            )
        StoreResult.Success(
            DownloadResolution.Direct(
                url = config.repoFile(decoded.fileName),
                // Verified: 200 in one hop, no cookies, no Referer, and even with an empty UA.
                headers = emptyMap(),
                fileName = decoded.fileName.substringAfterLast('/'),
                artifactType = ArtifactType.APK,
                expectedSha256 = decoded.sha256,
                expectedSize = decoded.sizeBytes,
                expiresAt = null,
            ),
        )
    }

    override suspend fun healthCheck(): StoreResult<Unit> = storeCall {
        // `entry.jar` weighs 2.6 KB and is the document everything else depends on: if it answers,
        // the store is alive in the way that matters to us.
        val response = http.executeUncached(Request.Builder().url(config.entryJarUrl).head().build())
        response.use {
            if (it.isSuccessful) StoreResult.Success(Unit) else StoreResult.Failure(StoreErrors.fromResponse(it))
        }
    }

    // --- Index ----------------------------------------------------------------------------

    override suspend fun openIndex(current: IndexToken?): StoreResult<StoreIndexSnapshot> = storeCall {
        val entry = when (val fetched = indexClient.fetchEntry()) {
            is StoreResult.Success -> fetched.value
            is StoreResult.Failure -> return@storeCall StoreResult.Failure(fetched.error)
            StoreResult.Unsupported -> return@storeCall StoreResult.Unsupported
        }

        val staleness = stalenessOf(entry)

        val currentTimestamp = current?.value?.toLongOrNull()
        if (currentTimestamp != null) {
            if (entry.timestamp == currentTimestamp) {
                return@storeCall StoreResult.Success(
                    EmptyIndexSnapshot(IndexToken(entry.timestamp.toString()), staleness),
                )
            }
            if (entry.timestamp < currentTimestamp) {
                // Anti-rollback defence. The timestamp comes from a signed document, but the
                // signature does not stop a mirror **re-serving an old but authentic index**: it is
                // the attack that freezes security updates, and the only way to notice is to
                // remember how far we had got.
                return@storeCall StoreResult.Failure(
                    StoreErrors.parseFailure(
                        selector = SELECTOR_ROLLBACK,
                        snippet = "stored=$currentTimestamp served=${entry.timestamp}",
                    ),
                )
            }
        }

        val diff = currentTimestamp?.let(entry::diffFrom)
        val file = diff ?: entry.index
        val mode = if (diff != null) IndexSyncMode.INCREMENTAL else IndexSyncMode.FULL

        when (val downloaded = indexClient.download(file)) {
            is StoreResult.Success -> StoreResult.Success(
                FdroidIndexSnapshot(
                    download = downloaded.value,
                    token = IndexToken(entry.timestamp.toString()),
                    mode = mode,
                    expectedRecords = file.numPackages.takeIf { it > 0 },
                    expectedBytes = file.size,
                    staleness = staleness,
                    projection = projection,
                    json = json,
                ),
            )

            is StoreResult.Failure -> StoreResult.Failure(downloaded.error)
            StoreResult.Unsupported -> StoreResult.Unsupported
        }
    }

    /**
     * How old the served index is, against the `maxAge` `entry.json` declares.
     *
     * `maxAge` was the only field of the signed document we deserialised without ever reading. It is
     * not a duplicate of the anti-rollback above: that one compares against what **we** had, so on a
     * first sync it protects nobody. This one compares against the clock, and is the only one that
     * sees a mirror frozen for months.
     *
     * A negative age — an index dated in the future — counts as zero: it is not a case to report
     * here, and treating it as "very old" would be the opposite of what it is.
     */
    private fun stalenessOf(entry: EntryDocument): IndexStaleness {
        val age = clock.now() - Instant.fromEpochMilliseconds(entry.timestamp)
        return IndexStaleness(
            age = if (age.isNegative()) Duration.ZERO else age,
            maxAge = entry.maxAge.days,
        )
    }

    /**
     * Merges a merge patch with the stored payload.
     *
     * Pure: no network, no state. It is the piece that lets the adapter know the diff's format
     * without knowing the database the "before" is kept in.
     */
    override fun mergeEntry(previous: String?, patch: String): String? {
        val patchElement = runCatching { json.parseToJsonElement(patch) }.getOrNull() ?: return previous
        val previousElement = previous?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() }
        val merged = JsonMergePatch.apply(previousElement, patchElement) ?: return null
        return json.encodeToString(JsonElement.serializer(), merged)
    }

    override fun projectEntry(payload: String): StoreListingDetail? {
        val obj = runCatching { json.parseToJsonElement(payload) }.getOrNull() as? JsonObject ?: return null
        val packageName = PackagePayload.packageNameOf(obj) ?: return null
        return projection.project(packageName, obj)
    }

    override fun projectCatalog(payload: String): StoreCatalogInfo? {
        val obj = runCatching { json.parseToJsonElement(payload) }.getOrNull() as? JsonObject ?: return null
        return IndexStreamReader.projectCatalog(obj)
    }

    private companion object {
        const val SELECTOR_ROLLBACK = "entry.json/timestamp"
    }
}

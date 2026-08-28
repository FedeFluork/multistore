package com.multistore.core.data

import com.multistore.core.model.AntiFeature
import com.multistore.core.model.AppVersion
import com.multistore.core.model.Category
import com.multistore.core.model.ContentKind
import com.multistore.core.model.LocalizedText
import com.multistore.core.model.Sha256
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.model.StoreListingDetail
import com.multistore.core.model.StoreListingSummary
import com.multistore.core.model.VersionRef
import com.multistore.store.api.DownloadMode
import com.multistore.store.api.DownloadResolution
import com.multistore.store.api.FilterCapability
import com.multistore.store.api.HashAvailability
import com.multistore.store.api.IndexRecord
import com.multistore.store.api.IndexSyncMode
import com.multistore.store.api.IndexToken
import com.multistore.store.api.IndexedStoreAdapter
import com.multistore.store.api.NetworkTier
import com.multistore.store.api.PagedResult
import com.multistore.store.api.SearchFilters
import com.multistore.store.api.SearchSource
import com.multistore.store.api.StoreCapabilities
import com.multistore.store.api.StoreCatalogInfo
import com.multistore.store.api.IndexStaleness
import com.multistore.store.api.StoreIndexSnapshot
import com.multistore.store.api.StoreMetadata
import com.multistore.store.api.StoreResult
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A fake indexed store, with a minimal payload format but with **the same rules**.
 *
 * It does not use F-Droid's fixtures, and that is not a shortcut: `:core:data` cannot depend on a
 * concrete store, and testing it against the real adapter would mean testing two things together and
 * not knowing which of the two broke. What has to be faithful is the **contract**, and it is:
 *
 *  - `mergeEntry` applies JSON merge patch semantics, where `null` deletes a key and an object
 *    reduced to nothing deletes the entry;
 *  - the payload carries its own identifier inside, as required by the fact that `projectEntry`
 *    receives only that;
 *  - the taxonomy arrives as a payload to merge, not as a ready-made list.
 */
class FakeIndexedStoreAdapter(
    override val id: StoreId = StoreId.FDROID,
    private val ttl: Duration = 7.days,
    /**
     * It can declare `REMOTE` while implementing [IndexedStoreAdapter].
     *
     * It is not a test oddity: it is the configuration `StoreRegistry.indexed` has to refuse. An
     * adapter that has an index but declares it searches over the network will search over the
     * network, because the capability is what the rest of the app decides on.
     */
    private val source: SearchSource = SearchSource.LOCAL_INDEX,
    /** What it declares it can filter by itself. */
    private val supportedFilters: Set<FilterCapability> = emptySet(),
    /** What the app can filter on its rows. See `FilterPlan`. */
    private val clientFilters: Set<FilterCapability> = emptySet(),
    /** Whether it declares a version history. See `AppDetailRepository.loadVersionHistory`. */
    private val versionHistory: Boolean = true,
) : IndexedStoreAdapter {

    /** What the next [openIndex] will serve. The test sets it. */
    var nextSnapshot: (IndexToken?) -> StoreResult<StoreIndexSnapshot> = { StoreResult.Unsupported }

    /** The tokens [openIndex] was called with, in order. */
    val openedWith = mutableListOf<IndexToken?>()

    var searchResults: StoreResult<PagedResult<StoreListingSummary>> =
        StoreResult.Success(PagedResult.empty())

    /** The queries [search] was called with: it serves to prove it is *not* called. */
    val searchedFor = mutableListOf<String>()

    /** The filters [search] was called with: it serves to prove **what** reaches it. */
    val searchedWith = mutableListOf<SearchFilters>()

    /**
     * How long it takes to answer.
     *
     * It serves the streaming of partials: with every store instantaneous, a search waiting for the
     * slowest and one emitting on every answer are indistinguishable. Among the real stores the
     * spread is real — F-Droid reads Room, apkmirror has three seconds of `Crawl-delay`.
     */
    var searchDelay: Duration = Duration.ZERO

    var detailResult: StoreResult<StoreListingDetail> = StoreResult.Unsupported

    override val metadata = StoreMetadata(
        displayName = "Fake",
        baseUrl = "https://fake.test",
        listingLanguage = "en-US",
        host = "fake.test",
    )

    override val capabilities = StoreCapabilities(
        search = true,
        searchSource = source,
        trending = false,
        recent = true,
        versionHistory = versionHistory,
        providesPackageName = true,
        providesRating = false,
        providesScreenshots = false,
        providesChangelog = false,
        providesHash = HashAvailability.ALWAYS,
        providesSignerFingerprint = true,
        supportsSplits = false,
        downloadMode = DownloadMode.DIRECT,
        networkTier = NetworkTier.OKHTTP,
        userAgent = "MultiStoreTest/1.0",
        supportedFilters = supportedFilters,
        clientFilters = clientFilters,
        contentKinds = setOf(ContentKind.APP),
        listingTtl = ttl,
    )

    override suspend fun search(query: String, filters: SearchFilters, page: Int):
        StoreResult<PagedResult<StoreListingSummary>> {
        searchedFor += query
        searchedWith += filters
        if (searchDelay > Duration.ZERO) delay(searchDelay)
        return searchResults
    }

    override suspend fun getAppDetails(ref: StoreAppRef): StoreResult<StoreListingDetail> = detailResult

    /** What the history answers, and which refs it was asked with. */
    var versionsResult: StoreResult<List<AppVersion>> = StoreResult.Unsupported
    val versionsAskedFor = mutableListOf<StoreAppRef>()

    override suspend fun getVersions(ref: StoreAppRef): StoreResult<List<AppVersion>> {
        versionsAskedFor += ref
        return versionsResult
    }

    override suspend fun getDownloadLink(ref: StoreAppRef, version: VersionRef?) =
        StoreResult.Unsupported as StoreResult<DownloadResolution>

    override suspend fun healthCheck() = StoreResult.Success(Unit)

    override suspend fun openIndex(current: IndexToken?): StoreResult<StoreIndexSnapshot> {
        openedWith += current
        return nextSnapshot(current)
    }

    override fun mergeEntry(previous: String?, patch: String): String? {
        val patchObject = parse(patch) ?: return previous
        val base = previous?.let(::parse) ?: JsonObject(emptyMap())
        val merged = base.toMutableMap()
        for ((key, value) in patchObject) {
            if (value is JsonNull) merged.remove(key) else merged[key] = value
        }
        // A merge patch that empties the entry is a deletion, not an empty payload.
        if (merged.isEmpty()) return null
        return JSON.encodeToString(JsonObject.serializer(), JsonObject(merged))
    }

    override fun projectEntry(payload: String): StoreListingDetail? {
        val obj = parse(payload) ?: return null
        val name = obj.string(FIELD_ID) ?: return null
        // What is not installable is not projected: it is the case of F-Droid's `.zip` entries, which
        // the repository has to keep as a payload without ever showing them.
        if (obj.string(FIELD_KIND) == KIND_NOT_INSTALLABLE) return null
        val versionCode = obj.string(FIELD_VERSION_CODE)?.toLongOrNull()
        return StoreListingDetail(
            summary = StoreListingSummary(
                storeId = id,
                ref = StoreAppRef(name),
                title = obj.string(FIELD_TITLE) ?: name,
                packageName = name,
                summary = obj.string(FIELD_SUMMARY)?.let { LocalizedText.of(it) } ?: LocalizedText.EMPTY,
                categories = obj.string(FIELD_CATEGORY)?.let(::listOf).orEmpty(),
                // The type is carried by the **entry**, as in the real index: F-Droid derives it from
                // its categories during projection, it does not read it from a listing field.
                contentKind = obj.string(FIELD_CONTENT_KIND)
                    ?.let { name -> ContentKind.entries.firstOrNull { it.name == name } }
                    ?: ContentKind.UNKNOWN,
                rating = obj.string(FIELD_RATING)?.toFloatOrNull(),
                lastUpdated = obj.string(FIELD_UPDATED)?.toLongOrNull()
                    ?.let(Instant::fromEpochMilliseconds),
            ),
            versions = versionCode?.let {
                listOf(
                    AppVersion(
                        versionName = obj.string(FIELD_VERSION_NAME) ?: it.toString(),
                        versionCode = it,
                        ref = VersionRef("$name@$it"),
                        sha256 = Sha256.parseOrNull(obj.string(FIELD_SHA) ?: ""),
                    ),
                )
            }.orEmpty(),
        )
    }

    override fun projectCatalog(payload: String): StoreCatalogInfo? {
        val obj = parse(payload) ?: return null
        return StoreCatalogInfo(
            categories = obj.keys.filter { it.startsWith(CATEGORY_PREFIX) }.map { key ->
                Category(
                    id = key.removePrefix(CATEGORY_PREFIX),
                    name = LocalizedText.of(obj.string(key)),
                )
            },
            antiFeatures = obj.keys.filter { it.startsWith(ANTI_FEATURE_PREFIX) }.map { key ->
                AntiFeature(
                    id = key.removePrefix(ANTI_FEATURE_PREFIX),
                    name = LocalizedText.of(obj.string(key)),
                )
            },
        )
    }

    private fun parse(raw: String): JsonObject? =
        runCatching { JSON.parseToJsonElement(raw) }.getOrNull() as? JsonObject

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    companion object {
        val JSON: Json = Json { ignoreUnknownKeys = true }

        const val FIELD_ID = "id"
        const val FIELD_TITLE = "title"
        const val FIELD_SUMMARY = "summary"
        const val FIELD_CATEGORY = "category"
        const val FIELD_VERSION_CODE = "versionCode"
        const val FIELD_VERSION_NAME = "versionName"
        const val FIELD_UPDATED = "updated"
        const val FIELD_SHA = "sha"
        const val FIELD_KIND = "kind"
        const val FIELD_CONTENT_KIND = "contentKind"
        const val FIELD_RATING = "rating"
        const val KIND_NOT_INSTALLABLE = "zip"
        const val CATEGORY_PREFIX = "cat:"
        const val ANTI_FEATURE_PREFIX = "af:"

        /** A complete entry's payload, with the identifier inside as the contract requires. */
        fun payload(id: String, vararg fields: Pair<String, String?>): String {
            val map = mutableMapOf<String, kotlinx.serialization.json.JsonElement>(
                FIELD_ID to JsonPrimitive(id),
            )
            for ((key, value) in fields) {
                map[key] = if (value == null) JsonNull else JsonPrimitive(value)
            }
            return JSON.encodeToString(JsonObject.serializer(), JsonObject(map))
        }

        /** A patch: like [payload], but with no mandatory fields and with `null` deleting. */
        fun patch(id: String, vararg fields: Pair<String, String?>): String = payload(id, *fields)

        /** The patch that deletes an entry: it zeroes every key, id included. */
        fun tombstone(id: String, keys: List<String>): String {
            val map = keys.associateWith { JsonNull } + (FIELD_ID to JsonNull)
            return JSON.encodeToString(JsonObject.serializer(), JsonObject(map))
        }
    }
}

/** A fake sync: the records are the ones the test wrote. */
class FakeSnapshot(
    override val token: IndexToken,
    override val mode: IndexSyncMode,
    private val records: List<IndexRecord>,
    override val expectedRecords: Int? = records.size,
    override val expectedBytes: Long? = null,
    override val staleness: IndexStaleness? = null,
) : StoreIndexSnapshot {

    var closed: Boolean = false
        private set

    override fun records(): Flow<IndexRecord> = records.asFlow()

    override fun close() {
        closed = true
    }
}

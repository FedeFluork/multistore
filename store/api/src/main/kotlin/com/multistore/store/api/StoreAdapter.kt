package com.multistore.store.api

import com.multistore.core.model.AppVersion
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.model.StoreListingDetail
import com.multistore.core.model.StoreListingSummary
import com.multistore.core.model.VersionRef

/**
 * What the rest of MultiStore knows about a store.
 *
 * An adapter has **no observable state**: it holds no cache, writes to no disk, knows no Room. It
 * receives a question, makes the requests it needs, returns a [StoreResult]. Everything worth
 * keeping is kept by `:core:data`.
 *
 * The two rules that make this contract sufficient for nine very different stores:
 *
 *  - **[StoreAppRef] is opaque.** Only the adapter knows whether it is a slug, a numeric id or a
 *    path. The core never builds a URL.
 *  - **No exception leaves here.** With nine adapters queried in parallel, an exception escaping
 *    one would cancel the scope and kill the other eight.
 */
interface StoreAdapter {

    val id: StoreId

    val metadata: StoreMetadata

    val capabilities: StoreCapabilities

    suspend fun search(
        query: String,
        filters: SearchFilters = SearchFilters.NONE,
        page: Int = 0,
    ): StoreResult<PagedResult<StoreListingSummary>>

    suspend fun getAppDetails(ref: StoreAppRef): StoreResult<StoreListingDetail>

    /** [version] `null` = the version the store considers current. */
    suspend fun getDownloadLink(
        ref: StoreAppRef,
        version: VersionRef? = null,
    ): StoreResult<DownloadResolution>

    /** Does the store answer? Used by the circuit breaker for the half-open probe. */
    suspend fun healthCheck(): StoreResult<Unit>

    // --- Optional: enabled by capabilities, otherwise Unsupported --------------------------

    suspend fun getTrending(page: Int = 0): StoreResult<PagedResult<StoreListingSummary>> =
        StoreResult.Unsupported

    suspend fun getRecent(page: Int = 0): StoreResult<PagedResult<StoreListingSummary>> =
        StoreResult.Unsupported

    suspend fun getVersions(ref: StoreAppRef): StoreResult<List<AppVersion>> =
        StoreResult.Unsupported

    /**
     * Checks the file is actually downloadable before showing the button.
     *
     * It exists for modyolo, where **about a quarter of the binaries answer HTTP 500**: without a
     * preflight HEAD the user would discover the failure after pressing Download. The default is
     * "available": for healthy stores an extra request per listing would be a cost with no return.
     */
    suspend fun preflight(resolution: DownloadResolution): StoreResult<Boolean> =
        StoreResult.Success(true)

    /**
     * The **human** page for this listing, the one a browser would open.
     *
     * Not `suspend` and it touches no network: it is the same construction the adapter already
     * performs to go and read it. It is in the contract rather than in the screen for the reason
     * at the top of this file — the core never builds a URL: the shape of that path is the one
     * thing `StoreAppRef` hides, and rebuilding it outside would mean nine special cases in
     * `:feature:appdetail` ageing separately from the nine adapters.
     *
     * `null` where there is no page to open. On a locally-indexed store the app draws the listing
     * from its own catalogue, but the repository has a page all the same — and to the user that
     * is the "original page".
     */
    fun listingUrl(ref: StoreAppRef): String? = null
}

/**
 * A store's identity.
 *
 * [displayName] is the brand, not interface text: "F-Droid" is written the same in all five
 * languages. The store's **description**, which does get translated, lives in `:feature:settings`'
 * `strings.xml`.
 */
data class StoreMetadata(
    val displayName: String,
    val baseUrl: String,
    /** The language the store writes its listings in, BCP-47. Needed to resolve texts. */
    val listingLanguage: String,
    /** The domain to show the user when an external page is opened. */
    val host: String,
)

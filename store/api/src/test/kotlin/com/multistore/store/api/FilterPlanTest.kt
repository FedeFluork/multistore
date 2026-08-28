package com.multistore.store.api

import com.google.common.truth.Truth.assertThat
import com.multistore.core.model.ContentKind
import com.multistore.core.model.SearchSort
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.model.StoreListingSummary
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The three tiers of the hybrid semantics.
 *
 * Every assertion here must be able to fail on a plausible but wrong version of [FilterPlan], and
 * that is the criterion they were chosen by: "the store can do it" and "nobody can do it" are the
 * easy cases, while the two that really break things are the indexed store in fallback and the row
 * missing the field.
 */
@DisplayName("FilterPlan — who applies a filter")
class FilterPlanTest {

    @Test
    @DisplayName("a filter at its neutral value demands nothing of anyone")
    fun neutralFiltersRequireNothing() {
        assertThat(FilterPlan.required(SearchFilters.NONE)).isEmpty()
        // Sorting is not a filter: it removes no rows, so it cannot exclude a store.
        assertThat(FilterPlan.required(SearchFilters(sort = SearchSort.RATING))).isEmpty()
        // Nor is adult content, the one case where the *active* value is also the default:
        // governing it here would exclude eight stores out of nine from every ordinary search.
        assertThat(FilterPlan.required(SearchFilters(includeNsfw = false))).isEmpty()
        assertThat(FilterPlan.required(SearchFilters(includeNsfw = true))).isEmpty()
    }

    @Test
    @DisplayName("whoever declares the filter applies it; whoever carries the field, we apply it")
    fun theThreeTiers() {
        val filters = SearchFilters(contentKind = ContentKind.GAME)

        val storeSide = capabilities(supported = setOf(FilterCapability.CONTENT_KIND))
        val clientSide = capabilities(client = setOf(FilterCapability.CONTENT_KIND))
        val neither = capabilities()

        assertThat(FilterPlan.unsupported(filters, storeSide, servedByIndex = false)).isEmpty()
        assertThat(FilterPlan.unsupported(filters, clientSide, servedByIndex = false)).isEmpty()
        assertThat(FilterPlan.unsupported(filters, neither, servedByIndex = false))
            .containsExactly(FilterCapability.CONTENT_KIND)
    }

    /**
     * The same store, two different tiers, and what decides is whether the index is there.
     *
     * F-Droid declares `CONTENT_KIND` and seven other filters, and **the index** applies them with
     * a SQL query. Until the index has been downloaded the fallback search answers — the
     * ten-result remote API, which accepts no filter. Reading the capability alone would announce
     * a filter that in that window nobody applies.
     */
    @Test
    @DisplayName("an indexed store without its index is not a store that can filter")
    fun aLocalIndexStoreInBootstrapCannotFilter() {
        val fdroid = capabilities(
            supported = setOf(FilterCapability.CONTENT_KIND),
            source = SearchSource.LOCAL_INDEX,
        )
        val filters = SearchFilters(contentKind = ContentKind.APP)

        assertThat(FilterPlan.tier(FilterCapability.CONTENT_KIND, fdroid, servedByIndex = true))
            .isEqualTo(FilterTier.STORE_SIDE)
        assertThat(FilterPlan.tier(FilterCapability.CONTENT_KIND, fdroid, servedByIndex = false))
            .isEqualTo(FilterTier.UNSUPPORTED)
        assertThat(FilterPlan.unsupported(filters, fdroid, servedByIndex = false))
            .containsExactly(FilterCapability.CONTENT_KIND)
    }

    @Test
    @DisplayName("the client-side filter discards the rows that do not satisfy it")
    fun clientSideFilteringDropsRows() {
        val caps = capabilities(client = setOf(FilterCapability.CONTENT_KIND))
        val rows = listOf(
            row("Solitario", kind = ContentKind.GAME),
            row("Calculator", kind = ContentKind.APP),
        )

        val kept = FilterPlan.applyClientSide(
            rows = rows,
            filters = SearchFilters(contentKind = ContentKind.GAME),
            capabilities = caps,
            servedByIndex = false,
        )

        assertThat(kept.map { it.title }).containsExactly("Solitario")
    }

    /**
     * A row without the field **does not pass**.
     *
     * It does not happen on the fixtures — that is exactly what the contract test verifies before
     * letting `clientFilters` be declared — but the behaviour has to be pinned all the same: the
     * day a store stopped publishing the rating, the alternative would be an "at least 4 stars"
     * search returning apps with no rating as though it had judged them.
     */
    @Test
    @DisplayName("a row missing the field does not satisfy the filter")
    fun aRowWithoutTheFieldDoesNotPass() {
        val caps = capabilities(client = setOf(FilterCapability.MIN_RATING))
        val rows = listOf(row("With rating", rating = 4.2f), row("Without rating", rating = null))

        val kept = FilterPlan.applyClientSide(
            rows = rows,
            filters = SearchFilters(minRating = 4f),
            capabilities = caps,
            servedByIndex = false,
        )

        assertThat(kept.map { it.title }).containsExactly("With rating")
    }

    /**
     * What the store already filtered is not filtered again.
     *
     * Not an optimisation: a defence against the double answer. If our interpretation of a filter
     * ever diverged from the store's, re-filtering here would hide the divergence by silently
     * discarding rows the store considered valid.
     */
    @Test
    @DisplayName("what the store filtered on its side does not pass through here again")
    fun storeSideFiltersAreNotAppliedTwice() {
        val caps = capabilities(supported = setOf(FilterCapability.CONTENT_KIND))
        // Rows the store already chose, and which read here would not carry the field.
        val rows = listOf(row("Solitario", kind = ContentKind.UNKNOWN))

        val kept = FilterPlan.applyClientSide(
            rows = rows,
            filters = SearchFilters(contentKind = ContentKind.GAME),
            capabilities = caps,
            servedByIndex = false,
        )

        assertThat(kept).hasSize(1)
    }

    @Test
    @DisplayName("two active filters must both be satisfied")
    fun twoActiveFiltersBothApply() {
        val caps = capabilities(
            client = setOf(FilterCapability.CONTENT_KIND, FilterCapability.MIN_RATING),
        )
        val rows = listOf(
            row("Rated game", kind = ContentKind.GAME, rating = 4.5f),
            row("Badly rated game", kind = ContentKind.GAME, rating = 2.0f),
            row("Rated app", kind = ContentKind.APP, rating = 4.9f),
        )

        val kept = FilterPlan.applyClientSide(
            rows = rows,
            filters = SearchFilters(contentKind = ContentKind.GAME, minRating = 4f),
            capabilities = caps,
            servedByIndex = false,
        )

        assertThat(kept.map { it.title }).containsExactly("Rated game")
    }

    private fun capabilities(
        supported: Set<FilterCapability> = emptySet(),
        client: Set<FilterCapability> = emptySet(),
        source: SearchSource = SearchSource.REMOTE,
    ) = StoreCapabilities(
        search = true,
        trending = false,
        recent = false,
        versionHistory = false,
        providesPackageName = false,
        providesRating = true,
        providesScreenshots = false,
        providesChangelog = false,
        providesHash = HashAvailability.NONE,
        providesSignerFingerprint = false,
        supportsSplits = false,
        downloadMode = DownloadMode.DIRECT,
        networkTier = NetworkTier.OKHTTP,
        userAgent = "Mozilla/5.0 (Android) MultiStoreTest",
        supportedFilters = supported,
        clientFilters = client,
        searchSource = source,
    )

    private fun row(
        title: String,
        kind: ContentKind = ContentKind.UNKNOWN,
        rating: Float? = null,
    ) = StoreListingSummary(
        storeId = StoreId.APKMIRROR,
        ref = StoreAppRef(title),
        title = title,
        contentKind = kind,
        rating = rating,
    )
}

package com.multistore.feature.search

import androidx.compose.runtime.Composable
import com.multistore.core.common.net.StoreHealth
import com.multistore.core.common.result.AppError
import com.multistore.core.data.repository.StoreEntry
import com.multistore.core.data.repository.StoreShortfall
import com.multistore.core.model.AggregatedApp
import com.multistore.core.model.AggregatedListing
import com.multistore.core.model.LocalizedText
import com.multistore.core.model.ResultOrigin
import com.multistore.core.model.SearchSort
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.model.StoreListingSummary
import com.multistore.core.model.ThemeMode
import com.multistore.core.testing.ScreenshotTest
import com.multistore.store.api.FilterCapability
import kotlin.time.Duration.Companion.minutes
import org.junit.Test

/**
 * Screenshots of [SearchScreen] in both themes.
 *
 * The golden photographs results **plus an absence**: a store paused by an open breaker. That is the
 * state the rest of the app treats as normal — with nine sources queried together, "all of them
 * answered" is the lucky case, not the reference one — and therefore the state in which a visual
 * regression is best made visible.
 *
 * The captured state says two more things, and both have a reason to be in a golden: the first row
 * comes from **two merged stores** and says so, and the paused store declares **how long** until the
 * next attempt. The second is why the countdown is a duration computed upstream rather than a clock
 * read here: a golden that read the time would not be comparable with itself.
 */
class SearchScreenScreenshotTest : ScreenshotTest() {

    @Test
    fun lightTheme() = capture(SCREEN_NAME, ThemeMode.LIGHT) { Content() }

    @Test
    fun darkTheme() = capture(SCREEN_NAME, ThemeMode.DARK) { Content() }

    /**
     * The filter panel **open**, which is a state of the screen and not a variant.
     *
     * It is also where this area's layout defects live: a sheet that scrolls, four groups of chips that
     * wrap on their own, and a list of stores whose length depends on how many the user has enabled. No
     * state test would see them.
     */
    @Test
    fun filtersLight() = capture(FILTERS_SCREEN_NAME, ThemeMode.LIGHT) { Content(showFilters = true) }

    @Test
    fun filtersDark() = capture(FILTERS_SCREEN_NAME, ThemeMode.DARK) { Content(showFilters = true) }

    @Composable
    private fun Content(showFilters: Boolean = false) {
        SearchScreen(
            uiState = SearchUiState.Results(
                query = "browser",
                apps = listOf(
                    AggregatedApp(
                        appKey = "pkg:org.mozilla.fennec_fdroid",
                        listings = listOf(
                            listing(
                                StoreId.FDROID,
                                "org.mozilla.fennec_fdroid",
                                "Fennec",
                                "A Firefox build without proprietary bits.",
                            ),
                            // The rating comes from apkcombo and not F-Droid, which publishes none: this
                            // is real aggregation, and also the case where the row shows a number the
                            // primary listing does not have.
                            listing(
                                StoreId.APKCOMBO,
                                "fennec/org.mozilla.fennec_fdroid",
                                "Fennec F-Droid",
                                "",
                                ResultOrigin.REMOTE,
                                rating = 4.3f,
                            ),
                        ),
                    ),
                    AggregatedApp(
                        appKey = "pkg:org.torproject.torbrowser",
                        listings = listOf(
                            listing(
                                StoreId.FDROID,
                                "org.torproject.torbrowser",
                                "Tor Browser",
                                "Browse the web anonymously.",
                            ),
                        ),
                    ),
                ),
                shortfalls = listOf(
                    StoreShortfall(
                        storeId = StoreId.APKMIRROR,
                        error = null,
                        circuitOpen = true,
                        retryIn = 4.minutes,
                    ),
                    StoreShortfall(storeId = StoreId.MODYOLO, error = AppError.Network(null)),
                    // A fault and an exclusion together, on purpose: they are two different notices with
                    // two different remedies — one is retried, the other removed — and the golden is there
                    // precisely to show they do not merge into one.
                    StoreShortfall(
                        storeId = StoreId.UPTODOWN,
                        error = null,
                        unsupportedFilters = setOf(FilterCapability.MIN_RATING),
                    ),
                ),
                hasMore = true,
                answered = 4,
                queried = 5,
            ),
            preferredLanguageTags = listOf("en"),
            storeDisplayName = { it.wireName },
            onQueryChange = {},
            onAppClick = { _, _ -> },
            onLoadMore = {},
            onRetry = {},
            filters = SearchFilterState(
                minRating = 4f,
                sort = SearchSort.RATING,
                excludedStores = setOf(StoreId.MODYOLO),
                available = listOf(
                    storeEntry(StoreId.FDROID, "F-Droid"),
                    storeEntry(StoreId.APKCOMBO, "APKCombo"),
                    storeEntry(StoreId.APKMIRROR, "APKMirror"),
                    storeEntry(StoreId.MODYOLO, "Modyolo"),
                    storeEntry(StoreId.UPTODOWN, "Uptodown"),
                ),
            ),
            initiallyShowingFilters = showFilters,
        )
    }

    private fun storeEntry(storeId: StoreId, name: String) = StoreEntry(
        storeId = storeId,
        displayName = name,
        host = "${storeId.wireName}.example",
        enabled = true,
        health = StoreHealth(storeId),
    )

    private fun listing(
        storeId: StoreId,
        ref: String,
        title: String,
        text: String,
        origin: ResultOrigin = ResultOrigin.LOCAL_INDEX,
        rating: Float? = null,
    ) = AggregatedListing(
        summary = StoreListingSummary(
            storeId = storeId,
            ref = StoreAppRef(ref),
            title = title,
            packageName = ref.substringAfterLast('/'),
            summary = if (text.isEmpty()) LocalizedText.EMPTY else LocalizedText(mapOf("en" to text)),
            rating = rating,
        ),
        origin = origin,
    )

    private companion object {
        const val SCREEN_NAME = "SearchScreen"
        const val FILTERS_SCREEN_NAME = "SearchScreen_filters"
    }
}

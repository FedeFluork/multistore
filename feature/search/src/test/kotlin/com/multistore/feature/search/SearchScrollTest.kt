package com.multistore.feature.search

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.multistore.core.designsystem.theme.MultiStoreTheme
import com.multistore.core.model.AggregatedApp
import com.multistore.core.model.AggregatedListing
import com.multistore.core.model.ResultOrigin
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.model.StoreListingSummary
import com.multistore.core.model.ThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Where the reader had got to in the results, after a trip to an app page and back.
 *
 * The screen deliberately pins the list to the top while the reader has not moved it, because
 * aggregation reorders on every store that answers and the key-anchoring of `LazyColumn` would
 * otherwise open a search part-way down its own results. That flag says something about the
 * **reader**, not about this composition — and it was a plain `remember`, so opening an app page
 * threw it away: navigation disposes this list, the flag came back `false`, and the effect pinned
 * the position back to the top over the offset `rememberLazyListState` had just restored correctly.
 *
 * The report also said the position survived when one came straight back without touching the app
 * page, which is consistent with the disposal not having happened yet — that half was not measured.
 * What is measured is this one: here, and the fix confirmed by hand on the emulator.
 *
 * The `SaveableStateHolder` is how the trip is expressed here: it is what `NavHost` puts around a
 * destination, so removing the content saves the same state navigation saves and discards the same
 * state navigation discards.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SearchScrollTest {

    @Suppress("DEPRECATION")
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `the reader's position in the results survives a trip to an app page`() {
        val results = SearchUiState.Results(
            query = "app",
            apps = List(40) { app(it) },
            shortfalls = emptyList(),
            hasMore = false,
        )
        var onSearch by mutableStateOf(true)

        rule.setContent {
            val holder = rememberSaveableStateHolder()
            MultiStoreTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
                if (onSearch) {
                    holder.SaveableStateProvider("search") {
                        SearchScreen(
                            uiState = results,
                            preferredLanguageTags = listOf("en"),
                            storeDisplayName = { it.wireName },
                            onQueryChange = {},
                            onAppClick = { _, _ -> },
                            onLoadMore = {},
                            onRetry = {},
                        )
                    }
                } else {
                    Text(text = "app page")
                }
            }
        }

        // The drag is the point, not the offset it covers: the screen watches gestures, so a
        // programmatic scroll alone would leave the reader looking as if they had never touched the
        // list. The `performScrollToIndex` after it is only there to make the position exact.
        rule.onNode(hasScrollToIndexAction()).performTouchInput { swipeUp() }
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(30)
        rule.waitForIdle()
        rule.onNodeWithText(title(30)).assertIsDisplayed()

        // Open an app page, then come back. Nothing about the results changed while away.
        onSearch = false
        rule.waitForIdle()
        onSearch = true
        rule.waitForIdle()

        // The first result must still be off screen — and off screen means not composed at all in a
        // `LazyColumn`, which is why this reads as "does not exist".
        rule.onNodeWithText(title(0)).assertDoesNotExist()
        rule.onNodeWithText(title(30)).assertIsDisplayed()
    }

    /**
     * The other half, and the one that decides between a flag and the query.
     *
     * A saved `true` would come back and answer for whatever is searched for next, and that is not a
     * hypothetical route: after process death the results are gone — the ViewModel starts idle — so
     * the entry is not consumed on the way in and waits for the first list that composes. What the
     * screen saves is therefore *which query* the reader took over, which the next question cannot
     * be mistaken for.
     *
     * The registry is driven by hand rather than with `StateRestorationTester` because the tester
     * saves and restores in one step, so the results would still be on screen at restore time and
     * the entry would be consumed by the query it belongs to — which is the case that already works.
     */
    @Test
    fun `a search made after process death does not inherit the previous reader's position`() {
        var registry by mutableStateOf(SaveableStateRegistry(restoredValues = null) { true })
        var state by mutableStateOf<SearchUiState>(
            SearchUiState.Results(
                query = "browser",
                apps = List(40) { app(it) },
                shortfalls = emptyList(),
                hasMore = false,
            ),
        )

        // Removed and put back rather than `key`-ed on the registry, and that distinction is the
        // test: a `key` contributes to the composite key hash, which is the very thing a saved entry
        // is looked up by. Keyed on the registry, the entry saved by the first process would be
        // searched for under a different name in the second — the value would never be found, and a
        // screen that inherits it would pass anyway.
        var alive by mutableStateOf(true)
        rule.setContent {
            if (alive) {
                CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                    MultiStoreTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
                        SearchScreen(
                            uiState = state,
                            preferredLanguageTags = listOf("en"),
                            storeDisplayName = { it.wireName },
                            onQueryChange = {},
                            onAppClick = { _, _ -> },
                            onLoadMore = {},
                            onRetry = {},
                        )
                    }
                }
            }
        }

        rule.onNode(hasScrollToIndexAction()).performTouchInput { swipeUp() }
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(30)
        rule.waitForIdle()
        rule.onNodeWithText(title(0)).assertDoesNotExist()

        // The system saves the screen, then kills the process. What comes back is an idle search:
        // the results were never saved anywhere, so there is no list on screen to claim the entry.
        val saved = registry.performSave()
        alive = false
        rule.waitForIdle()
        state = SearchUiState.Idle()
        registry = SaveableStateRegistry(restoredValues = saved) { true }
        alive = true
        rule.waitForIdle()

        // Somebody searches for something else. It has to open at the top of its own results.
        state = SearchUiState.Results(
            query = "firefox",
            apps = List(40) { app(it) },
            shortfalls = emptyList(),
            hasMore = false,
        )
        rule.waitForIdle()

        rule.onNodeWithText(title(0)).assertIsDisplayed()
    }

    private fun title(index: Int) = "App %02d".format(index)

    private fun app(index: Int) = AggregatedApp(
        appKey = "pkg:com.example.app$index",
        listings = listOf(
            AggregatedListing(
                summary = StoreListingSummary(
                    storeId = StoreId.FDROID,
                    ref = StoreAppRef("com.example.app$index"),
                    title = title(index),
                ),
                origin = ResultOrigin.LOCAL_INDEX,
            ),
        ),
    )
}

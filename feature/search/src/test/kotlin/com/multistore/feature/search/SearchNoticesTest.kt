package com.multistore.feature.search

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.multistore.core.common.result.AppError
import com.multistore.core.data.repository.StoreShortfall
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
 * Putting the "some stores did not answer" notice away.
 *
 * The notice is right to exist — a list that does not say so makes an incomplete result look
 * complete — but it sits above the results and cannot be scrolled off, so on a search where a store
 * is paused it costs a screenful on every query. The X does not switch the feature off: it silences
 * **this** question.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SearchNoticesTest {

    @Suppress("DEPRECATION")
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `the X puts the shortfall notice away`() {
        setContent()

        rule.onNodeWithText(SHORTFALL_TITLE).assertIsDisplayed()
        rule.onNodeWithContentDescription(DISMISS).performClick()

        rule.onNodeWithText(SHORTFALL_TITLE).assertDoesNotExist()
    }

    /**
     * A new query brings it back.
     *
     * This is the whole reason the dismissal remembers *which* query it was made for instead of being
     * a flag. A flag would silence every search from then on — a different promise from the one an X
     * on one notice makes — and the stores that failed to answer the next question are news the user
     * has not seen yet.
     */
    @Test
    fun `a new query brings the notice back`() {
        val state = setContent()

        rule.onNodeWithContentDescription(DISMISS).performClick()
        rule.onNodeWithText(SHORTFALL_TITLE).assertDoesNotExist()

        state.value = results("firefox")
        rule.waitForIdle()

        rule.onNodeWithText(SHORTFALL_TITLE).assertIsDisplayed()
    }

    /**
     * A store answering late does not push the list down.
     *
     * The reported symptom, and its real cause. `LazyColumn` anchors its position to the **key** of
     * the first visible item and restores that key to the top after the data changes; aggregation
     * reorders on every store that answers, so a late arrival ranked above the anchor is inserted
     * over it, the list scrolls to keep the anchor in place, and rows appear above the first visible
     * one. Nobody scrolled: the search opened part-way down its own results.
     */
    @Test
    fun `a store answering late does not push the list down`() {
        // Ten results, and the reader has not touched them.
        val state = setContent(apps = 10, firstIndex = 20)
        rule.onNodeWithText("App 20").assertIsDisplayed()

        // A slower store answers, and its results rank above the ones on screen: twenty rows are
        // inserted *before* the anchor. This is the interleaving, not an invented one — the ordering
        // is applied to the aggregated list, so every arrival can reorder it.
        state.value = results("browser", apps = 30, firstIndex = 0)
        rule.waitForIdle()

        rule.onNodeWithText("App 0").assertIsDisplayed()
    }

    /** A new search starts at the top of its own results, whatever the reader was doing before. */
    @Test
    fun `a new query starts at the top of the list`() {
        val state = setContent(apps = 30)

        rule.onNode(hasScrollToIndexAction()).performTouchInput { swipeUp() }
        rule.waitForIdle()
        rule.onNodeWithText("App 0").assertDoesNotExist()

        state.value = results("firefox", apps = 30)
        rule.waitForIdle()

        rule.onNodeWithText("App 0").assertIsDisplayed()
    }

    /**
     * Once the reader has scrolled, the list is theirs.
     *
     * The other half, and the one that keeps the fix from becoming its own defect: re-pinning on
     * every arrival would yank someone back to the top while they are reading, which is what
     * `LazyColumn`'s anchoring is right about.
     */
    @Test
    fun `after the reader scrolls, an arrival does not pull them back to the top`() {
        val state = setContent(apps = 30, firstIndex = 0)

        rule.onNode(hasScrollToIndexAction()).performTouchInput { swipeUp() }
        rule.waitForIdle()
        rule.onNodeWithText("App 0").assertDoesNotExist()

        // Same query, one more store's worth of results.
        state.value = results("browser", apps = 40, firstIndex = 0)
        rule.waitForIdle()

        rule.onNodeWithText("App 0").assertDoesNotExist()
    }

    private fun firstTitle(index: Int) = "App $index"

    /** Returns the state, so a test can move the screen to another query after composing it. */
    private fun setContent(apps: Int = 1, firstIndex: Int = 0): MutableState<SearchUiState> {
        val state = mutableStateOf<SearchUiState>(results("browser", apps, firstIndex))
        rule.setContent {
            MultiStoreTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
                SearchScreen(
                    uiState = state.value,
                    preferredLanguageTags = listOf("en"),
                    storeDisplayName = { it.wireName },
                    onQueryChange = {},
                    onAppClick = { _, _ -> },
                    onLoadMore = {},
                    onRetry = {},
                )
            }
        }
        return state
    }

    /**
     * [apps] rows whose titles start at [firstIndex].
     *
     * The offset is what lets a test insert rows *above* the ones already on screen, keys and all,
     * which is what a late store answering actually does.
     */
    private fun results(query: String, apps: Int = 1, firstIndex: Int = 0) = SearchUiState.Results(
        query = query,
        apps = List(apps) { position ->
            val index = firstIndex + position
            AggregatedApp(
                appKey = "pkg:app.$index",
                listings = listOf(
                    AggregatedListing(
                        summary = StoreListingSummary(
                            storeId = StoreId.FDROID,
                            ref = StoreAppRef("app.$index"),
                            title = "App $index",
                        ),
                        origin = ResultOrigin.LOCAL_INDEX,
                    ),
                ),
            )
        },
        // One notice, not two: `answered == queried` keeps the "still arriving" banner out, so the
        // content description below is unambiguous.
        shortfalls = listOf(StoreShortfall(storeId = StoreId.MODYOLO, error = AppError.Network(null))),
        hasMore = false,
    )

    private companion object {
        const val SHORTFALL_TITLE = "Some stores did not answer"
        const val DISMISS = "Hide this notice"
    }
}

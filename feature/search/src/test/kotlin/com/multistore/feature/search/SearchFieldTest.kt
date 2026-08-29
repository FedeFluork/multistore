package com.multistore.feature.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTextInput
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
 * The search field, typed into.
 *
 * It is the only test in the repository that delivers a keystroke, and it exists because the defect
 * it guards was invisible to everything else: nine guardrails, a full ViewModel suite and four
 * goldens all stayed green while the field was unusable. The reason is timing — no other test ever
 * changes the state *while* a character is being typed, and a golden photographs a screen that has
 * settled.
 *
 * ### What it pins
 *
 * `SearchUiState.query` answers "which query are these results for?"; the field's text answers "what
 * is being typed?". They agree on a settled screen and diverge exactly while a search is in flight,
 * which is when someone is still typing. Binding the editor to the first was the bug: the `String`
 * overload of `BasicTextField` keeps the selection in a private state and recombines it as
 * `copy(text = value)`, so a `value` that did not come from the last keystroke arrives with the old
 * caret offset, which `TextFieldValue` then coerces into the shorter string — the caret jumping back
 * one or more letters, exactly as reported.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SearchFieldTest {

    @Suppress("DEPRECATION")
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `an answer arriving for an older query does not rewrite the field`() {
        // Two states, as the screen has them: what the results are about, and what is in the field.
        // Only the keystroke writes the second one — that is the whole fix.
        var uiState by mutableStateOf<SearchUiState>(SearchUiState.Idle())
        var text by mutableStateOf("")

        rule.setContent {
            MultiStoreTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
                SearchScreen(
                    uiState = uiState,
                    query = text,
                    preferredLanguageTags = listOf("en"),
                    storeDisplayName = { it.wireName },
                    onQueryChange = { text = it },
                    onAppClick = { _, _ -> },
                    onLoadMore = {},
                    onRetry = {},
                )
            }
        }

        rule.onNode(hasSetTextAction()).performTextInput("firefox")
        rule.onNode(hasSetTextAction()).assertTextContains("firefox")

        // The store that was asked about "fire" answers now, four letters late. This is the write
        // that used to land in the editor.
        uiState = SearchUiState.Results(
            query = "fire",
            apps = listOf(app()),
            shortfalls = emptyList(),
            hasMore = false,
        )
        rule.waitForIdle()

        // The typed word survived. Bound to `uiState.query`, the field would read "fire" here and the
        // caret would have been coerced back into it.
        rule.onNode(hasSetTextAction()).assertTextContains("firefox")
    }

    private fun app() = AggregatedApp(
        appKey = "pkg:org.mozilla.fennec_fdroid",
        listings = listOf(
            AggregatedListing(
                summary = StoreListingSummary(
                    storeId = StoreId.FDROID,
                    ref = StoreAppRef("org.mozilla.fennec_fdroid"),
                    title = "Fennec",
                ),
                origin = ResultOrigin.LOCAL_INDEX,
            ),
        ),
    )
}

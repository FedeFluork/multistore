package com.multistore.feature.storelisting

import androidx.compose.runtime.Composable
import com.multistore.core.model.Category
import com.multistore.core.model.LocalizedText
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.model.StoreListingSummary
import com.multistore.core.model.ThemeMode
import com.multistore.core.testing.ScreenshotTest
import com.multistore.core.testing.completePage
import kotlinx.coroutines.flow.flowOf
import org.junit.Test

/**
 * Screenshots of [StoreListingScreen] in both themes.
 *
 * The golden photographs a **selected** category, which is what makes this screen different from any
 * other list: the picker's active chip, where one can see whether the selected state's contrast holds up
 * in dark mode too.
 *
 * **The "Load more" button is gone**, and with it its golden: the list is served by Paging, which loads
 * the next page when the scroll gets close to the end. What is left at the bottom is a spinner, and it
 * appears only if loading is slower than scrolling — on a local index, almost never. Photographing it
 * would mean constructing a slowness that does not exist.
 *
 * The open menu is **not** in the golden, deliberately: a `DropdownMenu` draws into a window of its own,
 * which a capture of the composition does not contain. Trying it here would give an image identical to
 * this one and the false impression of having compared it.
 */
class StoreListingScreenScreenshotTest : ScreenshotTest() {

    @Test
    fun lightTheme() = capture(SCREEN_NAME, ThemeMode.LIGHT) { Content() }

    @Test
    fun darkTheme() = capture(SCREEN_NAME, ThemeMode.DARK) { Content() }

    @Composable
    private fun Content() {
        StoreListingScreen(
            uiState = StoreListingUiState(
                storeName = "F-Droid",
                categories = listOf(
                    category("System", "System", 629),
                    category("Internet", "Internet", 614),
                    category("Multimedia", "Multimedia", 494),
                ),
                selectedCategoryId = "Internet",
            ),
            // `PagingData.from` rather than a `Pager`: an already-complete list, with no source and no
            // loads in flight, which is exactly what a golden should photograph.
            apps = flowOf(
                completePage(
                    listOf(
                        app("org.mozilla.fennec_fdroid", "Fennec", "A Firefox build without proprietary bits."),
                        app("org.torproject.torbrowser", "Tor Browser", "Browse the web anonymously."),
                        app(
                            "com.stoutner.privacybrowser.standard",
                            "Privacy Browser",
                            "A browser that respects privacy.",
                        ),
                    ),
                ),
            ),
            preferredLanguageTags = listOf("en"),
            onAppClick = { _, _ -> },
            onSelectCategory = {},
            onBack = {},
        )
    }

    private fun category(id: String, name: String, appCount: Int) =
        Category(id = id, name = LocalizedText(mapOf("en" to name)), appCount = appCount)

    private fun app(ref: String, title: String, summary: String) = StoreListingSummary(
        storeId = StoreId.FDROID,
        ref = StoreAppRef(ref),
        title = title,
        packageName = ref,
        summary = LocalizedText(mapOf("en" to summary)),
    )

    private companion object {
        const val SCREEN_NAME = "StoreListingScreen"
    }
}

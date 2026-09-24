package com.multistore.feature.settings

import androidx.compose.runtime.Composable
import com.multistore.core.model.ThemeMode
import com.multistore.core.testing.ScreenshotTest
import org.junit.Test

/**
 * Which stores MultiStore searches, and the three states a card can be in.
 *
 * It is the **comparison** between them that says whether the UI works:
 *
 * - **selected** — tinted fill plus a border, because a fill alone is a contrast difference and that
 *   is exactly what a reader who cannot rely on colour loses;
 * - **not selected** — the same card without either, which has to read as off at a glance rather
 *   than as the same card slightly paler;
 * - **breaker open** — its line in red, which must stand out from the description above it without
 *   becoming the only thing on the card one sees.
 *
 * The third is the one the dark theme can ruin: `colorScheme.error` over a tinted container is a
 * different red from the one over the surface, and if nobody looks, nobody knows.
 *
 * ### The second pair, with a tab open, is not decoration
 *
 * The tab labels carry `selected/total` **for their own group**, and the select-all checkbox
 * reflects the group rather than the catalogue. Neither can be seen on "All", where the group *is*
 * the catalogue and the two numbers coincide — so the case where they differ is the one worth
 * photographing. The Modified tab is chosen because it is the only group with more than one store
 * off, which is also the only way the checkbox's unchecked state gets into a golden.
 */
class StoreChooserScreenScreenshotTest : ScreenshotTest() {

    @Test
    fun lightTheme() = capture(SCREEN_NAME, ThemeMode.LIGHT) { Content() }

    @Test
    fun darkTheme() = capture(SCREEN_NAME, ThemeMode.DARK) { Content() }

    @Test
    fun tabLight() = capture(TAB_SCREEN_NAME, ThemeMode.LIGHT) { Content(StoreTab.MODIFIED) }

    @Test
    fun tabDark() = capture(TAB_SCREEN_NAME, ThemeMode.DARK) { Content(StoreTab.MODIFIED) }

    @Composable
    private fun Content(tab: StoreTab = StoreTab.ALL) {
        StoreChooserScreen(
            stores = StoreSamples.NINE,
            onBack = {},
            onSetEnabled = { _, _ -> },
            onSetAll = { _, _ -> },
            initialTab = tab,
        )
    }

    private companion object {
        const val SCREEN_NAME = "StoreChooserScreen"
        const val TAB_SCREEN_NAME = "StoreChooserScreen_tab"
    }
}

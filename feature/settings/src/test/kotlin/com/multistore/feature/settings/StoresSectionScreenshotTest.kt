package com.multistore.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import com.multistore.core.model.ThemeMode
import com.multistore.core.testing.ScreenshotTest
import org.junit.Test

/**
 * The Stores section, which is one row.
 *
 * Nearly empty on purpose: the list it used to hold lives on a screen of its own, and
 * what is left is a line with a count. Photographing it is still worth it, because that count is the
 * only place on the settings screen that says how many sources are being searched — and it is
 * assembled from the same sample the chooser's golden uses, so the two cannot disagree.
 */
class StoresSectionScreenshotTest : ScreenshotTest() {

    @Test
    fun lightTheme() = capture(SCREEN_NAME, ThemeMode.LIGHT) { Content() }

    @Test
    fun darkTheme() = capture(SCREEN_NAME, ThemeMode.DARK) { Content() }

    /**
     * The `Surface` is not decoration: in the real screen the section sits inside a `Scaffold`, so it
     * paints no background of its own. Without it the dark golden would come out with light text on a
     * white background — the same trap already met on the Storage section.
     */
    @Composable
    private fun Content() {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column {
                StoresSection(stores = StoreSamples.NINE, onOpenStoreChooser = {})
            }
        }
    }

    private companion object {
        const val SCREEN_NAME = "StoresSection"
    }
}

package com.multistore.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import com.multistore.core.model.MyAppsSettings
import com.multistore.core.model.MyAppsSort
import com.multistore.core.model.ThemeMode
import com.multistore.core.testing.ScreenshotTest
import org.junit.Test

/**
 * The "My apps" section, photographed on its own.
 *
 * ### Why it needs a golden of its own
 *
 * The same reason the Storage and Stores sections have one: `SettingsScreen` is taller than the
 * device, and this section sits below the fold. Measured by adding it and running
 * `verifyRoborazziDebug` — the whole-screen golden did not move by a pixel, which is precisely the
 * failure this project has already met once and named: green on a screen that had changed. (The
 * *search* golden did move, because the entry's description contains the word being searched for,
 * and that is the internal search working rather than the section being visible.)
 *
 * ### Why the chosen value is not the default
 *
 * The row shows the current criterion, and with `NAME` — the zero value — the golden would be right
 * for the one case where nobody has chosen anything. Photographing a criterion somebody picked is
 * what shows the row is reading the setting rather than printing a constant, and "Updatable first"
 * is also the longest of the four in most of the five languages, i.e. the one that would wrap.
 */
class MyAppsSectionScreenshotTest : ScreenshotTest() {

    @Test
    fun lightTheme() = capture(SCREEN_NAME, ThemeMode.LIGHT) { Content() }

    @Test
    fun darkTheme() = capture(SCREEN_NAME, ThemeMode.DARK) { Content() }

    /**
     * The `Surface` is not decoration: in the real screen the section sits inside a `Scaffold`, so it
     * paints no background of its own. Captured bare, the dark golden would come out with the dark
     * theme's light text on a white background — the trap already met on two other sections.
     */
    @Composable
    private fun Content() {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column {
                MyAppsSection(
                    myApps = MyAppsSettings(sort = MyAppsSort.UPDATABLE_FIRST),
                    filter = SettingsFilter.NONE,
                    onMyAppsSortChange = {},
                )
            }
        }
    }

    private companion object {
        const val SCREEN_NAME = "MyAppsSection"
    }
}

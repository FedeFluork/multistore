package com.multistore.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.multistore.core.model.ModifiedBuild
import com.multistore.core.model.ThemeMode
import com.multistore.core.testing.ScreenshotTest
import org.junit.Test

/**
 * The rework badge, in its two visible states and in the two themes.
 *
 * It gets a golden of its own rather than riding on a screen's, for the reason `:core:ui` grew
 * Roborazzi in the first place: rule 3 speaks of "every UI component", and this one is drawn by
 * three surfaces in two features — a search row, a listing header and the comparison table — so a
 * golden inside any one of them would photograph that screen's layout rather than the badge.
 *
 * ### The third state is on purpose absent, and that is the assertion
 *
 * [ModifiedBuild.NONE] draws nothing at all, so it cannot be photographed — and a golden with an
 * empty third slot would look identical to one whose third badge failed to lay out. What holds that
 * half is `ModifiedBuildBadgeTest`, which asserts on the tree instead of on pixels.
 *
 * What the picture does hold is what only a picture can: that the tertiary container is legible
 * against the page in **both** palettes, which is exactly where a hand-picked colour would fail,
 * and that the two labels do not wrap.
 */
class ModifiedBuildBadgeScreenshotTest : ScreenshotTest() {

    @Test
    fun lightTheme() = capture(SCREEN_NAME, ThemeMode.LIGHT) { Content() }

    @Test
    fun darkTheme() = capture(SCREEN_NAME, ThemeMode.DARK) { Content() }

    @Composable
    private fun Content() {
        // Without the `Surface` the golden has a transparent background, and the contrast the
        // picture exists to show would not be in it.
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ModifiedBuildBadge(state = ModifiedBuild.DECLARED, storeDisplayName = "an1")
                ModifiedBuildBadge(state = ModifiedBuild.POSSIBLE, storeDisplayName = "APKMody")
            }
        }
    }

    private companion object {
        const val SCREEN_NAME = "ModifiedBuildBadge"
    }
}

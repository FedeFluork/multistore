package com.multistore.core.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.multistore.core.model.ThemeMode
import com.multistore.core.testing.ScreenshotTest
import org.junit.Test

/**
 * The screenshot strip, in the two themes.
 *
 * ### What this golden can and cannot say
 *
 * There is no network in a screenshot test, so every cell photographs its **placeholder**. That is
 * not a weakness of the golden, it is the reason the cells have a fixed size at all: a strip laid
 * out from the loaded bitmap's intrinsic width would be a row of zero-width boxes here, and a golden
 * of nothing cannot catch a strip that stopped laying out. What it does catch is exactly what it can
 * catch — the cell's shape, the gap between cells, the corner radius, and that the placeholder is
 * legible against the page in **both** palettes, which is where a hand-picked grey would fail.
 *
 * The reader is not photographed and that is deliberate rather than an omission: it is an opaque
 * black surface with somebody else's image on it, so the only themed thing on it is the close
 * button — whose colours are asserted by the accessibility check that runs on every capture, not by
 * an eye on a picture of an unloaded image.
 */
class ScreenshotGalleryScreenshotTest : ScreenshotTest() {

    @Test
    fun lightTheme() = capture(SCREEN_NAME, ThemeMode.LIGHT) { Content() }

    @Test
    fun darkTheme() = capture(SCREEN_NAME, ThemeMode.DARK) { Content() }

    @Composable
    private fun Content() {
        // The `Surface` underneath is not framing: without it the golden would have a transparent
        // background and the placeholder's contrast would be invisible in both themes.
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(contentAlignment = Alignment.CenterStart) {
                ScreenshotGallery(
                    // Five, and more than fit on the device: the strip's whole point is that it
                    // scrolls, and a golden with three cells and empty space to the right would
                    // photograph a row, not a strip.
                    urls = List(5) { "https://example.invalid/shot-$it.png" },
                    contentPadding = PaddingValues(horizontal = 16.dp),
                )
            }
        }
    }

    private companion object {
        const val SCREEN_NAME = "ScreenshotGallery"
    }
}

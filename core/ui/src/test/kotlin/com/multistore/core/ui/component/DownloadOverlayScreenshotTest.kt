package com.multistore.core.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.multistore.core.model.ThemeMode
import com.multistore.core.testing.ScreenshotTest
import org.junit.Test

/**
 * The progress card, in the two themes.
 *
 * It lives in `:core:ui` and not in a feature because that is where it lives: it is drawn by `:app`
 * above the NavHost, so there is no screen containing it and no screen golden that would photograph
 * it. It is also why this module gained Roborazzi.
 *
 * The golden photographs **two** transfers, one with a known percentage and one without. It is not a
 * textbook case: four stores out of nine do not declare the file's size, so the indeterminate bar is
 * half the time — and with a single download the title would say the app's name instead of the
 * number, i.e. the other branch.
 */
class DownloadOverlayScreenshotTest : ScreenshotTest() {

    @Test
    fun lightTheme() = capture(SCREEN_NAME, ThemeMode.LIGHT) { Content() }

    @Test
    fun darkTheme() = capture(SCREEN_NAME, ThemeMode.DARK) { Content() }

    @Composable
    private fun Content() {
        // The `Surface` underneath is not framing: without it, the golden would have a transparent
        // background and the card's contrast would not be visible in either theme.
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(contentAlignment = Alignment.BottomCenter) {
                DownloadOverlay(
                    downloads = listOf(
                        DownloadProgress(id = 1, title = "Telegram", fraction = 0.42f),
                        DownloadProgress(id = 2, title = "Firefox", fraction = null),
                    ),
                    onDismiss = {},
                )
            }
        }
    }

    private companion object {
        const val SCREEN_NAME = "DownloadOverlay"
    }
}

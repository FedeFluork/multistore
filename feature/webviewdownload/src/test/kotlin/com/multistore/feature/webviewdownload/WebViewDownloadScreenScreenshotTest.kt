package com.multistore.feature.webviewdownload

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.multistore.core.model.ThemeMode
import com.multistore.core.testing.ScreenshotTest
import com.multistore.store.api.DownloadHint
import org.junit.Test

/**
 * Screenshots of [WebViewDownloadScreen] in both themes.
 *
 * In place of the WebView there is a placeholder, and that is not a shortcut: in Robolectric the WebView
 * draws nothing real, so a golden including it would photograph an empty rectangle **and pass it off as
 * the page** — and the day the page stopped loading, the comparison would stay green. What this screen
 * has to guarantee is the frame: the instructions, the current host, the progress, the colours in both
 * themes. That photographs.
 *
 * `SOLVE_CAPTCHA` among the five hints because it is the longest: it is the case where the banner wraps,
 * that is the one where wrong spacing shows.
 */
class WebViewDownloadScreenScreenshotTest : ScreenshotTest() {

    @Test
    fun lightTheme() = capture(SCREEN_NAME, ThemeMode.LIGHT) { Content() }

    @Test
    fun darkTheme() = capture(SCREEN_NAME, ThemeMode.DARK) { Content() }

    @Composable
    private fun Content() {
        WebViewDownloadScreen(
            uiState = WebViewDownloadUiState(
                title = "Aurora Store",
                pageUrl = "https://example.test/aurora-store/download",
                hint = DownloadHint.SOLVE_CAPTCHA,
                pageProgress = 65,
                currentHost = "example.test",
            ),
            onBack = {},
            onDismissError = {},
        ) { contentModifier ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = contentModifier.then(Modifier.fillMaxSize()),
            ) {}
        }
    }

    private companion object {
        const val SCREEN_NAME = "WebViewDownloadScreen"
    }
}

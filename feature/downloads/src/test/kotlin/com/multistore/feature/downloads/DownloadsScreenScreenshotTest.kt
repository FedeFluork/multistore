package com.multistore.feature.downloads

import androidx.compose.runtime.Composable
import com.multistore.core.model.ThemeMode
import com.multistore.core.testing.ScreenshotTest
import org.junit.Test

/**
 * Screenshots of [DownloadsScreen] in both themes.
 *
 * Both themes, always: `ScreenshotCoverageTest` in `:guardrails` fails if a screen has only one.
 *
 * The golden photographs **all three groups inhabited at once**, which is rare in use and is exactly
 * the point: the section headings, all three buttons and the three different sentences a history row
 * can carry all have to be looked at. A state showing one group at a time would need three
 * screenshots to say the same thing, and the relationship between them — which group comes first,
 * how the ready row stands out from the others — would be in none of them.
 *
 * "In progress" holds two rows, and they are the two halves of cancelling: one still running, which
 * offers Cancel, and one already parked, which offers the Delete that gets it off the screen. It is
 * also the only place the asymmetry can be checked — three outlined buttons and one filled, the
 * filled one always Install.
 *
 * The second golden is the empty state, and it is not padding: it is the screen most users see on the
 * day they install the app, and it is the one no other capture would ever contain.
 */
class DownloadsScreenScreenshotTest : ScreenshotTest() {

    @Test
    fun lightTheme() = capture(SCREEN_NAME, ThemeMode.LIGHT) { Content() }

    @Test
    fun darkTheme() = capture(SCREEN_NAME, ThemeMode.DARK) { Content() }

    @Test
    fun emptyLight() = capture(EMPTY_SCREEN_NAME, ThemeMode.LIGHT) { EmptyContent() }

    @Test
    fun emptyDark() = capture(EMPTY_SCREEN_NAME, ThemeMode.DARK) { EmptyContent() }

    @Composable
    private fun Content() {
        DownloadsScreen(
            uiState = PREVIEW_STATE,
            confirmation = null,
            onInstall = {},
            onCancel = {},
            onDelete = {},
            onClearHistory = {},
            onConfirm = {},
            onDismissConfirmation = {},
        )
    }

    @Composable
    private fun EmptyContent() {
        DownloadsScreen(
            uiState = DownloadsUiState.Empty,
            confirmation = null,
            onInstall = {},
            onCancel = {},
            onDelete = {},
            onClearHistory = {},
            onConfirm = {},
            onDismissConfirmation = {},
        )
    }

    private companion object {
        const val SCREEN_NAME = "DownloadsScreen"
        const val EMPTY_SCREEN_NAME = "DownloadsScreenEmpty"
    }
}

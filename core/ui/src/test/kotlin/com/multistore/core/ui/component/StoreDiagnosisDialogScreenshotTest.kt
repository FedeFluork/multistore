package com.multistore.core.ui.component

import androidx.compose.runtime.Composable
import com.multistore.core.common.net.FailureKind
import com.multistore.core.common.net.StoreDiagnosis
import com.multistore.core.common.net.StoreFault
import com.multistore.core.model.StoreHealthState
import com.multistore.core.model.StoreId
import com.multistore.core.model.ThemeMode
import com.multistore.core.testing.ScreenshotTest
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import org.junit.Test

/**
 * A store's health, in the two themes.
 *
 * It gets a golden here rather than inside either of its two callers, and that is the same reason
 * the component lives in `:core:ui`: Settings and the search notice both open it, a `:feature:*`
 * never depends on another, and a picture taken inside one of them would be a picture of that
 * screen's layout.
 *
 * ### What the picture holds that no state test can
 *
 * Four paragraphs of different lengths inside an `AlertDialog`, which is the surface most likely to
 * run out of height on a phone — and one of them, the run of faults, is `error`-coloured against the
 * dialog's own container in both palettes.
 */
class StoreDiagnosisDialogScreenshotTest : ScreenshotTest() {

    @Test
    fun lightTheme() = capture(SCREEN_NAME, ThemeMode.LIGHT) { Content() }

    @Test
    fun darkTheme() = capture(SCREEN_NAME, ThemeMode.DARK) { Content() }

    @Composable
    private fun Content() {
        StoreDiagnosisDialog(
            diagnosis = StoreDiagnosis(
                storeId = StoreId.APKMIRROR,
                state = StoreHealthState.OPEN,
                // Two days of faults after a success three days ago: the two numbers together are
                // the sentence this dialog exists for, and the picture is where their lengths can
                // be seen against each other.
                lastSuccessAt = NOW - 3.days,
                failingSince = NOW - 2.days,
                lastFailure = StoreFault(
                    kind = FailureKind.PARSE,
                    at = NOW - 4.hours,
                    selector = "#content .listWidget",
                ),
                openUntil = NOW + 4.hours,
                // Two distinct selectors, because that is what "the markup changed" looks like —
                // one selector failing a hundred times is one malformed page.
                parseFailureSelectors = setOf("#content .listWidget", ".appRow h5 a"),
            ),
            storeName = "APKMirror",
            onDismiss = {},
            // Fixed: a golden that read the clock would not be comparable with itself, which is the
            // reason this parameter exists at all.
            now = NOW,
        )
    }

    private companion object {
        const val SCREEN_NAME = "StoreDiagnosisDialog"
        val NOW: Instant = Instant.parse("2026-09-06T12:00:00Z")
    }
}

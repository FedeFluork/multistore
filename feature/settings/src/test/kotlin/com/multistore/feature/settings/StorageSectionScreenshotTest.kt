package com.multistore.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import com.multistore.core.model.CatalogRetention
import com.multistore.core.model.StorageLevel
import com.multistore.core.model.StorageSettings
import com.multistore.core.model.StorageUsage
import com.multistore.core.model.ThemeMode
import com.multistore.core.testing.ScreenshotTest
import org.junit.Test

/**
 * The Storage section at its two moments: at rest, and while something is happening.
 *
 * The `SettingsScreen` golden does **not** cover it, and it is worth saying why rather than letting
 * anyone believe it does: the screen is taller than the device and the Storage section is the last
 * one, so in that capture it falls below the fold. A golden that shows part of a screen and is counted
 * as covering the whole is worse than no golden.
 *
 * The two copies are the two **moments**, and together they cover every state a row can be in: at rest
 * all the buttons and the chosen values; in action one level being cleared, one with "freed", one with
 * "there was nothing", and the compaction finished.
 *
 * The sizes are the ones **measured on the device** and not round numbers: that is the case where the
 * golden earns its keep — four values of different orders of magnitude, one of them two digits of
 * megabytes, that must line up in a column without wrapping in five languages.
 *
 * `captureRoboImage` photographs the composition root, which the device crops to its own height: on a
 * Pixel 7 the section does not fit even **once**, and the first recording produced an image cut in
 * half through the fifth row. Hence the `+h2600dp` qualifier, which raises **only the canvas height**,
 * not the width: what the golden has to verify — that four numbers and five descriptions line up in a
 * column without wrapping badly — depends on the width, which stays the Pixel 7's. In the real screen
 * the section sits inside a scrolling list, so a greater height is not a condition the user will never
 * meet: it is precisely its intrinsic height.
 */
@org.robolectric.annotation.Config(qualifiers = "+h2600dp")
class StorageSectionScreenshotTest : ScreenshotTest() {

    @Test
    fun lightTheme() = capture(SCREEN_NAME, ThemeMode.LIGHT) { Content() }

    @Test
    fun darkTheme() = capture(SCREEN_NAME, ThemeMode.DARK) { Content() }

    /**
     * The `Surface` is not decoration: in the real screen the section sits inside a `Scaffold`, so it
     * paints no background of its own. Captured bare, the dark golden came out with the dark theme's
     * light text on a white background — unreadable, and not the app's fault. A golden showing
     * something the user will never see is not a baseline.
     */
    @Composable
    private fun Content() {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column {
                StorageSection(
                    reclaim = ReclaimUiState.Idle,
                    onReclaimSpace = {},
                    storage = StorageUiState(settings = StorageSettings(), usage = MEASURED),
                )
                StorageSection(
                    reclaim = ReclaimUiState.Done(freedBytes = 41_900_000),
                    onReclaimSpace = {},
                    storage = StorageUiState(
                        settings = StorageSettings(
                            keepApkAfterInstall = true,
                            imageCacheMaxBytes = StorageSettings.megabytes(64),
                            catalogRetention = CatalogRetention.KEEP,
                        ),
                        usage = MEASURED,
                        busy = StorageLevel.IMAGES,
                        freed = mapOf(
                            StorageLevel.STAGED_APKS to 29_593_841L,
                            // Zero bytes is not a saving of zero: it is "there was nothing to do", and
                            // the sentence is a different one. The golden is where to check that.
                            StorageLevel.PAGES to 0L,
                        ),
                    ),
                )
            }
        }
    }

    private companion object {
        const val SCREEN_NAME = "StorageSection"

        /** The four numbers measured on a device, not four round numbers. */
        val MEASURED = StorageUsage(
            catalogBytes = 65_314_816,
            imagesBytes = 4_493_312,
            pagesBytes = 1_617_920,
            stagedApkBytes = 29_593_841,
        )
    }
}

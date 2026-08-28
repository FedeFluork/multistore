package com.multistore.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import com.multistore.core.model.InstallSettings
import com.multistore.core.model.InstallerAvailability
import com.multistore.core.model.InstallerKind
import com.multistore.core.model.InstallerPreference
import com.multistore.core.model.ThemeMode
import com.multistore.core.testing.ScreenshotTest
import org.junit.Test

/**
 * The Installation section, which now has two rows rather than one.
 *
 * The `SettingsScreen` golden does **not** cover it, for the same reason already written for the
 * Storage section: the screen is taller than the device, and what falls below the fold in that
 * capture is verified by nobody. The defect would have gone unnoticed in a particularly unpleasant
 * way — the new row appeared without changing the existing golden by a single pixel, so
 * `verifyRoborazziDebug` stayed green on a screen that had changed.
 *
 * The "Allow app installation" row has **two** states that say opposite things and sit one on top of
 * the other in the same place: "Allowed" and "Not allowed". That is two words and a colour, exactly
 * the kind of difference a state test does not look at and a golden does.
 *
 * The permission is read by the row itself from the `PackageManager`, so here it is whatever
 * Robolectric answers for the test app — "not allowed". The two copies differ instead in the
 * **installer preference** and in which channels come out usable, which is the other thing this
 * section has to be able to say without lying: an unavailable channel stays visible and marked, it
 * does not vanish.
 */
class InstallationSectionScreenshotTest : ScreenshotTest() {

    @Test
    fun lightTheme() = capture(SCREEN_NAME, ThemeMode.LIGHT) { Content() }

    @Test
    fun darkTheme() = capture(SCREEN_NAME, ThemeMode.DARK) { Content() }

    /**
     * The `Surface` is not decoration: in the real screen the section sits inside a `Scaffold`, so it
     * paints no background of its own. Captured bare, the dark golden would come out with the dark
     * theme's light text on a white background.
     */
    @Composable
    private fun Content() {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column {
                InstallationSection(
                    installation = InstallSettings(),
                    filter = SettingsFilter.NONE,
                    installers = InstallerAvailability(usable = setOf(InstallerKind.SESSION)),
                    onInstallerPreferenceChange = {},
                )
                InstallationSection(
                    installation = InstallSettings(preference = InstallerPreference.SHIZUKU),
                    filter = SettingsFilter.NONE,
                    installers = InstallerAvailability(
                        usable = setOf(InstallerKind.SESSION, InstallerKind.SHIZUKU),
                    ),
                    onInstallerPreferenceChange = {},
                )
            }
        }
    }

    private companion object {
        const val SCREEN_NAME = "InstallationSection"
    }
}

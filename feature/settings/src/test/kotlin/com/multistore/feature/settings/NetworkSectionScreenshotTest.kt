package com.multistore.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.multistore.core.model.ChallengeStrategy
import com.multistore.core.model.NetworkSettings
import com.multistore.core.model.ThemeMode
import com.multistore.core.testing.ScreenshotTest
import org.junit.Test

/**
 * The three Network entries, which are **not visible** in the whole-screen golden.
 *
 * Same reason as [StorageSectionScreenshotTest], and worth repeating rather than letting anyone
 * believe `SettingsScreen_light.png` covers everything: the screen is taller than the device, the
 * Network section comes after Updates, Installation and Security, and in that capture it falls below
 * the fold. Recording the golden after adding two entries here left `SettingsScreen` unchanged by a
 * single pixel — which is exactly how a screen grows without any baseline noticing.
 *
 * The two states sit together because the row that matters is the strategy one: it is the only one in
 * the section with a **value** under the label, in `primary`, and the comparison between the two
 * themes is about that colour.
 */
class NetworkSectionScreenshotTest : ScreenshotTest() {

    @Test
    fun lightTheme() = capture(SCREEN_NAME, ThemeMode.LIGHT) { Content() }

    @Test
    fun darkTheme() = capture(SCREEN_NAME, ThemeMode.DARK) { Content() }

    /**
     * The `Surface` is there for the reason explained in [StorageSectionScreenshotTest].
     *
     * The `verticalScroll`, on the other hand, arrived with the fourth entry, and it is not cosmetic:
     * a `Column` that does not scroll measures its children with **the height that is left**, and when
     * that runs out the last row is squashed. The accessibility check noticed before anybody else — a
     * 40dp switch instead of 48 — and it was right twice over: in a column that does not scroll that
     * row would not even be reachable. In the real screen the section sits inside a scrolling list, so
     * this is the bench that resembles the app, not a way of silencing the check.
     */
    @Composable
    private fun Content() {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                // How the app starts: metered network denied, escalating up to the silent WebView,
                // assisted path available. It is the configuration proto3's zero value produces, and
                // the one that must stay that way.
                NetworkSection(network = NetworkSettings(), onMeteredNetworkAllowedChange = {})
                // The other extreme: no browser engine at all, in any form.
                NetworkSection(
                    network = NetworkSettings(
                        meteredNetworkAllowed = true,
                        challengeStrategy = ChallengeStrategy.CONSERVATIVE,
                        blockUserAssistedChallenge = true,
                    ),
                    onMeteredNetworkAllowedChange = {},
                )
            }
        }
    }

    private companion object {
        const val SCREEN_NAME = "NetworkSection"
    }
}

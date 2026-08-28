package com.multistore.feature.settings

import com.multistore.core.model.AppearanceSettings
import com.multistore.core.model.InstallSettings
import com.multistore.core.model.InstallerAvailability
import com.multistore.core.model.InstallerKind
import com.multistore.core.model.DiagnosticsSettings
import com.multistore.core.model.NetworkSettings
import com.multistore.core.model.NotificationSettings
import com.multistore.core.model.RemoteConfigSettings
import com.multistore.core.model.SearchSettings
import com.multistore.core.model.SecuritySettings
import com.multistore.core.model.ThemeMode
import com.multistore.core.model.VersionSettings
import com.multistore.core.model.UpdateSettings
import com.multistore.core.remoteconfig.RemoteConfigStatus
import com.multistore.core.testing.ScreenshotTest
import org.junit.Test

/**
 * Screenshots of [SettingsScreen] in both themes.
 *
 * Uses the ViewModel-free variant: a screenshot must depend only on the state we pass it, otherwise
 * the image changes when the DataStore changes and the comparison becomes unreliable.
 */
class SettingsScreenScreenshotTest : ScreenshotTest() {

    @Test
    fun lightTheme() = capture(SCREEN_NAME, ThemeMode.LIGHT) { Content() }

    @Test
    fun darkTheme() = capture(SCREEN_NAME, ThemeMode.DARK) { Content() }

    /**
     * The screen **with a search in progress**, which is a different state and not a variant.
     *
     * It photographs the three things search can get wrong and that no state test would see: a section
     * whose first entry was filtered out and that drags a dangling divider along, a heading left above
     * nothing, and the spacing between sections that are not adjacent in the full list. The query is
     * "install" because it appears in **different** sections — Installation and Updates — so it
     * exercises precisely the case where the filter has to skip entries in the middle rather than cut
     * at the end.
     */
    @Test
    fun searchLight() = capture(SEARCH_SCREEN_NAME, ThemeMode.LIGHT) { Content(query = QUERY) }

    @Test
    fun searchDark() = capture(SEARCH_SCREEN_NAME, ThemeMode.DARK) { Content(query = QUERY) }

    @androidx.compose.runtime.Composable
    private fun Content(query: String = "") {
        SettingsScreen(
            searchQuery = query,
            uiState = SettingsUiState.Ready(
                appearance = AppearanceSettings(
                    themeMode = ThemeMode.SYSTEM,
                    dynamicColor = true,
                    languageTag = "it",
                ),
                // Both security switches at their default, that is off: the golden photographs the
                // safe configuration, and that is the one that must stay that way.
                // The default: daily, no charging requirement, notices on, and the two automations
                // off.
                updates = UpdateSettings(),
                installation = InstallSettings(),
                security = SecuritySettings(),
                network = NetworkSettings(),
                // Off, which is the default: whoever opens the app for the first time gets no betas
                // chosen by us, and the golden photographs that configuration.
                versions = VersionSettings(),
                remoteConfig = RemoteConfigSettings(),
                // Off, which is the default and the safe value: the golden photographs the
                // configuration the app starts with.
                search = SearchSettings(),
                // The four notices at their default, that is all on: the golden photographs the app
                // that speaks, which is the one whoever never opens Settings gets.
                notifications = NotificationSettings(),
                diagnostics = DiagnosticsSettings(),
            ),
            onThemeModeChange = {},
            onDynamicColorChange = {},
            onLanguageChange = {},
            onAllowUnverifiedHashChange = {},
            onAllowSignerMismatchChange = {},
            onMeteredNetworkAllowedChange = {},
            // The ordinary device: no silent channel. It is also the state in which "install by
            // itself" is disabled with its explanation, which is the section's most delicate row — a
            // long two-line text under a greyed-out label.
            installers = InstallerAvailability(
                supported = setOf(InstallerKind.SESSION, InstallerKind.SHIZUKU, InstallerKind.ROOT),
                usable = setOf(InstallerKind.SESSION),
            ),
            // The state at first launch, before any download: compiled defaults and no attempt. It is
            // what anybody installing the app today sees, and also the only state that does not depend
            // on what is on the CDN at the moment the golden is recorded.
            configStatus = RemoteConfigStatus(),
        )
    }

    private companion object {
        const val SCREEN_NAME = "SettingsScreen"
        const val SEARCH_SCREEN_NAME = "SettingsScreen_search"

        /**
         * A single word, present in more than one section.
         *
         * In English — the goldens' locale — "install" appears in the Installation section and in the
         * "install updates by itself" entry of Updates: two non-adjacent sections, with entries skipped
         * in between. That is the state in which wrong dividers show.
         */
        const val QUERY = "install"
    }
}

package com.multistore.feature.myapps

import androidx.compose.runtime.Composable
import com.multistore.core.model.InstalledApp
import com.multistore.core.model.InstallerKind
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.model.ThemeMode
import com.multistore.core.testing.ScreenshotTest
import kotlin.time.Instant
import org.junit.Test
import com.multistore.core.model.MyAppsSort
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme

/**
 * Screenshots of [MyAppsScreen] in both themes.
 *
 * Both themes, always: `ScreenshotCoverageTest` in `:guardrails` fails if a screen has only one.
 *
 * The golden photographs **three rows that differ from each other** rather than a uniform list: one with
 * a known store, one without — that is the row that cannot be tapped, because with no origin there is no
 * detail page to open — and one with no icon in the local catalogue, where the placeholder shows. Those
 * are the three cases where a visual regression actually matters; a list of identical rows would prove
 * one of them.
 *
 * The second golden exists for a different reason: the update states are **five different sentences**
 * and none of them appears on the screen at rest. Without a capture of their own, a regression making
 * them all alike — or all invisible — would go unnoticed. It now also carries the "update everything"
 * panel, which is the same component the Home draws and appears here only when something is
 * updatable — so the first golden, deliberately, does not have it.
 *
 * The third pair photographs a **search that matched nothing**, which is the state easiest to get
 * wrong: the apps are still installed, so falling back to the empty state would take the field away
 * at exactly the moment it is needed to clear the query, and would say something false while doing
 * it.
 */
class MyAppsScreenScreenshotTest : ScreenshotTest() {

    @Test
    fun lightTheme() = capture(SCREEN_NAME, ThemeMode.LIGHT) { Content() }

    @Test
    fun darkTheme() = capture(SCREEN_NAME, ThemeMode.DARK) { Content() }

    @Test
    fun updatesLight() = capture(UPDATES_SCREEN_NAME, ThemeMode.LIGHT) { UpdatesContent() }

    @Test
    fun updatesDark() = capture(UPDATES_SCREEN_NAME, ThemeMode.DARK) { UpdatesContent() }

    @Test
    fun sortDialogLight() = capture(SORT_DIALOG_SCREEN_NAME, ThemeMode.LIGHT) { SortChoices() }

    @Test
    fun sortDialogDark() = capture(SORT_DIALOG_SCREEN_NAME, ThemeMode.DARK) { SortChoices() }

    /**
     * The four criteria, and the one currently chosen.
     *
     * A golden of its own because it is the only surface in this feature with two Compose roots —
     * hence the only one on which the accessibility check looks at something different from the
     * rest — and because the rows are hand-built `selectable`s: they do not go through
     * `minimumInteractiveComponentSize`, so their height is a thing that can regress silently.
     *
     * Not the default criterion, deliberately: with `NAME` selected the golden would be right for
     * the one case where nobody has chosen anything, and would not show that the mark follows the
     * setting.
     */
    @Composable
    private fun SortChoices() {
        Surface(color = MaterialTheme.colorScheme.background) {
            SortDialog(
                selected = MyAppsSort.RECENTLY_INSTALLED,
                onSelect = {},
                onDismiss = {},
            )
        }
    }

    @Test
    fun noMatchesLight() = capture(NO_MATCHES_SCREEN_NAME, ThemeMode.LIGHT) { NoMatches() }

    @Test
    fun noMatchesDark() = capture(NO_MATCHES_SCREEN_NAME, ThemeMode.DARK) { NoMatches() }

    /** Apps installed, a query typed, nothing matching: **not** the empty state. */
    @Composable
    private fun NoMatches() {
        MyAppsScreen(
            uiState = MyAppsUiState.Ready(
                apps = emptyList(),
                uninstall = UninstallUiState.Idle,
                query = "thunderbird",
            ),
            query = "thunderbird",
            onAppClick = { _, _ -> },
            onRequestUninstall = {},
            onConfirmUninstall = {},
            onDismissUninstall = {},
            onDismissFailure = {},
        )
    }

    @Composable
    private fun Content() {
        MyAppsScreen(
            uiState = MyAppsUiState.Ready(
                apps = listOf(
                    item("org.fdroid.fdroid", "F-Droid", "1.23.2", storeName = "F-Droid"),
                    item(
                        "de.danoeh.antennapod",
                        "AntennaPod",
                        "3.8.0",
                        storeName = "F-Droid",
                        iconUrl = null,
                    ),
                    item("org.example.orphan", "No origin", "0.9", storeName = null),
                ),
                uninstall = UninstallUiState.Idle,
            ),
            onAppClick = { _, _ -> },
            onRequestUninstall = {},
            onConfirmUninstall = {},
            onDismissUninstall = {},
            onDismissFailure = {},
        )
    }

    /** The five sentences a row can say about updates, all together. */
    @Composable
    private fun UpdatesContent() {
        MyAppsScreen(
            uiState = MyAppsUiState.Ready(
                apps = listOf(
                    item("org.fdroid.fdroid", "F-Droid", "1.23.2", storeName = "F-Droid")
                        .copy(update = UpdateState.Available("1.24.0")),
                    item("de.danoeh.antennapod", "AntennaPod", "3.8.0", storeName = "F-Droid")
                        .copy(update = UpdateState.Paused(available = true)),
                    item("org.mozilla.firefox", "Firefox", "154.0", storeName = "APKMirror")
                        .copy(update = UpdateState.Pinned(versionCode = 2_154_000, heldBack = "155.0")),
                    item("com.example.reader", "Reader", "9.1.4", storeName = "Uptodown")
                        .copy(update = UpdateState.Undeterminable),
                    item("org.example.orphan", "No channel", "0.9", storeName = null)
                        .copy(update = UpdateState.NoChannel),
                ),
                uninstall = UninstallUiState.Idle,
                // One of the five rows has an update, so the shared "update everything" panel is
                // drawn — which is the whole point of putting it in this golden rather than the
                // other: at rest it is absent, and a component nothing photographs is a component
                // nothing compares.
                updatable = 1,
            ),
            onAppClick = { _, _ -> },
            onRequestUninstall = {},
            onConfirmUninstall = {},
            onDismissUninstall = {},
            onDismissFailure = {},
        )
    }

    private fun item(
        packageName: String,
        label: String,
        versionName: String,
        storeName: String?,
        iconUrl: String? = "https://example.test/$packageName.png",
    ) = InstalledAppItem(
        app = InstalledApp(
            packageName = packageName,
            label = label,
            versionName = versionName,
            versionCode = 1,
            signerSha256 = null,
            installedAt = INSTALLED_AT,
            installerKind = InstallerKind.SESSION,
            sourceStoreId = StoreId.FDROID.takeIf { storeName != null },
            sourceRef = StoreAppRef(packageName).takeIf { storeName != null },
            iconUrl = iconUrl,
        ),
        storeName = storeName,
    )

    private companion object {
        const val SCREEN_NAME = "MyAppsScreen"
        const val UPDATES_SCREEN_NAME = "MyAppsScreen_updates"
        const val NO_MATCHES_SCREEN_NAME = "MyAppsScreen_no_matches"
        const val SORT_DIALOG_SCREEN_NAME = "MyAppsSortDialog"
        val INSTALLED_AT: Instant = Instant.fromEpochMilliseconds(1_787_316_712_615L)
    }
}

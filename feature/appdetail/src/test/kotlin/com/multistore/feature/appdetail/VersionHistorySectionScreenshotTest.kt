package com.multistore.feature.appdetail

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import com.multistore.core.common.version.VersionSelection
import com.multistore.core.data.repository.AppDetail
import com.multistore.core.data.repository.StoreTaxonomy
import com.multistore.core.data.repository.VersionOffer
import com.multistore.core.model.AppVersion
import com.multistore.core.model.DeviceProfile
import com.multistore.core.model.InstalledPackage
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.model.StoreListingDetail
import com.multistore.core.model.StoreListingSummary
import com.multistore.core.model.ThemeMode
import com.multistore.core.model.VersionRef
import com.multistore.core.testing.ScreenshotTest
import kotlin.time.Instant
import org.junit.Test

/**
 * The version history, photographed on its own.
 *
 * The `AppDetailScreen` golden is not enough because that screen is taller than the device and the
 * section is at the bottom. Tested: with the history open inside the whole-screen golden, **two** rows
 * out of five are visible — and the three left below the fold are exactly the ones worth looking at,
 * that is the verdicts other than "installable". Same finding as on the Installation section, where the
 * new row did not change the existing golden by a pixel and `verifyRoborazziDebug` stayed green on a
 * screen that had changed.
 *
 * It shows the two shapes the section can be in, one above the other:
 *
 *  1. **open on an installed app, and in error**: the five rows carry four different verdicts — the
 *     newest with its button, a beta with its channel name, the installed one, an older one, an
 *     incompatible one — and above them the row saying the rest did not arrive. The two things are
 *     together on purpose: a failure does **not** empty the section, and the golden is where that rule
 *     is seen;
 *  2. **collapsed**, which is how the section always presents itself the first time. It is a single row,
 *     but it is the row the user meets: if the icon pointed the wrong way no state test would say so.
 */
class VersionHistorySectionScreenshotTest : ScreenshotTest() {

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
                VersionHistorySection(
                    state = state(VersionHistoryUiState(expanded = true, failed = true)),
                    onToggle = {},
                    onRetry = {},
                    onInstallVersion = {},
                )
                VersionHistorySection(
                    state = state(VersionHistoryUiState(expanded = false)),
                    onToggle = {},
                    onRetry = {},
                    onInstallVersion = {},
                )
            }
        }
    }

    private fun state(history: VersionHistoryUiState): AppDetailUiState.Ready {
        val versions = listOf(
            version("1.24.0", 1_024_000),
            version("1.24.0-rc1", 1_023_900, channels = setOf("Beta")),
            version("1.23.2", INSTALLED),
            version("1.23.1", 1_023_051),
            version("1.23.0", 1_023_050, minSdk = 99),
        )
        val device = DeviceProfile(sdkInt = 34, supportedAbis = listOf("arm64-v8a"))
        return AppDetailUiState.Ready(
            detail = AppDetail(
                listing = StoreListingDetail(
                    summary = StoreListingSummary(
                        storeId = StoreId.FDROID,
                        ref = StoreAppRef("org.fdroid.fdroid"),
                        title = "F-Droid",
                        packageName = "org.fdroid.fdroid",
                    ),
                    versions = versions,
                ),
                installed = InstalledPackage(
                    packageName = "org.fdroid.fdroid",
                    versionName = "1.23.2",
                    versionCode = INSTALLED,
                    signerSha256 = null,
                ),
                selection = VersionSelection.Outcome.UpToDate(versions[2]),
                stale = false,
                // The same verdicts the repository computes: the golden photographs the real screen, not
                // a simplified version of the rule.
                versions = versions.map { candidate ->
                    VersionOffer(
                        version = candidate,
                        installability =
                            VersionSelection.installability(candidate, device, INSTALLED),
                    )
                },
            ),
            taxonomy = StoreTaxonomy(),
            storeName = "F-Droid",
            install = InstallUiState.Idle,
            versionHistorySupported = true,
            versionHistory = history,
        )
    }

    private fun version(
        name: String,
        code: Long,
        channels: Set<String> = emptySet(),
        minSdk: Int? = 23,
    ) = AppVersion(
        versionName = name,
        versionCode = code,
        ref = VersionRef("v$code"),
        sizeBytes = 9_400_000,
        minSdk = minSdk,
        releaseChannels = channels,
        // A fixed instant rather than `Clock.System.now()`: a date of today would change tomorrow, and
        // the comparison would report a regression on a screen that has not changed.
        publishedAt = Instant.fromEpochMilliseconds(1_756_166_400_000L),
    )

    private companion object {
        const val SCREEN_NAME = "VersionHistorySection"
        const val INSTALLED = 1_023_052L
    }
}

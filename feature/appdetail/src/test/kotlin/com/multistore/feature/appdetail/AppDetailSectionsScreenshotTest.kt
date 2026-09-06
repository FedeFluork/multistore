package com.multistore.feature.appdetail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.multistore.core.common.version.VersionSelection
import com.multistore.core.data.repository.AppDetail
import com.multistore.core.data.repository.StoreTaxonomy
import com.multistore.core.model.AppVersion
import com.multistore.core.model.LocalizedText
import com.multistore.core.model.Screenshot
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.model.StoreListingDetail
import com.multistore.core.model.StoreListingSummary
import com.multistore.core.model.ThemeMode
import com.multistore.core.model.VersionRef
import com.multistore.core.testing.ScreenshotTest
import org.junit.Test

/**
 * The three blocks that show what the app already knew and never said: images, release notes, links.
 *
 * ### Why not inside the `AppDetailScreen` golden
 *
 * The same reason `VersionHistorySectionScreenshotTest` exists: that screen is taller than the
 * device and these three sit **below the divider**, i.e. below the fold. Measured by putting them
 * there first — the whole-screen golden did not change by a pixel, which is the failure mode this
 * project has already met once: `verifyRoborazziDebug` green on a screen that had changed.
 *
 * ### The column scrolls, and that is not decoration
 *
 * A `Column` that does not scroll measures its children with the height that is **left**, and when
 * it runs out the last child is squashed — which in this repository was first noticed as an
 * accessibility failure reporting a 40dp switch. The real screen scrolls; a bench that did not would
 * photograph a layout the user never sees, and would fail the touch-target check for a reason that
 * belongs to the bench.
 *
 * ### The links are drawn because the capability is a parameter
 *
 * Robolectric installs no browser, so the real `ExternalLinks.canOpen` answers `false` for every
 * address and the block would render empty here. That is exactly why "can this device open an
 * address" is a parameter of the screen rather than a call inside the section — see the doc on
 * `canOpenLink`.
 */
class AppDetailSectionsScreenshotTest : ScreenshotTest() {

    @Test
    fun lightTheme() = capture(SCREEN_NAME, ThemeMode.LIGHT) { Content() }

    @Test
    fun darkTheme() = capture(SCREEN_NAME, ThemeMode.DARK) { Content() }

    /**
     * The `Surface` is not decoration: in the real screen these sit inside a `Scaffold`, so they
     * paint no background of their own. Captured bare, the dark golden would come out with the dark
     * theme's light text on a white background — the trap already met on two other sections.
     */
    @Composable
    private fun Content() {
        val state = state()
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Screenshots(state = state, preferredLanguageTags = listOf("en"))
                WhatsNew(state = state, preferredLanguageTags = listOf("en"))
                Links(state = state, canOpen = { true }, onOpen = {})
            }
        }
    }

    private fun state(): AppDetailUiState.Ready {
        val version = AppVersion(
            versionName = "1.23.2",
            versionCode = 1_023_052,
            ref = VersionRef("golden"),
            sizeBytes = 9_400_000,
            minSdk = 23,
            // The version's own notes, which is the branch that wins: `whatsNew` below is the
            // listing's, and preferring it would describe whatever the store calls current next to a
            // button installing something else.
            changelog = LocalizedText(
                mapOf(
                    "en" to "Repository updates no longer stall on a slow connection, and the " +
                        "index is verified before it replaces the one on disk.",
                ),
            ),
        )
        return AppDetailUiState.Ready(
            detail = AppDetail(
                listing = StoreListingDetail(
                    summary = StoreListingSummary(
                        storeId = StoreId.FDROID,
                        ref = StoreAppRef("org.fdroid.fdroid"),
                        title = "F-Droid",
                        packageName = "org.fdroid.fdroid",
                        developer = "F-Droid Limited",
                    ),
                    // Four cells in English and two in German. More than fit across the device —
                    // which is what shows the strip is a strip and not a row that happens to have
                    // room — and, more to the point, a source that localises: the German pair must
                    // **not** be in the golden, because the reader here reads English. F-Droid files
                    // 95 images for AntennaPod that way.
                    screenshots = List(4) {
                        Screenshot(url = "https://example.invalid/en-$it.png", locale = "en-US")
                    } + List(2) {
                        Screenshot(url = "https://example.invalid/de-$it.png", locale = "de")
                    },
                    whatsNew = LocalizedText(
                        mapOf("en" to "This text must not appear: the version's changelog wins."),
                    ),
                    versions = listOf(version),
                    sourceCodeUrl = "https://gitlab.com/fdroid/fdroidclient",
                    webSiteUrl = "https://f-droid.org",
                    issueTrackerUrl = "https://gitlab.com/fdroid/fdroidclient/-/issues",
                    changelogUrl = "https://gitlab.com/fdroid/fdroidclient/-/releases",
                    // The field this release had to add a column for: it was read by the adapter,
                    // written by nobody, and would have vanished on the second visit to a listing.
                    translationUrl = "https://hosted.weblate.org/projects/f-droid/",
                    // Two of them, because F-Droid publishes several for the same package and
                    // picking one would choose for the author which channel gets shown.
                    donateUrls = listOf(
                        "https://f-droid.org/donate",
                        "https://liberapay.com/F-Droid-Data",
                    ),
                ),
                installed = null,
                selection = VersionSelection.Outcome.Offer(version, isUpdate = false),
                stale = false,
            ),
            taxonomy = StoreTaxonomy(),
            storeName = "F-Droid",
            install = InstallUiState.Idle,
        )
    }

    private companion object {
        const val SCREEN_NAME = "AppDetailSections"
    }
}

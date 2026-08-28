package com.multistore.feature.home

import androidx.compose.runtime.Composable
import com.multistore.core.common.version.VersionSelection
import com.multistore.core.data.repository.HomeIndex
import com.multistore.core.data.repository.InstalledAppUpdate
import com.multistore.core.data.repository.SelfUpdateOffer
import com.multistore.core.data.repository.UpdateChannel
import com.multistore.core.model.AppVersion
import com.multistore.core.model.Category
import com.multistore.core.model.InstalledApp
import com.multistore.core.model.InstallerKind
import com.multistore.core.model.LocalizedText
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.model.StoreListingSummary
import com.multistore.core.model.VersionRef
import com.multistore.core.remoteconfig.SelfUpdateRelease
import com.multistore.core.model.ThemeMode
import com.multistore.core.testing.ScreenshotTest
import kotlin.time.Instant
import org.junit.Test

/**
 * Screenshots of [HomeScreen] in both themes.
 *
 * Both themes, always: `ScreenshotCoverageTest` in `:guardrails` fails if a screen has only one.
 *
 * The golden photographs the catalogue **already synced** and not first launch: it is the state in
 * which the screen actually has something to draw, therefore the one in which a colour or spacing
 * regression shows. The icons stay placeholders because there is no network in a test, and that is
 * intended: the placeholder sits *underneath* the image, so the cell still has its final size.
 *
 * The categories are there because the chip row is what makes the rest of the index reachable: it is
 * also the most delicate block in dark mode, where the contrast of a chip on `surface` is the first to
 * give way.
 */
class HomeScreenScreenshotTest : ScreenshotTest() {

    @Test
    fun lightTheme() = capture(SCREEN_NAME, ThemeMode.LIGHT) { Content() }

    @Test
    fun darkTheme() = capture(SCREEN_NAME, ThemeMode.DARK) { Content() }

    @Test
    fun remoteIndexLight() = capture(INDEX_SCREEN_NAME, ThemeMode.LIGHT) { RemoteIndexContent() }

    @Test
    fun remoteIndexDark() = capture(INDEX_SCREEN_NAME, ThemeMode.DARK) { RemoteIndexContent() }

    @Test
    fun updatesLight() = capture(UPDATES_SCREEN_NAME, ThemeMode.LIGHT) { UpdatesContent() }

    @Test
    fun updatesDark() = capture(UPDATES_SCREEN_NAME, ThemeMode.DARK) { UpdatesContent() }

    @Composable
    private fun Content() {
        HomeScreen(
            uiState = HomeUiState.Ready(
                storeId = StoreId.FDROID,
                index = IndexStatus.Synced(entryCount = 4_257, syncedAt = FIXED_INSTANT),
                recentlyUpdated = listOf(
                    summary("org.fdroid.fdroid", "F-Droid", "The app store with only free software."),
                    summary("org.torproject.torbrowser", "Tor Browser", "Browse the web anonymously."),
                    summary("com.nextcloud.client", "Nextcloud", "Keep your files in your own cloud."),
                ),
                categories = listOf(
                    Category("Internet", LocalizedText(mapOf("en" to "Internet"))),
                    Category("Multimedia", LocalizedText(mapOf("en" to "Multimedia"))),
                    Category("Security", LocalizedText(mapOf("en" to "Security"))),
                ),
            ),
            preferredLanguageTags = listOf("en"),
            onAppClick = { _, _ -> },
            onBrowseCatalogue = { _, _ -> },
            onSync = {},
            onSyncWithConsent = {},
            onDismissMeteredConsent = {},
            onDismissFailure = {},
        )
    }

    /**
     * The same Home, with the updates card above the catalogue.
     *
     * A golden of its own rather than added to the base one: the card **is not there** when there is
     * nothing to update, which is the normal state, and a single capture could not prove both things.
     * It is also where the only point on the screen using the accent colour on a container shows, that
     * is the first to give way in dark mode.
     */
    @Composable
    private fun UpdatesContent() {
        HomeScreen(
            uiState = HomeUiState.Ready(
                storeId = StoreId.FDROID,
                index = IndexStatus.Synced(entryCount = 4_257, syncedAt = FIXED_INSTANT),
                recentlyUpdated = listOf(
                    summary("org.fdroid.fdroid", "F-Droid", "The app store with only free software."),
                ),
                updates = listOf(
                    update("de.danoeh.antennapod", "AntennaPod", "3.8.0", "3.9.0"),
                    update("org.mozilla.firefox", "Firefox", "154.0", "155.0"),
                ),
            ),
            preferredLanguageTags = listOf("en"),
            onAppClick = { _, _ -> },
            onBrowseCatalogue = { _, _ -> },
            onSync = {},
            onSyncWithConsent = {},
            onDismissMeteredConsent = {},
            onDismissFailure = {},
        )
    }

    /**
     * The Home fed by the remote index, **at first launch**.
     *
     * A golden of its own because it photographs the only state in which the two new sections make
     * sense on their own: no local catalogue downloaded yet, and the screen not empty anyway. It is
     * also the case `HomeList`'s structure exists to make possible — the Home's body used not to exist
     * at all without a synced local index.
     *
     * The self-update card is here too, the only block on the screen on `surfaceContainerHigh`: in
     * dark mode it is the first contrast to give way.
     */
    @Composable
    private fun RemoteIndexContent() {
        HomeScreen(
            uiState = HomeUiState.Ready(
                storeId = StoreId.FDROID,
                index = IndexStatus.NeverSynced,
                recentlyUpdated = emptyList(),
                remoteIndex = HomeIndex(
                    popular = listOf(
                        remote(StoreId.UPTODOWN, "capcut", "CapCut"),
                        remote(StoreId.APKMODY, "apps/youtube-premium-app", "YouTube Premium"),
                        remote(StoreId.UPTODOWN, "telegram", "Telegram"),
                        remote(StoreId.APKMODY, "games/8-ball-pool", "8 Ball Pool"),
                    ),
                    recent = listOf(
                        remote(StoreId.APKCOMBO, "recovery-reboot/gt.recovery.reboot", "Recovery Reboot"),
                        remote(StoreId.APKMIRROR, "google-inc/gmail", "Gmail"),
                        remote(StoreId.PDALIFE, "winter-burrow-android-a51917", "Winter Burrow"),
                    ),
                ),
                selfUpdate = SelfUpdateOffer(
                    release = SelfUpdateRelease(
                        versionCode = 2,
                        versionName = "0.5.0",
                        minSdk = 26,
                        url = "https://example.invalid/multistore.apk",
                        notes = "Nine stores, a signed index, and MultiStore updating itself.",
                    ),
                    installedVersionCode = 1,
                    installedVersionName = "0.1.0",
                ),
            ),
            preferredLanguageTags = listOf("en"),
            onAppClick = { _, _ -> },
            onBrowseCatalogue = { _, _ -> },
            onSync = {},
            onSyncWithConsent = {},
            onDismissMeteredConsent = {},
            onDismissFailure = {},
        )
    }

    private fun remote(storeId: StoreId, ref: String, title: String) = StoreListingSummary(
        storeId = storeId,
        ref = StoreAppRef(ref),
        title = title,
    )

    private fun update(
        packageName: String,
        title: String,
        installed: String,
        available: String,
    ) = InstalledAppUpdate(
        app = InstalledApp(
            packageName = packageName,
            label = title,
            versionName = installed,
            versionCode = 1,
            signerSha256 = null,
            installedAt = FIXED_INSTANT,
            installerKind = InstallerKind.SESSION,
        ),
        channel = UpdateChannel(
            storeId = StoreId.FDROID,
            ref = StoreAppRef(packageName),
            listingId = 1L,
            title = title,
            iconUrl = null,
        ),
        selection = VersionSelection.Outcome.Offer(
            version = AppVersion(versionName = available, versionCode = 2, ref = VersionRef("v2")),
            isUpdate = true,
        ),
    )

    private fun summary(id: String, title: String, text: String) = StoreListingSummary(
        storeId = StoreId.FDROID,
        ref = StoreAppRef(id),
        title = title,
        packageName = id,
        summary = LocalizedText(mapOf("en" to text)),
    )

    private companion object {
        const val SCREEN_NAME = "HomeScreen"
        const val UPDATES_SCREEN_NAME = "HomeScreen_updates"
        const val INDEX_SCREEN_NAME = "HomeScreen_remote_index"

        /** A fixed instant: a golden cannot depend on the machine's clock. */
        val FIXED_INSTANT: Instant = Instant.fromEpochSeconds(1_756_000_000)
    }
}

package com.multistore.feature.appdetail

import androidx.compose.runtime.Composable
import com.multistore.core.common.version.VersionSelection
import com.multistore.core.data.repository.AppDetail
import com.multistore.core.data.repository.CrossStoreAvailability
import com.multistore.core.data.repository.StoreAvailability
import com.multistore.core.data.repository.StoreTaxonomy
import com.multistore.core.data.repository.VersionOffer
import com.multistore.core.installer.verify.ApkArchiveInfo
import com.multistore.core.installer.verify.PreInstallVerifier.VerificationOutcome
import com.multistore.core.model.AggregatedListing
import com.multistore.core.model.AntiFeature
import com.multistore.core.model.AppVersion
import com.multistore.core.model.DeviceProfile
import com.multistore.core.model.InstalledPackage
import com.multistore.core.model.LocalizedText
import com.multistore.core.model.MatchMethod
import com.multistore.core.model.Sha256
import com.multistore.core.model.ModifiedBuild
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.model.StoreListingDetail
import com.multistore.core.model.StoreListingSummary
import com.multistore.core.model.ThemeMode
import com.multistore.core.model.UsesPermission
import com.multistore.core.model.VersionRef
import com.multistore.core.testing.ScreenshotTest
import kotlin.time.Instant
import org.junit.Test

/**
 * Screenshots of [AppDetailScreen] in both themes.
 *
 * `canInstallPackages = false` on purpose: the golden photographs the screen **with** the notice
 * asking for the install permission, because that is what the user sees the first time and therefore
 * the configuration with the most elements to compare. The anti-features are there for the same
 * reason — they are the only block on the page whose text arrives from the store already localised
 * rather than from `strings.xml`, and it is worth seeing it drawn.
 */
class AppDetailScreenScreenshotTest : ScreenshotTest() {

    @Test
    fun lightTheme() = capture(SCREEN_NAME, ThemeMode.LIGHT) { Content() }

    @Test
    fun darkTheme() = capture(SCREEN_NAME, ThemeMode.DARK) { Content() }

    @Test
    fun verificationLight() = capture(VERIFIED_SCREEN_NAME, ThemeMode.LIGHT) { Verified() }

    @Test
    fun verificationDark() = capture(VERIFIED_SCREEN_NAME, ThemeMode.DARK) { Verified() }

    @Test
    fun crossStoreLight() = capture(CROSS_STORE_SCREEN_NAME, ThemeMode.LIGHT) { CrossStore() }

    @Test
    fun crossStoreDark() = capture(CROSS_STORE_SCREEN_NAME, ThemeMode.DARK) { CrossStore() }

    @Test
    fun installedLight() = capture(INSTALLED_SCREEN_NAME, ThemeMode.LIGHT) { Installed() }

    @Test
    fun installedDark() = capture(INSTALLED_SCREEN_NAME, ThemeMode.DARK) { Installed() }

    @Test
    fun modifiedLight() = capture(MODIFIED_SCREEN_NAME, ThemeMode.LIGHT) { Modified() }

    @Test
    fun modifiedDark() = capture(MODIFIED_SCREEN_NAME, ThemeMode.DARK) { Modified() }

    @Test
    fun permissionsLight() = capture(PERMISSIONS_SCREEN_NAME, ThemeMode.LIGHT) { Permissions() }

    @Test
    fun permissionsDark() = capture(PERMISSIONS_SCREEN_NAME, ThemeMode.DARK) { Permissions() }

    @Test
    fun channelLight() = capture(CHANNEL_SCREEN_NAME, ThemeMode.LIGHT) { Channel() }

    @Test
    fun channelDark() = capture(CHANNEL_SCREEN_NAME, ThemeMode.DARK) { Channel() }

    /**
     * The app is here, it is current, and the page has an **Open** button.
     *
     * It is the branch that had nothing in it before: up to date meant a sentence and an "Uninstall",
     * i.e. a page whose only offer was to undo the thing the user had come to do. The golden is worth
     * a pair of its own because the button is drawn only where three conditions hold at once —
     * installed, up to date, and a package with a launcher activity — and none of the other goldens
     * has all three.
     */
    @Composable
    private fun Installed() {
        Content(
            installedVersionCode = 1_023_052,
            upToDate = true,
            // `null` here would be the case of a package with no launcher activity — an input method,
            // a wallpaper — which is common enough on F-Droid to be the reason the parameter exists.
            onOpenApp = {},
        )
    }

    /**
     * The offer to make this listing the app's update channel, **with** the signature warning.
     *
     * The warning half is the one photographed, because it is the one that changes the card: a
     * differing signer turns it into a `WARNING` container with a second paragraph, and that is the
     * variant where a colour choice can be wrong in a way only a picture shows. The quiet half is a
     * neutral card with one paragraph, which every other informing card on this page already covers.
     *
     * The store named is APKMirror rather than the one this fixture's listing comes from, because
     * the sentence's whole job is to say what is **changing** — a card naming the same store twice
     * would read as though nothing were.
     */
    @Composable
    private fun Channel() {
        Content(
            installedVersionCode = 1_023_051,
            channelSwitch = ChannelSwitch(
                packageName = "org.fdroid.fdroid",
                currentStoreName = "APKMirror",
                signerConflict = true,
            ),
        )
    }

    /**
     * The permission list, with a sensitive one in it.
     *
     * A golden of its own because the four that exist all photograph the **third** state of that
     * section — no list, which is what the eight scraped stores show until a version has been
     * downloaded once — and the third state is a sentence, not rows. What only a picture can hold
     * here is that the "Sensitive" chip is legible on its error container in both palettes, that a
     * permission and its system-written description do not collide, and that the section does not
     * push the author's links off the page.
     *
     * The names are real Android permissions on purpose: their labels and descriptions come from the
     * platform, not from `strings.xml`, so a made-up name would photograph the fallback path instead
     * of the one every real listing takes.
     *
     * **What the picture is not evidence of:** Robolectric's `PackageManager` knows only part of the
     * platform's permission table, so some of these come back unlabelled and unclassified here and
     * are drawn by their identifier — `CAMERA` is *dangerous* on a device and is not marked in this
     * golden. That makes the picture more useful rather than less, because it holds both paths at
     * once; the classification itself is asserted where it can be, in `PermissionCatalogTest`.
     */
    @Composable
    private fun Permissions() {
        Content(
            permissions = listOf(
                UsesPermission("android.permission.CAMERA"),
                UsesPermission("android.permission.READ_CONTACTS"),
                UsesPermission("android.permission.INTERNET"),
                UsesPermission("android.permission.ACCESS_NETWORK_STATE"),
                // Declared only up to API 28, and the golden is captured on 34: it must **not**
                // appear. A permission shown that this device would never be asked for is the screen
                // accusing an app of wanting something it stopped wanting.
                UsesPermission("android.permission.WRITE_EXTERNAL_STORAGE", maxSdk = 28),
            ),
        )
    }

    /**
     * A listing from a source that republishes reworked apps.
     *
     * A golden of its own rather than a badge added to the four that exist, because the four are all
     * F-Droid — a store that publishes no reworks, and where the correct picture is one with **no**
     * badge at all. Both pictures are needed: the header must be able to say this and to say
     * nothing, and only having them side by side makes a badge that stopped drawing distinguishable
     * from a store that stopped publishing reworks.
     *
     * What only this picture can catch is the placement: the chip sits between the facts line and
     * "newer elsewhere", which is where the reader is already weighing this store against the
     * others, and it must not push the summary or the install button off the fold.
     */
    @Composable
    private fun Modified() {
        Content(modifiedBuild = ModifiedBuild.DECLARED)
    }

    /**
     * "Available on 2 stores" **and** a possible match, in the same golden.
     *
     * The two sections have to be photographed together because the point is that they are
     * distinguishable: one lists proven matches and opens with a tap, the other asks. A golden with
     * only the first would say nothing about the rule that separates them.
     */
    @Composable
    private fun CrossStore() {
        Content(
            crossStore = CrossStoreAvailability(
                availableOn = listOf(
                    StoreAvailability(
                        listing = AggregatedListing(
                            summary = StoreListingSummary(
                                storeId = StoreId.APKMIRROR,
                                ref = StoreAppRef("f-droid-limited/f-droid"),
                                title = "F-Droid",
                                packageName = "org.fdroid.fdroid",
                                // Higher than the one offered here: it is what makes the "apkmirror
                                // publishes 1.24.0" row appear in the header, which without two
                                // comparable `versionCode`s never appears.
                                latestVersionName = "1.24.0",
                                latestVersionCode = 1_024_000,
                            ),
                        ),
                        listingId = 2,
                    ),
                ),
                possibleMatches = listOf(
                    StoreAvailability(
                        listing = AggregatedListing(
                            summary = StoreListingSummary(
                                storeId = StoreId.APKMODY,
                                ref = StoreAppRef("apps/f-droid-basic"),
                                title = "F-Droid Basic",
                            ),
                            confidence = 0.5f,
                            method = MatchMethod.TITLE_DEV,
                        ),
                        listingId = 3,
                    ),
                ),
                unexploredStores = 2,
            ),
        )
    }

    /**
     * The verification card, with **one check out of three not performed**.
     *
     * That is the configuration worth photographing, not the one with three green ticks: the reason the
     * card exists is to tell "verified" from "not contradicted", and in a golden with three ticks that
     * distinction would not show. The chosen state is the real one for 4 stores out of 9, which do not
     * publish the packageName.
     */
    @Composable
    private fun Verified() {
        Content(
            verification = VerificationOutcome.Ok(
                info = ApkArchiveInfo(
                    packageName = "org.fdroid.fdroid",
                    versionCode = 1_023_052,
                    minSdk = 23,
                    signerSha256 = listOf(requireNotNull(Sha256.parseOrNull("43".repeat(32)))),
                    signatureSchemes = setOf(2, 3),
                    fileSha256 = requireNotNull(Sha256.parseOrNull("90".repeat(32))),
                    sizeBytes = 9_400_000,
                ),
                packageNameWasVerified = false,
                signerWasVerified = true,
                hashWasVerified = true,
            ),
        )
    }

    @Composable
    private fun Content(
        verification: VerificationOutcome.Ok? = null,
        crossStore: CrossStoreAvailability = CrossStoreAvailability(),
        versions: List<AppVersion>? = null,
        installedVersionCode: Long? = null,
        upToDate: Boolean = false,
        onOpenApp: (() -> Unit)? = null,
        versionHistorySupported: Boolean = false,
        versionHistory: VersionHistoryUiState = VersionHistoryUiState(),
        modifiedBuild: ModifiedBuild = ModifiedBuild.NONE,
        /**
         * `null` by default, which is the **third** state and the one the other goldens show: eight
         * stores of nine publish nothing, so "not known yet" is what a listing says until a version
         * has been downloaded once. It is deliberately not `emptyList()` — that would be the claim
         * "asks for nothing" on every existing golden.
         */
        permissions: List<UsesPermission>? = null,
        /** `null` on every other golden: the offer only exists where a channel is already set. */
        channelSwitch: ChannelSwitch? = null,
    ) {
        val version = AppVersion(
            versionName = "1.23.2",
            versionCode = 1_023_052,
            ref = VersionRef("golden"),
            sizeBytes = 9_400_000,
            minSdk = 23,
            antiFeatures = listOf(AntiFeature(id = "NonFreeNet")),
            permissions = permissions,
        )
        val published = versions ?: listOf(version)
        val device = DeviceProfile(sdkInt = 34, supportedAbis = listOf("arm64-v8a"))
        AppDetailScreen(
            uiState = AppDetailUiState.Ready(
                detail = AppDetail(
                    listing = StoreListingDetail(
                        summary = StoreListingSummary(
                            storeId = StoreId.FDROID,
                            ref = StoreAppRef("org.fdroid.fdroid"),
                            title = "F-Droid",
                            packageName = "org.fdroid.fdroid",
                            summary = LocalizedText(
                                mapOf("en" to "The app store with only free software."),
                            ),
                            developer = "F-Droid Limited",
                            // The rating sits in the header alongside version and size, and is missing
                            // on six stores out of nine: without it here the golden would photograph
                            // the incomplete row and nobody would ever see the complete one.
                            rating = 4.6f,
                            // The same argument one field further: the count is published by five
                            // stores and the downloads label by three, and both were being collected
                            // and thrown away. A six-figure count is deliberate — it is what shows
                            // that the number goes through `NumberFormat` and not `toString`.
                            ratingCount = 128_461,
                            downloadsLabel = "10M+",
                        ),
                        description = LocalizedText(
                            mapOf(
                                "en" to "F-Droid is an installable catalogue of free and open " +
                                    "source software for Android.",
                            ),
                        ),
                        versions = published,
                        license = "GPL-3.0-or-later",
                    ),
                    installed = installedVersionCode?.let {
                        InstalledPackage(
                            packageName = "org.fdroid.fdroid",
                            versionName = "1.23.2",
                            versionCode = it,
                            signerSha256 = null,
                        )
                    },
                    selection = if (upToDate) {
                        VersionSelection.Outcome.UpToDate(version)
                    } else {
                        VersionSelection.Outcome.Offer(version, isUpdate = false)
                    },
                    stale = false,
                    // The same verdicts the repository computes: the golden photographs the screen,
                    // not a simplified version of the rule.
                    versions = published.map { candidate ->
                        VersionOffer(
                            version = candidate,
                            installability = VersionSelection.installability(
                                candidate,
                                device,
                                installedVersionCode,
                            ),
                        )
                    },
                ),
                taxonomy = StoreTaxonomy(
                    antiFeatures = listOf(
                        AntiFeature(
                            id = "NonFreeNet",
                            name = LocalizedText(mapOf("en" to "Non-free network services")),
                            description = LocalizedText(
                                mapOf("en" to "Promotes or depends on a non-free network service."),
                            ),
                        ),
                    ),
                ),
                storeName = "F-Droid",
                modifiedBuild = modifiedBuild,
                channelSwitch = channelSwitch,
                install = InstallUiState.Idle,
                verification = verification,
                crossStore = crossStore,
                versionHistorySupported = versionHistorySupported,
                versionHistory = versionHistory,
            ),
            preferredLanguageTags = listOf("en"),
            canInstallPackages = false,
            // Not `null`: the golden must photograph the top bar's two actions as well. They are the
            // only interactive elements up there besides Back, and therefore the only ones the
            // accessibility check hooked to every capture can say anything about.
            onOpenInBrowser = {},
            onShareListing = {},
            onOpenApp = onOpenApp,
            onBack = {},
            onInstall = {},
            onUninstall = {},
            onCancel = {},
            onDismissOutcome = {},
            onGrantInstallPermission = {},
            onInstallFromDownload = {},
            onUserAssistedDownload = {},
            storeDisplayName = { it.wireName },
            onOpenListing = { _, _ -> },
            onCompareStores = {},
            onSwitchUpdateChannel = {},
            onSearchDeveloper = {},
            onLookUpOtherStores = {},
            onConfirmMatch = {},
            onRejectMatch = {},
            onToggleVersionHistory = {},
            onShowVersionHistory = {},
            onRetryVersionHistory = {},
            onInstallVersion = {},
        )
    }

    private companion object {
        const val SCREEN_NAME = "AppDetailScreen"
        const val VERIFIED_SCREEN_NAME = "AppDetailScreen_verification"
        const val CROSS_STORE_SCREEN_NAME = "AppDetailScreen_store"
        const val INSTALLED_SCREEN_NAME = "AppDetailScreen_installed"
        const val MODIFIED_SCREEN_NAME = "AppDetailScreen_modified"
        const val PERMISSIONS_SCREEN_NAME = "AppDetailScreen_permissions"
        const val CHANNEL_SCREEN_NAME = "AppDetailScreen_channel"
    }
}

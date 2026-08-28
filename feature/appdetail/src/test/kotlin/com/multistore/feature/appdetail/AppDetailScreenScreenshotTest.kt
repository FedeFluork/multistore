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
        versionHistorySupported: Boolean = false,
        versionHistory: VersionHistoryUiState = VersionHistoryUiState(),
    ) {
        val version = AppVersion(
            versionName = "1.23.2",
            versionCode = 1_023_052,
            ref = VersionRef("golden"),
            sizeBytes = 9_400_000,
            minSdk = 23,
            antiFeatures = listOf(AntiFeature(id = "NonFreeNet")),
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
                    selection = VersionSelection.Outcome.Offer(version, isUpdate = false),
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
                install = InstallUiState.Idle,
                verification = verification,
                crossStore = crossStore,
                versionHistorySupported = versionHistorySupported,
                versionHistory = versionHistory,
            ),
            preferredLanguageTags = listOf("en"),
            canInstallPackages = false,
            // Not `null`: the golden must photograph the "open in browser" action too, which is the
            // only interactive element of the top bar besides the back button — and therefore the only
            // one the accessibility check can say anything about.
            onOpenInBrowser = {},
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
    }
}

package com.multistore.store.fdroid.index

import com.google.common.truth.Truth.assertThat
import com.multistore.core.common.version.VersionSelection
import com.multistore.core.model.AppVersion
import com.multistore.core.model.DeviceProfile
import com.multistore.core.model.StoreListingDetail
import com.multistore.store.fdroid.FdroidConfig
import com.multistore.store.fdroid.Fixtures
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Which version to offer, proven on the real packages that make the question hard.
 *
 * The reference is the official API's `suggestedVersionCode`, measured on 20 packages on 23/08/2026:
 * the rule agrees on 17. The three divergences are documented below as tests, because in all three
 * **it is the API that is unsuited to us**, not the rule that is wrong.
 */
@DisplayName("Choosing the version to offer")
class VersionChoiceTest {

    private val projection = PackageProjection(repoUrl = FdroidConfig().repoUrl)

    /** Pixel 7, the device the app is tested on. */
    private val pixel7 = DeviceProfile(sdkInt = 36, supportedAbis = listOf("arm64-v8a", "armeabi-v7a"))

    private fun detail(packageName: String): StoreListingDetail =
        requireNotNull(projection.project(packageName, Fixtures.slicePackage(packageName)))

    private fun choose(
        packageName: String,
        device: DeviceProfile = pixel7,
        installedSigner: com.multistore.core.model.Sha256? = null,
        installedVersionCode: Long? = null,
        allowBeta: Boolean = false,
    ): VersionSelection.Outcome {
        val d = detail(packageName)
        return VersionSelection.select(
            VersionSelection.Request(
                versions = d.versions,
                device = device,
                preferredSigner = d.preferredSignerSha256,
                installedSigner = installedSigner,
                installedVersionCode = installedVersionCode,
                allowNonDefaultChannels = allowBeta,
            ),
        )
    }

    private fun offered(outcome: VersionSelection.Outcome): AppVersion = when (outcome) {
        is VersionSelection.Outcome.Offer -> outcome.version
        is VersionSelection.Outcome.UpToDate -> outcome.version
        else -> error("Expected an outcome carrying a version, got $outcome")
    }

    @Test
    @DisplayName("F-Droid: 1.23.2 is offered, not the 2.0-rc0 with the highest versionCode")
    fun betaIsNotOffered() {
        val chosen = offered(choose(Fixtures.PKG_FDROID))

        // The official API says 1023052 and the rule agrees. The raw maximum would be 2000040.
        assertThat(chosen.versionCode).isEqualTo(1_023_052L)
        assertThat(chosen.versionName).isEqualTo("1.23.2")
    }

    @Test
    @DisplayName("whoever explicitly asks for betas receives them")
    fun betaIsOfferedWhenAskedFor() {
        val chosen = offered(choose(Fixtures.PKG_FDROID, allowBeta = true))

        assertThat(chosen.versionCode).isEqualTo(2_000_040L)
    }

    @Test
    @DisplayName("an app that exists only in beta is not a dead end, and the channel is named")
    fun onlyBetaIsItsOwnOutcome() {
        // On the real index 28 versions are in `Beta`. A package publishing **only** there ended up
        // in `NothingInstallable`, i.e. with the same message as "this store has no package for this
        // app" — which is a dead end, whereas this is a choice somebody might want to make.
        val onlyBeta = detail(Fixtures.PKG_FDROID).versions.filterNot { it.isDefaultChannel }
        assertThat(onlyBeta).isNotEmpty()

        val outcome = VersionSelection.select(
            VersionSelection.Request(versions = onlyBeta, device = pixel7),
        )

        assertThat(outcome).isInstanceOf(VersionSelection.Outcome.OnlyOtherChannels::class.java)
        val only = outcome as VersionSelection.Outcome.OnlyOtherChannels
        // The channel's name comes from the store: it is what makes the sentence useful rather than
        // alarming.
        assertThat(only.channels).contains("Beta")
        assertThat(only.available).isNotEmpty()
    }

    @Test
    @DisplayName("with no installable artefact the outcome is a different one")
    fun noArtifactIsStillNothingInstallable() {
        val outcome = VersionSelection.select(
            VersionSelection.Request(versions = emptyList(), device = pixel7),
        )

        assertThat(outcome).isEqualTo(VersionSelection.Outcome.NothingInstallable)
    }

    @Test
    @DisplayName("package not installed: the recommended signer wins, not the highest versionCode")
    fun preferredSignerWinsForANewInstall() {
        val d = detail(Fixtures.PKG_KEYBOARD)
        val chosen = offered(choose(Fixtures.PKG_KEYBOARD))

        // The API's `suggestedVersionCode` here says 55, but that version is signed by the developer,
        // not by F-Droid. The rule picks the 50 signed by `preferredSigner`. It is not pedantry: it is
        // the signature that determines whether the next update will succeed.
        assertThat(chosen.versionCode).isEqualTo(50L)
        assertThat(chosen.signerSha256).isEqualTo(d.preferredSignerSha256)
    }

    @Test
    @DisplayName("package already installed: the installed signature rules, and the answer changes")
    fun installedSignerOverridesThePreferredOne() {
        val d = detail(Fixtures.PKG_KEYBOARD)
        val upstream = d.versions
            .mapNotNull { it.signerSha256 }
            .first { it != d.preferredSignerSha256 }

        val chosen = offered(choose(Fixtures.PKG_KEYBOARD, installedSigner = upstream, installedVersionCode = 50L))

        // Same package, same fixture, different answer: whoever installed the developer-signed build
        // has to stay on that signature chain, otherwise the update is refused by the operating
        // system. And here the 55 exists and is a real update.
        assertThat(chosen.versionCode).isEqualTo(55L)
        assertThat(chosen.signerSha256).isEqualTo(upstream)
    }

    @Test
    @DisplayName("if no version has the installed signature, the conflict is declared instead of staying silent")
    fun signerConflictIsExplicit() {
        val foreign = requireNotNull(com.multistore.core.model.Sha256.parseOrNull("ab".repeat(32)))

        val outcome = choose(Fixtures.PKG_FDROID, installedSigner = foreign, installedVersionCode = 1L)

        // It is not "nothing to be done": it is the case where "uninstall and reinstall, lose your
        // data" has to be offered. A generic error would hide the only possible action.
        assertThat(outcome).isInstanceOf(VersionSelection.Outcome.SignerConflict::class.java)
        assertThat((outcome as VersionSelection.Outcome.SignerConflict).available).isNotEmpty()
    }

    @Test
    @DisplayName("already up to date is not an update")
    fun upToDateIsDistinctFromAnUpdate() {
        val d = detail(Fixtures.PKG_FDROID)
        val installed = d.preferredSignerSha256

        val current = choose(Fixtures.PKG_FDROID, installedSigner = installed, installedVersionCode = 1_023_052L)
        val older = choose(Fixtures.PKG_FDROID, installedSigner = installed, installedVersionCode = 1_023_051L)

        assertThat(current).isInstanceOf(VersionSelection.Outcome.UpToDate::class.java)
        assertThat(older).isInstanceOf(VersionSelection.Outcome.Offer::class.java)
        assertThat((older as VersionSelection.Outcome.Offer).isUpdate).isTrue()
    }

    @Test
    @DisplayName("on an old phone we drop to the version that runs on it, we do not give up")
    fun anOlderDeviceGetsAnOlderVersion() {
        val d = detail(Fixtures.PKG_MIN_SDK_33)
        assertThat(d.versions.map { it.minSdk }).contains(33)

        val recent = offered(choose(Fixtures.PKG_MIN_SDK_33))
        val old = offered(choose(Fixtures.PKG_MIN_SDK_33, device = DeviceProfile(26, listOf("arm64-v8a"))))

        // The current version asks for minSdk 33; on an API 26 device the rule drops to an older
        // build instead of declaring the app unavailable. It is the difference between a store usable
        // on a six-year-old phone and one that says no to it.
        assertThat(recent.minSdk).isEqualTo(33)
        assertThat(old.minSdk).isAtMost(26)
        assertThat(old.versionCode).isLessThan(recent.versionCode)
    }

    @Test
    @DisplayName("when no version runs on it, the outcome is Incompatible and not an empty list")
    fun incompatibleIsItsOwnAnswer() {
        val tooOld = DeviceProfile(sdkInt = 21, supportedAbis = listOf("arm64-v8a"))

        val outcome = choose(Fixtures.PKG_MIN_SDK_33, device = tooOld)

        // The distinction matters to the UI: "does not run on this phone" and "this store does not
        // have it" lead to two different messages and two different actions.
        assertThat(outcome).isEqualTo(VersionSelection.Outcome.Incompatible)
    }

    @Test
    @DisplayName("per-ABI builds: the one the device can run is chosen")
    fun abiSpecificBuildsArePickedCorrectly() {
        val arm64 = offered(choose(Fixtures.PKG_NEWPIPE))
        assertThat(arm64.abis.isEmpty() || arm64.abis.contains("arm64-v8a")).isTrue()

        val x86 = offered(
            choose(Fixtures.PKG_NEWPIPE, device = DeviceProfile(sdkInt = 36, supportedAbis = listOf("x86_64"))),
        )
        // On an x86_64 device the choice must change: downloading the arm64 build means thirty
        // megabytes that will not install.
        assertThat(x86.abis.isEmpty() || x86.abis.contains("x86_64")).isTrue()
    }

    @Test
    @DisplayName("the .zip entries do not even reach the choice")
    fun zipOnlyPackageNeverReachesSelection() {
        // The filter is upstream, in the projection: the API's `suggestedVersionCode` for this package
        // says 2130, but that file is an OTA to be flashed. For us the package does not exist, and
        // that is the right answer.
        assertThat(projection.project(Fixtures.PKG_OTA, Fixtures.slicePackage(Fixtures.PKG_OTA))).isNull()
    }
}

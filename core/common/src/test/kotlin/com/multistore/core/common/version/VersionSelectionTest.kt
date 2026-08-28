package com.multistore.core.common.version

import com.google.common.truth.Truth.assertThat
import com.multistore.core.model.AppVersion
import com.multistore.core.model.ArtifactType
import com.multistore.core.model.DeviceProfile
import com.multistore.core.model.VersionRef
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The version-selection rule, exercised on the JVM.
 *
 * The **version pin** and the distinction between "up to date" and "cannot be known" are pure
 * decisions: here they are tested with four invented versions and no infrastructure.
 */
@DisplayName("Version selection")
class VersionSelectionTest {

    private val pixel7 = DeviceProfile(sdkInt = 36, supportedAbis = listOf("arm64-v8a", "armeabi-v7a"))

    private fun version(
        code: Long?,
        name: String = code?.toString() ?: "?",
        channels: Set<String> = emptySet(),
        minSdk: Int? = null,
    ) = AppVersion(
        versionName = name,
        versionCode = code,
        ref = VersionRef("v-$name"),
        releaseChannels = channels,
        minSdk = minSdk,
    )

    private fun select(
        versions: List<AppVersion>,
        installed: Long? = null,
        pinned: Long? = null,
        allowNonDefaultChannels: Boolean = false,
    ) = VersionSelection.select(
        VersionSelection.Request(
            versions = versions,
            device = pixel7,
            installedVersionCode = installed,
            pinnedVersionCode = pinned,
            allowNonDefaultChannels = allowNonDefaultChannels,
        ),
    )

    // --- The version pin ---------------------------------------------------------------------

    @Test
    @DisplayName("pinned at 100: 120 is not offered, and it is said to exist")
    fun pinHoldsBackTheNewerBuild() {
        val newer = version(120)
        val outcome = select(listOf(version(100), newer), installed = 100, pinned = 100)

        // Without this outcome the listing would say "up to date", which is false: 120 exists,
        // the user simply refused it. Naming it is what lets them change their mind.
        val pinned = outcome as VersionSelection.Outcome.Pinned
        assertThat(pinned.pinnedVersionCode).isEqualTo(100)
        assertThat(pinned.heldBack).isEqualTo(newer)
        assertThat(pinned.offer).isNull()
    }

    @Test
    @DisplayName("pinned at 100 with 90 installed: 100 is offered all the same")
    fun pinStillOffersUpToThePin() {
        val atThePin = version(100)
        val outcome = select(listOf(version(90), atThePin, version(120)), installed = 90, pinned = 100)

        // "No further" is not "nothing". Pinning at 100 with 90 installed means reaching 100.
        val pinned = outcome as VersionSelection.Outcome.Pinned
        assertThat(pinned.offer?.version).isEqualTo(atThePin)
        assertThat(pinned.offer?.isUpdate).isTrue()
        assertThat(pinned.heldBack.versionCode).isEqualTo(120)
    }

    @Test
    @DisplayName("a pin that withholds nothing stays invisible")
    fun pinIsSilentWhenItHoldsNothingBack() {
        val outcome = select(listOf(version(100)), installed = 100, pinned = 100)

        // A "pinned" notice on an app that has nothing newer anyway would describe a problem
        // that does not exist.
        assertThat(outcome).isInstanceOf(VersionSelection.Outcome.UpToDate::class.java)
    }

    @Test
    @DisplayName("the pin does not take the blame for what the release channel discards")
    fun pinDoesNotClaimWhatTheChannelFilterRejects() {
        // 120 is a beta: unpinned it would not have been offered anyway. If the pin were
        // applied by filtering at the input, the user would read that **their** pin is denying
        // them an update they would never have received.
        val outcome = select(
            listOf(version(100), version(120, channels = setOf("Beta"))),
            installed = 100,
            pinned = 100,
        )

        assertThat(outcome).isInstanceOf(VersionSelection.Outcome.UpToDate::class.java)
    }

    @Test
    @DisplayName("pinned below what is installed: no downgrade is proposed")
    fun pinBelowInstalledOffersNothing() {
        val outcome = select(listOf(version(90), version(120)), installed = 120, pinned = 100)

        // The pin withholds 120… which is already installed: unpinned, `decide` answers "up to
        // date", so the pin has nothing to say.
        assertThat(outcome).isInstanceOf(VersionSelection.Outcome.UpToDate::class.java)
    }

    @Test
    @DisplayName("with no pin, the newest is offered as before")
    fun withoutAPinNothingChanges() {
        val outcome = select(listOf(version(100), version(120)), installed = 100)

        val offer = outcome as VersionSelection.Outcome.Offer
        assertThat(offer.version.versionCode).isEqualTo(120)
        assertThat(offer.isUpdate).isTrue()
    }

    // --- "Up to date" against "cannot be known" ----------------------------------------------

    @Test
    @DisplayName("a store without a versionCode cannot say \"up to date\"")
    fun withoutAVersionCodeTheComparisonIsNotPossible() {
        // uptodown publishes the versionCode nowhere on its site. Without this distinction
        // every app taken from there would say "up to date" forever, with the same confident
        // face as one that really is.
        val outcome = select(listOf(version(code = null, name = "9.1.4")), installed = 12_345)

        val upToDate = outcome as VersionSelection.Outcome.UpToDate
        assertThat(upToDate.comparable).isFalse()
    }

    @Test
    @DisplayName("with a versionCode, \"up to date\" is a statement of fact")
    fun withAVersionCodeTheComparisonIsReal() {
        val outcome = select(listOf(version(100)), installed = 100)

        assertThat((outcome as VersionSelection.Outcome.UpToDate).comparable).isTrue()
    }

    @Test
    @DisplayName("app not installed: no comparison to make, and no uncertainty to declare")
    fun aFreshInstallIsAlwaysAnOffer() {
        val outcome = select(listOf(version(code = null, name = "9.1.4")))

        assertThat(outcome).isInstanceOf(VersionSelection.Outcome.Offer::class.java)
        assertThat((outcome as VersionSelection.Outcome.Offer).isUpdate).isFalse()
    }

    // --- Split containers ---------------------------------------------------------------------

    @Test
    @DisplayName("a bundle is offered: this line used to discard it silently")
    fun containersAreOfferedNow() {
        val bundle = version(10).copy(artifactType = ArtifactType.XAPK)

        val outcome = VersionSelection.select(
            VersionSelection.Request(versions = listOf(bundle), device = pixel7),
        )

        // Eight adapters out of nine populate the artifact type; the filter here used to read
        // `setOf(APK)`, so an app published **only** as a bundle came out as "no installable
        // artifact" — the one outcome of this function that sounds like a property of the store
        // rather than of us.
        assertThat((outcome as VersionSelection.Outcome.Offer).version).isEqualTo(bundle)
    }

    @Test
    @DisplayName("with the old filter the same bundle was not installable")
    fun theOldFilterRejectedTheSameBundle() {
        val bundle = version(10).copy(artifactType = ArtifactType.APKM)

        val outcome = VersionSelection.select(
            VersionSelection.Request(
                versions = listOf(bundle),
                device = pixel7,
                supportedArtifactTypes = setOf(ArtifactType.APK),
            ),
        )

        // The only way to **prove** the default changed: without this, the test above would
        // pass even with `supportedArtifactTypes` removed entirely.
        assertThat(outcome).isEqualTo(VersionSelection.Outcome.NothingInstallable)
    }
}

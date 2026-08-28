package com.multistore.core.common.version

import com.google.common.truth.Truth.assertThat
import com.multistore.core.common.version.VersionSelection.Installability
import com.multistore.core.model.AppVersion
import com.multistore.core.model.ArtifactType
import com.multistore.core.model.DeviceProfile
import com.multistore.core.model.VersionRef
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * What happens on pressing **one** version in the history.
 *
 * The twin question to `VersionSelection.select`, and not the same one: that chooses among all,
 * this judges a single one. The constraint binding them is that they cannot contradict each other
 * on the same screen, and the last test in this file is what holds that fast.
 */
@DisplayName("What can be done with a version")
class InstallabilityTest {

    private val pixel7 = DeviceProfile(sdkInt = 36, supportedAbis = listOf("arm64-v8a", "armeabi-v7a"))

    private fun version(
        code: Long?,
        name: String = code?.toString() ?: "?",
        minSdk: Int? = null,
        abis: List<String> = emptyList(),
        artifact: ArtifactType = ArtifactType.APK,
        channels: Set<String> = emptySet(),
    ) = AppVersion(
        versionName = name,
        versionCode = code,
        ref = VersionRef("v-$name"),
        minSdk = minSdk,
        abis = abis,
        artifactType = artifact,
        releaseChannels = channels,
    )

    private fun verdict(
        version: AppVersion,
        installed: Long? = null,
        artifacts: Set<ArtifactType> = ArtifactType.entries.toSet(),
    ) = VersionSelection.installability(version, pixel7, installed, artifacts)

    @Test
    @DisplayName("nothing installed: any compatible version can be taken")
    fun installableWhenNothingIsInstalled() {
        assertThat(verdict(version(100))).isEqualTo(Installability.INSTALLABLE)
    }

    @Test
    @DisplayName("newer than the installed one: allowed")
    fun newerThanInstalled() {
        assertThat(verdict(version(120), installed = 100)).isEqualTo(Installability.INSTALLABLE)
    }

    @Test
    @DisplayName("it is the installed one, and the row says so instead of offering it again")
    fun sameAsInstalled() {
        assertThat(verdict(version(100), installed = 100)).isEqualTo(Installability.INSTALLED)
    }

    /**
     * The case this function exists for.
     *
     * Android does not replace an app with an earlier version, and neither installer passes a
     * downgrade flag: `SessionInstaller` has no public one, `ShellInstaller` creates the session
     * with `-r` and not `-d`. Offering it would mean a whole download and a refusal from the
     * system at the end, with the user having waited for nothing.
     */
    @Test
    @DisplayName("older than the installed one: not offered, and the reason is given")
    fun olderThanInstalled() {
        assertThat(verdict(version(90), installed = 100))
            .isEqualTo(Installability.OLDER_THAN_INSTALLED)
    }

    @Test
    @DisplayName("minSdk too high: incompatible, and so before any number comparison")
    fun incompatibleBeatsTheVersionComparison() {
        // Also older than the installed one: the reason to give is the one the user cannot
        // resolve at all, not the one an uninstall would resolve.
        assertThat(verdict(version(90, minSdk = 99), installed = 100))
            .isEqualTo(Installability.INCOMPATIBLE)
    }

    @Test
    @DisplayName("no ABI in common: incompatible")
    fun incompatibleAbi() {
        assertThat(verdict(version(120, abis = listOf("x86_64")), installed = 100))
            .isEqualTo(Installability.INCOMPATIBLE)
    }

    @Test
    @DisplayName("an artifact we cannot open says so, rather than \"incompatible\"")
    fun unsupportedArtifact() {
        val apks = version(120, artifact = ArtifactType.APKS)
        assertThat(verdict(apks, artifacts = setOf(ArtifactType.APK)))
            .isEqualTo(Installability.UNSUPPORTED_ARTIFACT)
    }

    /**
     * Four stores out of nine do not publish the `versionCode` — uptodown and an1 across their
     * whole sites.
     *
     * With the number missing the comparison is not made and the version stays pressable: the
     * same honesty as `UpToDate.comparable`, where missing data does not disguise itself as an
     * answer. The system will decide, since it has the real number; we never had it.
     */
    @Test
    @DisplayName("without a versionCode we do not pretend to know: it stays installable")
    fun unknownVersionCodeStaysInstallable() {
        assertThat(verdict(version(null), installed = 100)).isEqualTo(Installability.INSTALLABLE)
        assertThat(verdict(version(90), installed = null)).isEqualTo(Installability.INSTALLABLE)
    }

    /**
     * The constraint between the two functions, and why they live in the same file.
     *
     * The listing shows the top button — which comes from `select` — together with the history
     * below, which comes from here. If `select` offered a version this one declares
     * uninstallable, the user would read two different answers to the same question ten lines
     * apart.
     */
    @Test
    @DisplayName("the version the rule offers is always pressable in the history too")
    fun theOfferedVersionIsAlwaysInstallableInTheList() {
        val versions = listOf(
            version(90),
            version(100),
            version(130, channels = setOf("Beta")),
            version(140, minSdk = 99),
            version(150, artifact = ArtifactType.APKS),
        )
        val offered = VersionSelection.select(
            VersionSelection.Request(
                versions = versions,
                device = pixel7,
                installedVersionCode = 90,
                supportedArtifactTypes = setOf(ArtifactType.APK),
            ),
        )
        val version = (offered as VersionSelection.Outcome.Offer).version

        assertThat(version.versionCode).isEqualTo(100)
        assertThat(verdict(version, installed = 90, artifacts = setOf(ArtifactType.APK)))
            .isEqualTo(Installability.INSTALLABLE)
    }
}

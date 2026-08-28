package com.multistore.store.fdroid.index

import com.google.common.truth.Truth.assertThat
import com.multistore.core.model.ContentKind
import com.multistore.core.model.ScreenshotKind
import com.multistore.store.fdroid.FdroidConfig
import com.multistore.store.fdroid.FdroidRefs
import com.multistore.store.fdroid.Fixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The F-Droid index's traps, proven against the real index.
 *
 * Every test in here corresponds to a fact measured on `index-v2.json` on 23/08/2026, not to a guess
 * about how the index might be built. They are the points where a parser written by reading the
 * documentation works on 99% of packages and gets the rest wrong — silently.
 */
@DisplayName("Projecting a package: the real index's traps")
class PackageProjectionTest {

    private val projection = PackageProjection(repoUrl = FdroidConfig().repoUrl)

    private fun project(packageName: String) =
        projection.project(packageName, Fixtures.slicePackage(packageName))

    // --- Trap 1: the anti-features are only in the versions ---------------------------------

    @Test
    @DisplayName("anti-features are read from the versions, because at package level they do not exist")
    fun antiFeaturesLiveOnVersionsOnly() {
        val metadata = Fixtures.slicePackage(Fixtures.PKG_PROTONVPN)
            .getValue("metadata") as kotlinx.serialization.json.JsonObject

        // 0 occurrences across 4,257 packages in the real index: looking for them here never finds any.
        assertThat(metadata.keys).doesNotContain("antiFeatures")

        val detail = requireNotNull(project(Fixtures.PKG_PROTONVPN))
        val flagged = detail.versions.filter { it.antiFeatures.isNotEmpty() }
        assertThat(flagged).isNotEmpty()
        assertThat(flagged.first().antiFeatures.map { it.id })
            .containsAtLeast("NonFreeNet", "Tracking")
    }

    // --- Trap 2: the three .zip entries ------------------------------------------------------

    @Test
    @DisplayName("the package made only of .zip files is not projected at all")
    fun otaZipPackageIsDropped() {
        // `org.fdroid.fdroid.privileged.ota` publishes 3 `.zip` files: they are OTAs to be flashed,
        // not installable with PackageInstaller. They are also the only 3 entries without
        // `manifest.signer.sha256` and without `usesSdk`, and belong to the only package with no
        // `preferredSigner`. Filtering by extension solves all three problems at once.
        assertThat(project(Fixtures.PKG_OTA)).isNull()
    }

    // --- Trap 3: the non-canonical file names ------------------------------------------------

    @Test
    @DisplayName("the file URL comes from the index, not rebuilt from packageName and versionCode")
    fun downloadUrlComesFromTheIndex() {
        val detail = requireNotNull(project(Fixtures.PKG_BANGLEJS))
        val decoded = detail.versions.mapNotNull { FdroidRefs.decode(it.ref) }

        assertThat(decoded).isNotEmpty()
        // 45 entries out of 12,871 use `<pkg>_<versionCode>_<githash>.apk`. A "clever" construction
        // of the name would work for 99.7% of cases and fail on the rest, in production and with no
        // useful error message.
        val nonCanonical = decoded.filter { decodedVersion ->
            val expected = "/${Fixtures.PKG_BANGLEJS}_${
                detail.versions.first { it.ref == FdroidRefs.versionRef(
                    decodedVersion.sha256, decodedVersion.sizeBytes, decodedVersion.fileName,
                ) }.versionCode
            }.apk"
            decodedVersion.fileName != expected
        }
        assertThat(nonCanonical).isNotEmpty()
        assertThat(decoded.map { it.fileName }.all { it.endsWith(".apk") }).isTrue()
    }

    // --- Trap 6: the release channel ---------------------------------------------------------

    @Test
    @DisplayName("F-Droid's highest version is a Beta, and is not the one to show")
    fun betaChannelIsMarked() {
        val detail = requireNotNull(project(Fixtures.PKG_FDROID))
        val highest = detail.versions.maxBy { it.versionCode ?: Long.MIN_VALUE }

        assertThat(highest.versionCode).isEqualTo(2_000_040L)
        assertThat(highest.releaseChannels).containsExactly("Beta")
        assertThat(highest.isDefaultChannel).isFalse()

        // The listing's summary shows the default channel's version, not the raw maximum: it is
        // 1.23.2, the same one the official API suggests.
        assertThat(detail.summary.latestVersionCode).isEqualTo(1_023_052L)
        assertThat(detail.summary.latestVersionName).isEqualTo("1.23.2")
    }

    // --- Trap 7: several signers, and a duplicated versionCode --------------------------------

    @Test
    @DisplayName("the same versionCode can appear twice, with different signatures")
    fun versionCodeIsNotUnique() {
        val detail = requireNotNull(project(Fixtures.PKG_KEYBOARD))
        val fifty = detail.versions.filter { it.versionCode == 50L }

        assertThat(fifty).hasSize(2)
        assertThat(fifty.map { it.signerSha256 }.toSet()).hasSize(2)
        // They are two different files, and the SHA-256 tells them apart: it is a version's natural
        // key, while `(package, versionCode)` is not.
        assertThat(fifty.map { it.sha256 }.toSet()).hasSize(2)
        assertThat(detail.preferredSignerSha256).isNotNull()
        assertThat(fifty.count { it.signerSha256 == detail.preferredSignerSha256 }).isEqualTo(1)
    }

    // --- Integrity: hash and signer on everything --------------------------------------------

    @Test
    @DisplayName("every .apk version carries a hash and a signer, as the capability promises")
    fun everyApkVersionCarriesHashAndSigner() {
        val everything = Fixtures.slicePackages().keys.mapNotNull { project(it) }
        val versions = everything.flatMap { it.versions }

        assertThat(versions).isNotEmpty()
        // `providesHash = ALWAYS` and `providesSignerFingerprint = true` are declared by the adapter:
        // this test is what makes them verified statements rather than optimism.
        versions.forEach {
            assertThat(it.sha256).isNotNull()
            assertThat(it.signerSha256).isNotNull()
        }
    }

    // --- Missing fields: what the index does not guarantee -----------------------------------

    @Test
    @DisplayName("a package with no icon, summary or description is projected all the same")
    fun missingOptionalFieldsAreTolerated() {
        val noIcon = requireNotNull(project(Fixtures.PKG_NO_ICON))
        assertThat(noIcon.summary.iconUrl).isNull()
        assertThat(noIcon.summary.title).isNotEmpty()

        val noText = requireNotNull(project(Fixtures.PKG_NO_TEXT))
        assertThat(noText.summary.summary.isEmpty).isTrue()
        assertThat(noText.description.isEmpty).isTrue()
        // The title is always there: `name` is the only localised field present on all 4,257.
        assertThat(noText.summary.title).isNotEmpty()
    }

    @Test
    @DisplayName("a summary translated without en-US still resolves")
    fun localizedFallbackWorksWithoutEnglish() {
        val detail = requireNotNull(project(Fixtures.PKG_STREETCOMPLETE))

        assertThat(detail.summary.summary.resolve(listOf("it"))).isNotNull()
        // Even asking for a language that is not there, something has to come out: an empty listing
        // would be worse than a listing in another language.
        assertThat(detail.summary.summary.resolve(listOf("nl"))).isNotNull()
    }

    // --- The rest of the projection -----------------------------------------------------------

    @Test
    @DisplayName("relative URLs become absolute against the repository")
    fun relativePathsBecomeAbsolute() {
        val detail = requireNotNull(project(Fixtures.PKG_FDROID))

        assertThat(detail.summary.iconUrl).startsWith("https://f-droid.org/repo/")
        assertThat(detail.screenshots).isNotEmpty()
        detail.screenshots.forEach { assertThat(it.url).startsWith("https://f-droid.org/repo/") }
        assertThat(detail.screenshots.map { it.kind }).contains(ScreenshotKind.PHONE)
    }

    @Test
    @DisplayName("minSdk and ABIs come from the version's manifest")
    fun manifestFieldsAreRead() {
        val alarm = requireNotNull(project(Fixtures.PKG_MIN_SDK_33))
        assertThat(alarm.versions.map { it.minSdk }).contains(33)

        val newPipe = requireNotNull(project(Fixtures.PKG_NEWPIPE))
        val perAbi = newPipe.versions.filter { it.abis.isNotEmpty() }
        assertThat(perAbi).isNotEmpty()
        // Separate builds per ABI: without the ABI filter an APK that does not start would be downloaded.
        assertThat(perAbi.flatMap { it.abis }.toSet()).containsAtLeast("arm64-v8a", "x86_64")
    }

    @Test
    @DisplayName("apps and games are told apart by their categories")
    fun contentKindComesFromCategories() {
        assertThat(requireNotNull(project(Fixtures.PKG_FDROID)).summary.contentKind)
            .isEqualTo(ContentKind.APP)
    }

    @Test
    @DisplayName("a package with a single version and an unusual id is no exception")
    fun oddPackageIdIsFine() {
        val detail = requireNotNull(project(Fixtures.PKG_SNAKE))

        assertThat(detail.summary.packageName).isEqualTo(Fixtures.PKG_SNAKE)
        assertThat(detail.versions).hasSize(1)
    }

    // --- The key == file.sha256 invariant, and multiple signers -------------------------------

    @Test
    @DisplayName("a version whose key contradicts file.sha256 is discarded")
    fun contradictingHashIsDropped() {
        // On the real index it does not happen — 12,871 entries out of 12,871 match — but the
        // `versionRef` is born from `sha256`, and it is the hash the pre-install pipeline compares
        // against the downloaded file. If key and field contradict each other we do not know which
        // file will arrive, and an ambiguous artefact is not safely installable.
        val detail = projection.project("com.example.app", packageWith(key = "aa".repeat(32), declared = "bb".repeat(32)))

        assertThat(detail?.versions.orEmpty()).isEmpty()
    }

    @Test
    @DisplayName("when key and field match, the version passes")
    fun matchingHashIsKept() {
        val same = "cc".repeat(32)

        val detail = requireNotNull(projection.project("com.example.app", packageWith(key = same, declared = same)))

        assertThat(detail.versions).hasSize(1)
        assertThat(detail.versions.single().sha256?.hex).isEqualTo(same)
    }

    @Test
    @DisplayName("with more than one signer the first is not guessed: the signer stays unknown")
    fun multipleSignersMeanUnknownSigner() {
        val sha = "dd".repeat(32)

        // A co-signed APK's identity is the **set** of signers, not the first. `null` here already
        // means "we do not know": version selection does not filter by signature and the decision is
        // left to the pre-install pipeline, which reads the real signers from the APK. Guessing could
        // have invented a signature conflict — whose way out is "uninstall and reinstall, lose your
        // data".
        val two = requireNotNull(
            projection.project("com.example.app", packageWith(key = sha, declared = sha, signers = 2)),
        )
        assertThat(two.versions.single().signerSha256).isNull()

        val one = requireNotNull(
            projection.project("com.example.app", packageWith(key = sha, declared = sha, signers = 1)),
        )
        assertThat(one.versions.single().signerSha256).isNotNull()
    }

    private fun packageWith(key: String, declared: String, signers: Int = 1): JsonObject {
        val signerList = (1..signers).joinToString(", ") { "\"${"%02x".format(it).repeat(32)}\"" }
        return Json.parseToJsonElement(
            """
            {
              "metadata": { "name": { "en-US": "Example" }, "added": 1 },
              "versions": {
                "$key": {
                  "added": 1,
                  "file": { "name": "/example.apk", "sha256": "$declared", "size": 100 },
                  "manifest": {
                    "versionName": "1.0",
                    "versionCode": 1,
                    "signer": { "sha256": [$signerList] }
                  }
                }
              }
            }
            """.trimIndent(),
        ) as JsonObject
    }
}

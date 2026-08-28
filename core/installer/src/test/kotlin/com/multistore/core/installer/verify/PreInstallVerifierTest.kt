package com.multistore.core.installer.verify

import com.google.common.truth.Truth.assertThat
import com.multistore.core.model.InstalledPackage
import com.multistore.core.model.Sha256
import java.io.File
import org.junit.Test

/**
 * The pre-install verification pipeline, on **real** APKs.
 *
 * The four fixtures are real APKs, generated with `aapt2` and `apksigner` and committed: 8.5 KB
 * each. Testing this pipeline on fake objects would prove its conditions are well written; testing
 * it on really signed files proves `apksig` reads from them what we expect — which is the half of
 * the work that can go wrong silently.
 *
 * The four required outcomes are here, plus the ones review added: downgrade, unexpected signer on
 * first installation, unsigned APK.
 */
class PreInstallVerifierTest {

    private val reader = ApksigApkArchiveReader()
    private val verifier = PreInstallVerifier(reader)

    private val validPackage = "com.multistore.fixture.valid"
    private val otherPackage = "com.multistore.fixture.other"

    private fun fixture(name: String): File {
        val url = requireNotNull(javaClass.classLoader.getResource("fixtures/apk/$name")) {
            "Missing APK fixture: $name"
        }
        return File(url.toURI())
    }

    private fun expectation(
        declared: String? = validPackage,
        expectedSha256: Sha256? = null,
        expectedSize: Long? = null,
        expectedSigner: Sha256? = null,
        installed: InstalledPackage? = null,
        sdk: Int = 36,
        allowDowngrade: Boolean = false,
        allowSignerMismatch: Boolean = false,
        allowHashMismatch: Boolean = false,
    ) = PreInstallVerifier.Expectation(
        declaredPackageName = declared,
        expectedSha256 = expectedSha256,
        expectedSizeBytes = expectedSize,
        expectedSignerSha256 = expectedSigner,
        installed = installed,
        deviceSdkInt = sdk,
        allowDowngrade = allowDowngrade,
        allowSignerMismatch = allowSignerMismatch,
        allowHashMismatch = allowHashMismatch,
    )

    private fun signerOf(name: String): Sha256 {
        val read = reader.read(fixture(name))
        return (read as ApkReadResult.Readable).info.signerSha256.first()
    }

    // --- Reading ------------------------------------------------------------------------------

    @Test
    fun `a signed APK is read and its signature really is verified`() {
        val read = reader.read(fixture("valid.apk"))

        val info = (read as ApkReadResult.Readable).info
        assertThat(info.packageName).isEqualTo(validPackage)
        assertThat(info.versionCode).isEqualTo(42L)
        assertThat(info.minSdk).isEqualTo(26)
        assertThat(info.signerSha256).hasSize(1)
        // At least one verified signature scheme: if the list were empty, `isVerified` would have
        // said yes without anything having been checked.
        assertThat(info.signatureSchemes).isNotEmpty()
    }

    @Test
    fun `an unsigned APK is not installable`() {
        val read = reader.read(fixture("unsigned.apk"))

        assertThat(read).isInstanceOf(ApkReadResult.NotVerified::class.java)
    }

    @Test
    fun `a file that is not an APK does not blow the reader up`() {
        val notAnApk = File.createTempFile("fake", ".apk").apply { writeText("not a zip") }
        try {
            assertThat(reader.read(notAnApk)).isInstanceOf(ApkReadResult.Unreadable::class.java)
        } finally {
            notAnApk.delete()
        }
    }

    // --- The four verification outcomes ---------------------------------------------------------

    @Test
    fun `valid case - it passes and declares what it verified`() {
        val info = (reader.read(fixture("valid.apk")) as ApkReadResult.Readable).info

        val outcome = verifier.verify(
            fixture("valid.apk"),
            expectation(expectedSha256 = info.fileSha256, expectedSize = info.sizeBytes),
        )

        val ok = outcome as PreInstallVerifier.VerificationOutcome.Ok
        assertThat(ok.packageNameWasVerified).isTrue()
        assertThat(ok.hashWasVerified).isTrue()
    }

    @Test
    fun `store that publishes no hash - it passes, but hashWasVerified stays false`() {
        val outcome = verifier.verify(fixture("valid.apk"), expectation(expectedSha256 = null))

        val ok = outcome as PreInstallVerifier.VerificationOutcome.Ok
        assertThat(ok.hashWasVerified).isFalse()
    }

    @Test
    fun `wrong packageName - hard block`() {
        // The APK declares `com.multistore.fixture.other`, the listing promised the other one. This
        // is the defence against installing the wrong package, and it is not configurable.
        val outcome = verifier.verify(fixture("wrong-package.apk"), expectation(declared = validPackage))

        val mismatch = outcome as PreInstallVerifier.VerificationOutcome.PackageNameMismatch
        assertThat(mismatch.declared).isEqualTo(validPackage)
        assertThat(mismatch.actual).isEqualTo(otherPackage)
    }

    @Test
    fun `signature different from the installed one - it blocks before the system does`() {
        val installedSigner = signerOf("valid.apk")

        val outcome = verifier.verify(
            fixture("foreign-signer.apk"),
            expectation(
                installed = InstalledPackage(validPackage, "1.0.0", 1, installedSigner),
            ),
        )

        // The operating system would refuse it anyway: catching it here allows offering "uninstall
        // and reinstall, lose your data" instead of an opaque error halfway through.
        assertThat(outcome).isInstanceOf(
            PreInstallVerifier.VerificationOutcome.SignerMismatchWithInstalled::class.java,
        )
    }

    @Test
    fun `wrong hash - it stops before looking inside`() {
        val wrong = requireNotNull(Sha256.parseOrNull("ab".repeat(32)))

        val outcome = verifier.verify(fixture("valid.apk"), expectation(expectedSha256 = wrong))

        assertThat(outcome).isInstanceOf(PreInstallVerifier.VerificationOutcome.HashMismatch::class.java)
    }

    @Test
    fun `allow_unverified_hash - it does not block, but does not pass the file off as verified`() {
        val wrong = requireNotNull(Sha256.parseOrNull("ab".repeat(32)))

        val outcome = verifier.verify(
            fixture("valid.apk"),
            expectation(expectedSha256 = wrong, allowHashMismatch = true),
        )

        // It is the half that matters of the inverted setting: the user chose not to be stopped, not
        // to be lied to. If `hashWasVerified` came back `true` the verification card would say
        // "verified" about a file whose hash does not match — i.e. the exact opposite of what
        // happened.
        val ok = outcome as PreInstallVerifier.VerificationOutcome.Ok
        assertThat(ok.hashWasVerified).isFalse()
    }

    @Test
    fun `size different from the expected one - the digest is not even computed`() {
        val outcome = verifier.verify(fixture("valid.apk"), expectation(expectedSize = 999_999L))

        assertThat(outcome).isInstanceOf(PreInstallVerifier.VerificationOutcome.SizeMismatch::class.java)
    }

    // --- Downgrade, first install, relaxations, and the split checks ----------------------------

    @Test
    fun `downgrade - it blocks, unless the user asks for it`() {
        val signer = signerOf("valid.apk")
        val installed = InstalledPackage(validPackage, "9.9", 99, signer)

        val blocked = verifier.verify(fixture("valid.apk"), expectation(installed = installed))
        val allowed = verifier.verify(
            fixture("valid.apk"),
            expectation(installed = installed, allowDowngrade = true),
        )

        // The versionCode used to be extracted and never compared: installing an older version is the
        // classic way of reintroducing an already-fixed vulnerability.
        val downgrade = blocked as PreInstallVerifier.VerificationOutcome.Downgrade
        assertThat(downgrade.installedVersionCode).isEqualTo(99L)
        assertThat(downgrade.offeredVersionCode).isEqualTo(42L)
        assertThat(allowed).isInstanceOf(PreInstallVerifier.VerificationOutcome.Ok::class.java)
    }

    @Test
    fun `first installation - the signature is compared with the one the store declared`() {
        val expectedSigner = signerOf("valid.apk")

        val wrongOne = verifier.verify(
            fixture("foreign-signer.apk"),
            expectation(expectedSigner = expectedSigner),
        )
        val rightOne = verifier.verify(fixture("valid.apk"), expectation(expectedSigner = expectedSigner))

        // Without this check, the first installation — the only one that establishes which signature
        // chain we tie ourselves to forever — would be the only unverified one.
        assertThat(wrongOne).isInstanceOf(PreInstallVerifier.VerificationOutcome.UnexpectedSigner::class.java)
        val ok = rightOne as PreInstallVerifier.VerificationOutcome.Ok
        assertThat(ok.signerWasVerified).isTrue()
    }

    @Test
    fun `store that publishes no packageName - it passes, but says so`() {
        val outcome = verifier.verify(fixture("valid.apk"), expectation(declared = null))

        // Four stores out of nine do not publish the packageName. The check cannot be made, and the
        // difference between "verified" and "not contradicted" has to reach the UI.
        val ok = outcome as PreInstallVerifier.VerificationOutcome.Ok
        assertThat(ok.packageNameWasVerified).isFalse()
    }

    @Test
    fun `minSdk higher than the device's - incompatible`() {
        val outcome = verifier.verify(fixture("valid.apk"), expectation(sdk = 21))

        assertThat(outcome).isInstanceOf(PreInstallVerifier.VerificationOutcome.Incompatible::class.java)
    }

    @Test
    fun `the user setting relaxes the signature check but not the packageName one`() {
        val installedSigner = signerOf("valid.apk")

        val signerRelaxed = verifier.verify(
            fixture("foreign-signer.apk"),
            expectation(
                installed = InstalledPackage(validPackage, "1.0", 1, installedSigner),
                allowSignerMismatch = true,
            ),
        )
        val packageStillBlocked = verifier.verify(
            fixture("wrong-package.apk"),
            expectation(allowSignerMismatch = true),
        )

        // `allow_signer_mismatch` is a setting; the packageName match is not and must not become one
        // by accident through relaxing the other.
        assertThat(signerRelaxed).isInstanceOf(PreInstallVerifier.VerificationOutcome.Ok::class.java)
        assertThat(packageStillBlocked)
            .isInstanceOf(PreInstallVerifier.VerificationOutcome.PackageNameMismatch::class.java)
    }

    // --- Split containers ------------------------------------------------------------------

    /**
     * The splits in these tests are **real APKs but not real splits**.
     *
     * Step 8 does not look at the `split` attribute — nor does `PackageInstaller`, which reads it
     * from each file's manifest and decides for itself which is the base — but it does look at
     * package, version and signer. Those three things in the fixtures are real and different from
     * each other, and that is exactly the material needed: `wrong-package.apk` has another package,
     * `foreign-signer.apk` another key.
     */
    private fun payload(base: String, vararg splits: String) = PreInstallVerifier.Payload(
        delivered = fixture(base),
        base = fixture(base),
        splits = splits.map(::fixture),
    )

    @Test
    fun `a split of the same app passes, and ends up in the outcome`() {
        val outcome = verifier.verify(payload("valid.apk", "valid.apk"), expectation())

        val ok = outcome as PreInstallVerifier.VerificationOutcome.Ok
        assertThat(ok.splits).hasSize(1)
        assertThat(ok.splits.single().packageName).isEqualTo(validPackage)
    }

    @Test
    fun `a split of another package is a refusal that says which`() {
        val outcome = verifier.verify(payload("valid.apk", "wrong-package.apk"), expectation())

        // Without this check another app's APK would enter the same session as the base, and
        // `PackageInstaller` would refuse it with an error that does not name the entry.
        val rejected = outcome as PreInstallVerifier.VerificationOutcome.ForeignSplit
        assertThat(rejected.name).isEqualTo("wrong-package.apk")
        assertThat(rejected.reason).contains(otherPackage)
    }

    @Test
    fun `a split signed by another key is a refusal`() {
        val outcome = verifier.verify(payload("valid.apk", "foreign-signer.apk"), expectation())

        // Same package, same version, another key: a piece of app not produced by whoever produced
        // the base.
        val rejected = outcome as PreInstallVerifier.VerificationOutcome.ForeignSplit
        assertThat(rejected.reason).contains("key")
    }

    @Test
    fun `an unsigned split is a refusal`() {
        val outcome = verifier.verify(payload("valid.apk", "unsigned.apk"), expectation())

        assertThat(outcome).isInstanceOf(PreInstallVerifier.VerificationOutcome.ForeignSplit::class.java)
    }

    @Test
    fun `the hash the store declared is compared with the container, not with the base`() {
        val container = fixture("wrong-package.apk")
        val declared = digestOf(container)

        val outcome = verifier.verify(
            PreInstallVerifier.Payload(delivered = container, base = fixture("valid.apk")),
            expectation(expectedSha256 = declared),
        )

        // `wrong-package.apk` acts as a fake container: what matters is that the comparison went
        // **against it**. With the base's digest the outcome would be `HashMismatch`, i.e. every
        // container installation would fail accusing the store of publishing wrong hashes.
        assertThat((outcome as PreInstallVerifier.VerificationOutcome.Ok).hashWasVerified).isTrue()
    }

    @Test
    fun `a hash that does not match the container blocks`() {
        val outcome = verifier.verify(
            PreInstallVerifier.Payload(
                delivered = fixture("wrong-package.apk"),
                base = fixture("valid.apk"),
            ),
            expectation(expectedSha256 = digestOf(fixture("valid.apk"))),
        )

        assertThat(outcome).isInstanceOf(PreInstallVerifier.VerificationOutcome.HashMismatch::class.java)
    }

    private fun digestOf(file: File): Sha256 = Sha256.ofBytes(
        java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()),
    )
}

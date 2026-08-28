package com.multistore.store.fdroid.index

import com.google.common.truth.Truth.assertThat
import com.multistore.core.model.Sha256
import com.multistore.store.fdroid.FdroidConfig
import com.multistore.store.fdroid.FdroidPaths
import com.multistore.store.fdroid.Fixtures
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The index's chain of trust, proven on the real files.
 *
 * `entry.jar` is the authentic one from `f-droid.org`; the other three fixtures are the three ways
 * an attacker can try: content modified leaving the original signature, signature removed entirely,
 * signature redone with their own key. A verifier getting even one of these three wrong protects
 * against nothing — and the most common way to get them wrong is writing code that *looks* like it
 * verifies (see [JarSignatureVerifier]'s KDoc on the order between reading the stream and
 * `getCertificates`).
 */
@DisplayName("entry.jar signature verification")
class JarSignatureVerifierTest {

    private val verifier = JarSignatureVerifier(FdroidConfig.PINNED_SIGNER)

    @Test
    @DisplayName("the authentic entry.jar passes, and the signer is the pinned one")
    fun authenticJarIsVerified() {
        val result = verifier.readVerifiedEntry(Fixtures.file(Fixtures.ENTRY_JAR), FdroidPaths.ENTRY_JSON_ENTRY)

        assertThat(result).isInstanceOf(JarSignatureVerifier.Result.Verified::class.java)
        val verified = result as JarSignatureVerifier.Result.Verified
        assertThat(verified.signerFingerprint).isEqualTo(FdroidConfig.PINNED_SIGNER)
        assertThat(verified.content.decodeToString()).contains("\"index-v2.json\"".drop(1))
    }

    @Test
    @DisplayName("content modified with the original signature is rejected")
    fun tamperedContentIsRejected() {
        val result = verifier.readVerifiedEntry(
            Fixtures.file(Fixtures.ENTRY_JAR_TAMPERED),
            FdroidPaths.ENTRY_JSON_ENTRY,
        )

        // It is the most insidious case: the archive has a valid signature, produced by the right
        // key, over content that is no longer the signed one. A check stopping at the certificate's
        // fingerprint would let it through.
        assertThat(result).isInstanceOf(JarSignatureVerifier.Result.Tampered::class.java)
    }

    @Test
    @DisplayName("an unsigned archive is rejected")
    fun unsignedJarIsRejected() {
        val result = verifier.readVerifiedEntry(
            Fixtures.file(Fixtures.ENTRY_JAR_UNSIGNED),
            FdroidPaths.ENTRY_JSON_ENTRY,
        )

        assertThat(result).isEqualTo(JarSignatureVerifier.Result.Unsigned)
    }

    @Test
    @DisplayName("another key's signature is rejected, however valid")
    fun foreignSignerIsRejected() {
        val result = verifier.readVerifiedEntry(
            Fixtures.file(Fixtures.ENTRY_JAR_FOREIGN),
            FdroidPaths.ENTRY_JSON_ENTRY,
        )

        assertThat(result).isInstanceOf(JarSignatureVerifier.Result.WrongSigner::class.java)
        val wrong = result as JarSignatureVerifier.Result.WrongSigner
        assertThat(wrong.expected).isEqualTo(FdroidConfig.PINNED_SIGNER)
        assertThat(wrong.actual).isNotEqualTo(FdroidConfig.PINNED_SIGNER)
    }

    @Test
    @DisplayName("asking for an entry that does not exist does not throw, it returns Malformed")
    fun missingEntryIsMalformed() {
        val result = verifier.readVerifiedEntry(Fixtures.file(Fixtures.ENTRY_JAR), "does-not-exist.json")

        assertThat(result).isInstanceOf(JarSignatureVerifier.Result.Malformed::class.java)
    }

    @Test
    @DisplayName("a different pin rejects even the authentic archive")
    fun differentPinRejectsAuthenticJar() {
        // The proof that the pin really counts: if this test passed with the right pin too, it would
        // mean the comparison does not happen.
        val otherPin = requireNotNull(Sha256.parseOrNull("00".repeat(Sha256.HEX_LENGTH / 2)))
        val result = JarSignatureVerifier(otherPin)
            .readVerifiedEntry(Fixtures.file(Fixtures.ENTRY_JAR), FdroidPaths.ENTRY_JSON_ENTRY)

        assertThat(result).isInstanceOf(JarSignatureVerifier.Result.WrongSigner::class.java)
    }
}

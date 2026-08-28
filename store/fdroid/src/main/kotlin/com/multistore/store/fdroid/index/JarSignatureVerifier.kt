package com.multistore.store.fdroid.index

import com.multistore.core.model.Sha256
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.jar.JarEntry
import java.util.jar.JarFile

/**
 * Verifies a signed JAR and extracts its content **only if** the signer is the expected one.
 *
 * ### Why `JarFile` and not `JarInputStream`
 *
 * `JarInputStream` verifies signatures only if `META-INF/MANIFEST.MF` is the **first** entry of the
 * archive. In F-Droid's `entry.jar` the real order is `entry.json`, `CIARANG.SF`, `CIARANG.RSA`, and
 * **the manifest last**. With `JarInputStream` verification would not happen: `getCertificates()`
 * would return `null` and a carelessly written check ("if the certificates do not match, discard")
 * would let everything through. Random access is needed, therefore a file on disk.
 *
 * ### What this verification really guarantees, and what it does not
 *
 * It guarantees that **the entry we read** is listed in the manifest, that its digest matches the
 * declared one, and that the signature block is valid and produced by the key whose certificate has
 * the expected fingerprint.
 *
 * It guarantees nothing about the *other* entries in the archive: a JAR can contain unsigned files
 * next to signed ones, and the signature of the latter says nothing about the former. That is why
 * [readVerifiedEntry] reads a named entry and ignores everything else: there is no path in which a
 * file added by an attacker is so much as opened.
 *
 * The PKIX chain is **not** validated, and that is not an oversight: F-Droid's certificate is
 * self-signed, so no CA can confirm it. The trust rests in the pin.
 */
class JarSignatureVerifier(private val expectedSigner: Sha256) {

    sealed interface Result {
        data class Verified(val content: ByteArray, val signerFingerprint: Sha256) : Result {
            override fun equals(other: Any?): Boolean =
                this === other ||
                    (other is Verified && content.contentEquals(other.content) &&
                        signerFingerprint == other.signerFingerprint)

            override fun hashCode(): Int = 31 * content.contentHashCode() + signerFingerprint.hashCode()
        }

        /** The archive is malformed, or the requested entry is not there. */
        data class Malformed(val reason: String) : Result

        /** The entry is not signed, or is not listed in the manifest. */
        data object Unsigned : Result

        /** Signed, but by somebody else. */
        data class WrongSigner(val actual: Sha256?, val expected: Sha256) : Result

        /** The digest does not match: the content was modified after signing. */
        data class Tampered(val reason: String) : Result
    }

    /**
     * Reads [entryName] from the JAR, returning its bytes only if the signature holds.
     *
     * The order of operations is not negotiable: **first** the stream is read to the end, **then**
     * the certificates are asked for. `JarEntry.getCertificates()` before a complete read returns
     * `null` — not because they are missing, but because verification has not happened yet. It is
     * the classic mistake with this API, and its outcome is a check that looks like it works and
     * checks nothing.
     */
    fun readVerifiedEntry(jar: File, entryName: String): Result = try {
        JarFile(jar, /* verify = */ true).use { jarFile ->
            val entry = jarFile.getJarEntry(entryName)
                ?: return Result.Malformed("the entry '$entryName' does not exist in the archive")
            val content = jarFile.getInputStream(entry).use { it.readBytes() }
            verifySigner(entry, content)
        }
    } catch (e: SecurityException) {
        // JarFile signals both a mismatched digest and an invalid signature this way.
        Result.Tampered(e.message ?: "invalid signature")
    } catch (e: IOException) {
        Result.Malformed(e.message ?: "unreadable archive")
    }

    private fun verifySigner(entry: JarEntry, content: ByteArray): Result {
        val certificates = entry.certificates
        if (certificates.isNullOrEmpty()) return Result.Unsigned

        // A single signer: verified against the real index, 0 entries out of 12,871 have more than
        // one. Accepting several would mean accepting that *any one* of the signers is enough, which
        // is a weaker property than the one we want.
        val leaf = certificates.firstOrNull() as? X509Certificate
            ?: return Result.Malformed("the first certificate is not X.509")
        val actual = Sha256.ofBytes(MessageDigest.getInstance("SHA-256").digest(leaf.encoded))
        if (actual != expectedSigner) return Result.WrongSigner(actual, expectedSigner)
        return Result.Verified(content, actual)
    }
}

package com.multistore.core.installer.verify

import com.android.apksig.ApkVerifier
import com.android.apksig.apk.ApkUtils
import com.android.apksig.util.DataSources
import com.multistore.core.model.Sha256
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads and **verifies** an APK with `apksig`, the same library APKs are signed with.
 *
 * ### Why not `PackageManager.getPackageArchiveInfo`
 *
 * It would look like the obvious choice — it is the operating system's parser — but it has two
 * defects that here are decisive:
 *
 *  1. **`GET_SIGNING_CERTIFICATES` exists from API 28.** The project's `minSdk` is 26. On API 26 and
 *     27 that flag does nothing, and a reader built on it would always return an absent signer: steps
 *     3 and 5 of the pipeline would become **silent** no-ops — verification looks performed and
 *     verifies nothing.
 *  2. **`getPackageArchiveInfo` does not verify.** It collects the declared certificates; it does not
 *     prove the archive corresponds to those signatures. `ApkVerifier.verify()` does: it checks every
 *     entry's digests, the v1/v2/v3 schemes, and their mutual consistency.
 *
 * `apksig` solves both, behaves identically on every API level because it does not depend on one,
 * and — not least — makes it possible to test the pipeline on the JVM with real APKs instead of on an
 * emulator.
 *
 * One limit remains to be known: verifying the signature says **who** produced that file, not that
 * the content is benign. For sources redistributing modified APKs there is no original developer's
 * signature to compare against: the pipeline protects against package substitution, not against
 * tampering upstream.
 */
@Singleton
class ApksigApkArchiveReader @Inject constructor(
    /** The API level the APK will run on: it decides which signature schemes suffice. */
    private val minCheckedPlatformVersion: Int = DEFAULT_MIN_SDK,
) : ApkArchiveReader {

    override fun read(file: File): ApkReadResult = try {
        readOrThrow(file)
    } catch (e: Exception) {
        // apksig throws quite a few different exceptions (ApkFormatException, ZipFormatException,
        // IOException, IllegalArgumentException on empty files). Telling them apart would not change
        // what can be done: in every case the file is not installable.
        ApkReadResult.Unreadable(e.message ?: e::class.java.simpleName)
    }

    private fun readOrThrow(file: File): ApkReadResult {
        if (!file.isFile || file.length() == 0L) {
            return ApkReadResult.Unreadable("the file does not exist or is empty")
        }

        val verification = ApkVerifier.Builder(file)
            .setMinCheckedPlatformVersion(minCheckedPlatformVersion)
            .build()
            .verify()

        if (!verification.isVerified) {
            val reason = verification.errors.joinToString(separator = "; ").ifEmpty {
                "signature missing or invalid"
            }
            return ApkReadResult.NotVerified(reason)
        }

        val certificates = verification.signerCertificates
        if (certificates.isEmpty()) return ApkReadResult.NotVerified("no signer certificate")

        val manifest = RandomAccessFile(file, "r").use { raf ->
            ApkUtils.getAndroidManifest(DataSources.asDataSource(raf))
        }
        // The manifest's ByteBuffer is consumed on each read: each one starts from its own slice.
        val packageName = ApkUtils.getPackageNameFromBinaryAndroidManifest(manifest.slice())
        val versionCode = ApkUtils.getLongVersionCodeFromBinaryAndroidManifest(manifest.slice())
        val minSdk = runCatching {
            ApkUtils.getMinSdkVersionFromBinaryAndroidManifest(manifest.slice())
        }.getOrDefault(1)

        return ApkReadResult.Readable(
            ApkArchiveInfo(
                packageName = packageName,
                versionCode = versionCode,
                minSdk = minSdk,
                signerSha256 = certificates.map { Sha256.ofBytes(sha256(it.encoded)) },
                signerLineageSha256 = verification.signingCertificateLineage
                    ?.certificatesInLineage
                    ?.map { Sha256.ofBytes(sha256(it.encoded)) }
                    .orEmpty(),
                signatureSchemes = buildSet {
                    if (verification.isVerifiedUsingV1Scheme) add(1)
                    if (verification.isVerifiedUsingV2Scheme) add(2)
                    if (verification.isVerifiedUsingV3Scheme) add(3)
                    if (verification.isVerifiedUsingV31Scheme) add(31)
                },
                fileSha256 = Sha256.ofBytes(digestOf(file)),
                sizeBytes = file.length(),
            ),
        )
    }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun digestOf(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { stream ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest()
    }

    companion object {
        /** It has to stay aligned with the project's `minSdk`. */
        const val DEFAULT_MIN_SDK: Int = 26
        private const val BUFFER_BYTES = 64 * 1024
    }
}

package com.multistore.core.installer.verify

import com.multistore.core.model.InstalledPackage
import com.multistore.core.model.Sha256
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The pre-install verification pipeline: **identical for all nine stores**.
 *
 * No privileged path, no per-store exception. The way to make that true is not discipline but
 * structure: this class does not know which store the file came from, and there is no parameter with
 * which to tell it.
 *
 * The order of the checks is the planned one, with two additions that turned out to be necessary:
 *
 *  1. **anti-downgrade** (step 6). The `versionCode` was extracted and then compared with nothing.
 *     Installing a version older than the present one is the classic way of reintroducing an
 *     already-fixed vulnerability, and the operating system would refuse it anyway: catching it
 *     beforehand allows explaining it instead of showing an opaque error.
 *  2. **expected signer even when the package is not installed** (step 5b). The original plan
 *     compared the signature only with the installed one; but when the app is **not** there, the
 *     right comparison is with the signer the store declared. Without it, the first installation —
 *     the only one that establishes which signature chain we tie ourselves to — would be the only
 *     unverified one.
 */
@Singleton
class PreInstallVerifier @Inject constructor(
    private val reader: ApkArchiveReader,
) {

    /**
     * What we know about the file **before** opening it, as declared by the store.
     *
     * [declaredPackageName] can be `null`: four stores out of nine do not publish it. In that case
     * the anti-package-swap check is impossible, and that has to be **said** — see
     * [VerificationOutcome.Ok.packageNameWasVerified] — not silently skipped.
     */
    data class Expectation(
        val declaredPackageName: String?,
        val expectedSha256: Sha256? = null,
        val expectedSizeBytes: Long? = null,
        /** The signer the store recommends. Used when the package is not installed. */
        val expectedSignerSha256: Sha256? = null,
        /** What is on the device now, read from the PackageManager and not from our database. */
        val installed: InstalledPackage? = null,
        val deviceSdkInt: Int,
        /** The user has explicitly agreed to install an older version. */
        val allowDowngrade: Boolean = false,
        /**
         * The `allow_unverified_hash` user setting: a mismatch between published hash and real hash
         * does not block.
         *
         * It does not mean "do not check": the comparison happens anyway and the result ends up in
         * [VerificationOutcome.Ok.hashWasVerified]. Skipping it entirely would make "the hash does
         * not match" indistinguishable from "the store does not publish one", which are two very
         * different things to whoever is reading the listing.
         */
        val allowHashMismatch: Boolean = false,
        /**
         * The `allow_signer_mismatch` user setting.
         *
         * These two fields' defaults are the safe values, and they coincide with proto3's zero value
         * — which is why the fields are called `allow_` and not `verify_` (see `SecuritySettings`).
         * The `packageName` match, by contrast, has no field here and must not have one: it is the
         * pipeline's only non-negotiable check.
         */
        val allowSignerMismatch: Boolean = false,
    )

    /**
     * The files verification works on.
     *
     * They are **three roles and not three files**: when the store delivers an APK, [delivered] and
     * [base] are the same object and [splits] is empty. When it delivers a container, [delivered] is
     * the container — the only thing on which comparing the size and the hash the store published
     * makes sense — while [base] and [splits] are what came out of it, and signature, package and
     * version are verified on those.
     *
     * Keeping them separate is what prevents the easy mistake: comparing the store's hash with the
     * **extracted** APK's digest, which never matches, and concluding from that that the store
     * publishes wrong hashes.
     */
    data class Payload(
        val delivered: File,
        val base: File,
        val splits: List<File> = emptyList(),
    ) {
        companion object {
            /** The normal case: the delivered file is already the APK to install. */
            fun of(apk: File): Payload = Payload(delivered = apk, base = apk)
        }
    }

    sealed interface VerificationOutcome {

        data class Ok(
            val info: ApkArchiveInfo,
            /**
             * `false` when the store does not declare the packageName and the comparison could not
             * be made. It is not a detail to hide: it is the difference between "verified" and "not
             * contradicted".
             */
            val packageNameWasVerified: Boolean,
            val signerWasVerified: Boolean,
            /**
             * `false` both when the store publishes no hash and when it publishes one that does not
             * match and the user has chosen not to be blocked. In both cases what can be said of the
             * APK is "not contradicted", not "verified".
             */
            val hashWasVerified: Boolean = false,
            /** What was read from the splits, empty when there is no container. */
            val splits: List<ApkArchiveInfo> = emptyList(),
        ) : VerificationOutcome

        data class Unreadable(val reason: String) : VerificationOutcome

        data class NotSigned(val reason: String) : VerificationOutcome

        data class SizeMismatch(val expected: Long, val actual: Long) : VerificationOutcome

        data class HashMismatch(val expected: Sha256, val actual: Sha256) : VerificationOutcome

        /** Hard block, not configurable: the file is not the app the user asked for. */
        data class PackageNameMismatch(val declared: String, val actual: String) : VerificationOutcome

        /** The package is installed with another signature: uninstalling and losing data is needed. */
        data class SignerMismatchWithInstalled(
            val installed: Sha256?,
            val actual: List<Sha256>,
        ) : VerificationOutcome

        /** The signature is not the one the store had declared. */
        data class UnexpectedSigner(val expected: Sha256, val actual: List<Sha256>) : VerificationOutcome

        data class Downgrade(val installedVersionCode: Long, val offeredVersionCode: Long) : VerificationOutcome

        data class Incompatible(val minSdk: Int, val deviceSdkInt: Int) : VerificationOutcome

        /**
         * A split of the container does not belong to the same app as the base.
         *
         * It is the check that makes harmless the fact that the names inside a container are written
         * by the store: a `config.arm64_v8a.apk` that is really another app's APK would enter the
         * same session as the base, and `PackageInstaller` would refuse it with an error that does
         * not say which entry is the problem. Here instead we know **which** and **why**.
         *
         * It holds for the signature too: a split signed by another key is a piece of app that was
         * not produced by whoever produced the base.
         */
        data class ForeignSplit(val name: String, val reason: String) : VerificationOutcome
    }

    /** The normal case, and the only one there used to be: one file, which is already the APK to install. */
    fun verify(file: File, expectation: Expectation): VerificationOutcome =
        verify(Payload.of(file), expectation)

    fun verify(payload: Payload, expectation: Expectation): VerificationOutcome {
        // 1 — size, **of the delivered file**. The cheapest check goes first: if the file is
        // truncated there is no point computing its digest.
        val actualSize = payload.delivered.length()
        expectation.expectedSizeBytes?.let { expected ->
            if (expected != actualSize) return VerificationOutcome.SizeMismatch(expected, actualSize)
        }

        // 2/3 — hash and reading the archive. `apksig` also verifies internal integrity: an APK
        // whose content does not correspond to the signatures never reaches the following checks.
        val info = when (val read = reader.read(payload.base)) {
            is ApkReadResult.Readable -> read.info
            is ApkReadResult.NotVerified -> return VerificationOutcome.NotSigned(read.reason)
            is ApkReadResult.Unreadable -> return VerificationOutcome.Unreadable(read.reason)
        }

        // The hash the store declared concerns **what the store delivered**. With a container that
        // is not the base but the container itself, and comparing it with the base's digest would
        // give a mismatch on every single installation.
        val deliveredSha256 = if (payload.delivered == payload.base) {
            info.fileSha256
        } else {
            digestOf(payload.delivered) ?: return VerificationOutcome.Unreadable(UNREADABLE_CONTAINER)
        }

        var hashVerified = false
        expectation.expectedSha256?.let { expected ->
            if (expected != deliveredSha256) {
                if (!expectation.allowHashMismatch) {
                    return VerificationOutcome.HashMismatch(expected, deliveredSha256)
                }
            } else {
                hashVerified = true
            }
        }

        // 4 — the packageName must match the listing's. Hard block.
        var packageNameVerified = false
        expectation.declaredPackageName?.let { declared ->
            if (declared != info.packageName) {
                return VerificationOutcome.PackageNameMismatch(declared, info.packageName)
            }
            packageNameVerified = true
        }

        // 5 — the signature.
        var signerVerified = false
        val installed = expectation.installed
        if (installed != null) {
            val installedSigner = installed.signerSha256
            // An APK can have several signers, and with schema v3 it can declare the chain of keys
            // preceding it: it is enough for the installed one to appear in either list. Excluding
            // the chain would make "different signature" of every key rotation — an update Android
            // would accept, and for which we would be asking the user to lose the app's data.
            val accepted = info.signerSha256 + info.signerLineageSha256
            if (installedSigner != null && installedSigner !in accepted) {
                if (!expectation.allowSignerMismatch) {
                    return VerificationOutcome.SignerMismatchWithInstalled(installedSigner, info.signerSha256)
                }
            } else if (installedSigner != null) {
                signerVerified = true
            }
        } else {
            expectation.expectedSignerSha256?.let { expected ->
                if (expected !in info.signerSha256) {
                    if (!expectation.allowSignerMismatch) {
                        return VerificationOutcome.UnexpectedSigner(expected, info.signerSha256)
                    }
                } else {
                    signerVerified = true
                }
            }
        }

        // 6 — anti-downgrade.
        if (installed != null && !expectation.allowDowngrade &&
            info.versionCode < installed.versionCode
        ) {
            return VerificationOutcome.Downgrade(installed.versionCode, info.versionCode)
        }

        // 7 — compatibility with the device. Last because it is the only one the user cannot solve
        // in any way: telling them after ruling out the solvable problems is more useful than
        // stopping straight away.
        if (info.minSdk > expectation.deviceSdkInt) {
            return VerificationOutcome.Incompatible(info.minSdk, expectation.deviceSdkInt)
        }

        // 8 — the splits, if there are any. Each has to be a signed APK belonging to the **same**
        // app as the base: same package, same version, same signers. It is the step that makes
        // harmless the fact that the names inside a container are written by the store.
        val splits = mutableListOf<ApkArchiveInfo>()
        for (split in payload.splits) {
            val name = split.name
            val splitInfo = when (val read = reader.read(split)) {
                is ApkReadResult.Readable -> read.info
                is ApkReadResult.NotVerified -> return VerificationOutcome.ForeignSplit(name, read.reason)
                is ApkReadResult.Unreadable -> return VerificationOutcome.ForeignSplit(name, read.reason)
            }
            when {
                splitInfo.packageName != info.packageName ->
                    return VerificationOutcome.ForeignSplit(name, otherPackage(splitInfo.packageName))

                splitInfo.versionCode != info.versionCode ->
                    return VerificationOutcome.ForeignSplit(name, otherVersion(splitInfo.versionCode))

                // Sets and not lists: the signers' order is not guaranteed, and two APKs signed by
                // the same two keys in a different order are the same thing.
                splitInfo.signerSha256.toSet() != info.signerSha256.toSet() ->
                    return VerificationOutcome.ForeignSplit(name, OTHER_SIGNER)
            }
            splits += splitInfo
        }

        return VerificationOutcome.Ok(
            info = info,
            packageNameWasVerified = packageNameVerified,
            signerWasVerified = signerVerified,
            hashWasVerified = hashVerified,
            splits = splits,
        )
    }

    private fun digestOf(file: File): Sha256? = runCatching {
        val digest = MessageDigest.getInstance(SHA_256)
        file.inputStream().buffered().use { stream ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        Sha256.ofBytes(digest.digest())
    }.getOrNull()

    private companion object {
        const val SHA_256 = "SHA-256"
        const val BUFFER_BYTES = 64 * 1024
        const val UNREADABLE_CONTAINER = "the container could not be re-read"
        const val OTHER_SIGNER = "signed by a different key"

        fun otherPackage(actual: String) = "belongs to package $actual"
        fun otherVersion(actual: Long) = "it is version $actual"
    }
}

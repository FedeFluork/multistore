package com.multistore.core.installer.verify

import com.multistore.core.model.Sha256

/**
 * What is read from an APK file, before installing it.
 *
 * Every field is **non-nullable**: a field that could not be read does not become `null` but makes
 * the whole read fail, with [ApkReadResult.Unreadable]. The difference is not stylistic — a null
 * `signerSha256` that the pipeline interprets as "no check possible, carry on" is a signature-check
 * bypass dressed as tolerance.
 */
data class ApkArchiveInfo(
    val packageName: String,
    val versionCode: Long,
    val minSdk: Int,
    /**
     * The signers, plural.
     *
     * An APK can be signed by several keys, and schema v3 allows **rotation**: the chain declares
     * that a new key succeeds an old one. Modelling it as a single value would work for nearly all
     * cases and would get wrong exactly those where the signature is changing, i.e. when the check
     * matters most.
     */
    val signerSha256: List<Sha256>,
    /**
     * The key **lineage** declared by schema v3, if there is one.
     *
     * It serves not to block an update the operating system would accept. With rotation, the
     * installed app is signed by the old key and the update by the new one: comparing only
     * [signerSha256] would give "different signature" and would offer the user to uninstall and lose
     * their data for a perfectly legitimate update. The chain is the proof that the new key succeeds
     * the old one, and it is exactly what Android verifies.
     *
     * Empty where there is no rotation, which is the normal case.
     */
    val signerLineageSha256: List<Sha256> = emptyList(),
    /** The schemes the signature was verified with: v1 (JAR), v2, v3, v3.1. */
    val signatureSchemes: Set<Int>,
    val fileSha256: Sha256,
    val sizeBytes: Long,
)

sealed interface ApkReadResult {

    data class Readable(val info: ApkArchiveInfo) : ApkReadResult

    /**
     * The file is an APK but its signature does not hold.
     *
     * It is distinct from [Unreadable] because it says something different to the user: not "the
     * download is corrupt" but "this file is not what the signer produced".
     */
    data class NotVerified(val reason: String) : ApkReadResult

    /** Not a readable APK: corrupt zip, unreadable manifest, truncated file. */
    data class Unreadable(val reason: String) : ApkReadResult
}

/**
 * Whoever knows how to read an APK.
 *
 * It is an interface because the verification pipeline has to be testable without touching real
 * files for every combination of outcomes — but the real implementation, the one deciding whether an
 * installation starts, is a single one.
 */
interface ApkArchiveReader {
    fun read(file: java.io.File): ApkReadResult
}

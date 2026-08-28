package com.multistore.core.model

/**
 * A SHA-256 digest, normalised to 64 lowercase hexadecimal characters.
 *
 * A type rather than a `String` for a measured reason: F-Droid publishes digests in lowercase,
 * `apksigner` prints them in lowercase, the pinned repository fingerprint is written in
 * uppercase, and `MessageDigest` returns bytes. Comparing strings of different provenance is a
 * bug that stays invisible until it blocks a legitimate installation — or, worse, lets one
 * through that should have been blocked.
 *
 * The constructor is private: the only ways in are [parseOrNull] and [ofBytes], which normalise.
 */
@JvmInline
value class Sha256 private constructor(val hex: String) {

    override fun toString(): String = hex

    /** The first 12 characters, for logs and diagnostics. Never for comparisons. */
    fun abbreviated(): String = hex.take(ABBREVIATED_LENGTH)

    companion object {
        const val HEX_LENGTH: Int = 64
        private const val ABBREVIATED_LENGTH = 12
        private val HEX_ONLY = Regex("[0-9a-f]{$HEX_LENGTH}")

        /** `null` if the string is not a valid hexadecimal SHA-256. Never throws. */
        fun parseOrNull(raw: String?): Sha256? {
            val normalized = raw?.trim()?.lowercase()?.replace(":", "") ?: return null
            return if (HEX_ONLY.matches(normalized)) Sha256(normalized) else null
        }

        fun ofBytes(digest: ByteArray): Sha256 {
            require(digest.size == HEX_LENGTH / 2) {
                "A SHA-256 digest is ${HEX_LENGTH / 2} bytes long, not ${digest.size}"
            }
            val sb = StringBuilder(HEX_LENGTH)
            for (b in digest) {
                val v = b.toInt() and 0xFF
                sb.append(HEX_DIGITS[v ushr 4]).append(HEX_DIGITS[v and 0x0F])
            }
            return Sha256(sb.toString())
        }

        private const val HEX_DIGITS = "0123456789abcdef"
    }
}

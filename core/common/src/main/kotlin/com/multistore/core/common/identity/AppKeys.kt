package com.multistore.core.common.identity

import com.multistore.core.common.text.TextNormalizer
import java.security.MessageDigest

/**
 * The key by which several stores talk about the **same** app.
 *
 * Where the store publishes the `packageName` the key is that, and the match is exact. Where it
 * does not — four stores out of nine, an1 across its whole site — what remains is the normalised
 * title and the developer, reduced to a digest so the key is not long and fragile.
 *
 * The prefix tells the two cases apart at a glance in a query, and above all stops a
 * `packageName` and a digest from ever colliding: they are different namespaces and must stay so.
 *
 * It lives in `:core:common` rather than in `:core:data`'s mapper because it is more than the way
 * a row is written: it is the identity [IdentityMatcher] groups search results on, i.e. a domain
 * rule that belongs where it can be tested without Robolectric.
 */
object AppKeys {

    private const val EXACT_PREFIX = "pkg:"
    private const val INFERRED_PREFIX = "sig:"
    private const val DIGEST_CHARS = 24
    private const val HEX = "0123456789abcdef"

    fun forPackage(packageName: String): String = EXACT_PREFIX + packageName

    fun inferred(title: String, developer: String?): String {
        val seed = TextNormalizer.titleKey(title) + " " + TextNormalizer.titleKey(developer.orEmpty())
        val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
        val hex = buildString {
            for (byte in digest) {
                val value = byte.toInt() and 0xFF
                append(HEX[value ushr 4])
                append(HEX[value and 0x0F])
            }
        }
        return INFERRED_PREFIX + hex.take(DIGEST_CHARS)
    }

    fun of(packageName: String?, title: String, developer: String?): String =
        packageName?.takeIf { it.isNotBlank() }?.let(::forPackage) ?: inferred(title, developer)

    /** `true` if the key comes from a `packageName` rather than from an inference. */
    fun isExact(appKey: String): Boolean = appKey.startsWith(EXACT_PREFIX)

    fun packageNameOrNull(appKey: String): String? =
        if (isExact(appKey)) appKey.removePrefix(EXACT_PREFIX) else null
}

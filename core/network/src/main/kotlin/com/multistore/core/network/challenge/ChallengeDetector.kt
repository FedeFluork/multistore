package com.multistore.core.network.challenge

import com.multistore.core.model.BlockKind
import okhttp3.Response

/**
 * Recognises a response that is a block rather than content.
 *
 * Needed because a challenge almost never arrives with an honest status code: Cloudflare returns
 * **403 or 503 with an HTML page** that looks like a valid document to a naive client. Without
 * this recognition an adapter would try to extract selectors from the challenge page and produce
 * a `ParseFailure`, i.e. the wrong diagnosis: someone would go looking for a markup change where
 * what is needed is climbing one rung.
 *
 * Body inspection is limited to the first bytes and **does not consume** the response: it uses
 * `peekBody`, which leaves the stream readable for whoever comes next.
 */
object ChallengeDetector {

    /** How many bytes of the body to look at. Challenge pages announce themselves at the top. */
    const val PEEK_BYTES: Long = 8 * 1024

    private val CHALLENGE_MARKERS = listOf(
        "__cf_chl",
        "cf-browser-verification",
        "cf_chl_opt",
        "just a moment",
        "checking your browser",
        "enable javascript and cookies to continue",
        "ddos-guard",
    )

    private val CAPTCHA_MARKERS = listOf(
        "g-recaptcha",
        "grecaptcha",
        "h-captcha",
        "cf-turnstile",
        "challenges.cloudflare.com/turnstile",
    )

    /** `null` if the response is real content. */
    fun classify(response: Response): BlockKind? {
        // Cloudflare explicitly marks the responses it blocked.
        if (response.header("cf-mitigated")?.equals("challenge", ignoreCase = true) == true) {
            return BlockKind.CHALLENGE
        }
        if (response.isSuccessful) return classifyBody(response, onlyCaptcha = true)
        return when (response.code) {
            HTTP_FORBIDDEN, HTTP_UNAVAILABLE -> classifyBody(response, onlyCaptcha = false)
                ?: if (response.code == HTTP_FORBIDDEN) BlockKind.FORBIDDEN else null
            HTTP_UNAVAILABLE_LEGAL -> BlockKind.GEO
            else -> null
        }
    }

    /**
     * @param onlyCaptcha on a 200 only the captcha is looked for: a page that *contains* the
     * words "checking your browser" but answers 200 is almost always an article about it, not a
     * block.
     */
    private fun classifyBody(response: Response, onlyCaptcha: Boolean): BlockKind? {
        val body = runCatching { response.peekBody(PEEK_BYTES).string().lowercase() }.getOrNull()
            ?: return null
        if (CAPTCHA_MARKERS.any { it in body }) return BlockKind.CAPTCHA
        if (!onlyCaptcha && CHALLENGE_MARKERS.any { it in body }) return BlockKind.CHALLENGE
        return null
    }

    private const val HTTP_FORBIDDEN = 403
    private const val HTTP_UNAVAILABLE = 503
    private const val HTTP_UNAVAILABLE_LEGAL = 451
}

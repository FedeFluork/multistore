package com.multistore.core.common.result

import kotlin.time.Duration

/**
 * The errors the application knows how to tell the user about.
 *
 * Not a duplicate of `StoreError`: that one describes what went wrong *inside an adapter* and
 * lives in `:store:api`, which `:core:common` cannot see. This is the app's vocabulary, into
 * which `:core:data` translates the errors of every source.
 *
 * Each case carries what is needed to decide *what to do*, not only what to write: a
 * [RateLimited] with `retryAfter` allows rescheduling, a [Blocked] does not.
 */
sealed interface AppError {

    /** No network, DNS, timeout, TLS. Retryable. */
    data class Network(val cause: Throwable?) : AppError

    /** The store answered "too many requests". */
    data class RateLimited(val retryAfter: Duration?) : AppError

    /** The store bars the way: challenge, captcha, geo-block, 403. */
    data class Blocked(val reason: String?) : AppError

    /** The response arrived but is not the expected one: changed markup, malformed JSON. */
    data class Parse(val what: String, val detail: String? = null) : AppError

    /** The content is not (or no longer) there. */
    data object NotFound : AppError

    /** An integrity check failed: hash, signature, packageName. Never retryable. */
    data class IntegrityFailed(val what: String) : AppError

    /** Not enough space, unwritable file, missing system permission. */
    data class Storage(val cause: Throwable?) : AppError

    /** Installation failed, with the status code `PackageInstaller` returned. */
    data class InstallFailed(val statusCode: Int?, val systemMessage: String?) : AppError

    /** The user cancelled. Not a failure: the UI must not show it as an error. */
    data object Cancelled : AppError

    /**
     * The only route to that file goes through a tap on the store's page, and the user has turned
     * that path off in Settings.
     *
     * A case of its own rather than a [Blocked], because the two sentences send the user to two
     * different places: "the store bars the way" is not solvable anywhere, this is solvable with
     * a switch. It is also the only way `block_user_assisted_challenge` gets to **announce
     * itself**: a setting that makes a button vanish without explanation looks too much like a
     * fault.
     */
    data object UserAssistanceDisabled : AppError

    /** Everything else. If this case shows up often, a case above is missing. */
    data class Unexpected(val cause: Throwable?) : AppError
}

package com.multistore.core.data.mapper

import com.multistore.core.common.net.FailureKind
import com.multistore.core.common.result.AppError
import com.multistore.store.api.StoreError

/**
 * The translation between the adapters' vocabulary and the application's.
 *
 * The two exist separately for a reason of scope: `StoreError` lives in `:store:api`, which
 * `:core:common` cannot see. `:core:data` is the only module that sees both, so the translation lives
 * here — and it is the only place it lives, instead of being repeated in every repository with
 * slightly different shades.
 */
fun StoreError.toAppError(): AppError = when (this) {
    is StoreError.Network -> AppError.Network(cause)
    is StoreError.RateLimited -> AppError.RateLimited(retryAfter)
    is StoreError.Blocked -> AppError.Blocked(kind.name)
    is StoreError.ParseFailure -> AppError.Parse(selector, snippetHash)
    StoreError.NotFound -> AppError.NotFound
    is StoreError.Unsupported -> AppError.Unexpected(null)
    is StoreError.Unexpected -> AppError.Unexpected(cause)
}

/**
 * How the circuit breaker should react to this error.
 *
 * The distinction that matters is between "retry later" and "stop": a 429 or a block open it
 * immediately, because insisting makes things worse; a timeout counts in the window and opens only if
 * it repeats. A `NotFound` is not a store fault and must count in neither.
 */
fun StoreError.toFailureKind(): FailureKind = when (this) {
    is StoreError.Network -> FailureKind.TRANSIENT
    is StoreError.RateLimited -> FailureKind.RATE_LIMITED
    is StoreError.Blocked -> FailureKind.BLOCKED
    is StoreError.ParseFailure -> FailureKind.PARSE
    StoreError.NotFound -> FailureKind.NOT_FOUND
    // "Unsupported" is a correct answer to a wrong question: the store is fine.
    is StoreError.Unsupported -> FailureKind.NOT_FOUND
    is StoreError.Unexpected -> FailureKind.TRANSIENT
}

/** The selector to record for a parse failure, if there is one. */
fun StoreError.parseSelector(): String? = (this as? StoreError.ParseFailure)?.selector

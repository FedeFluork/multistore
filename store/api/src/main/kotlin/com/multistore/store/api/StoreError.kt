package com.multistore.store.api

import com.multistore.core.model.BlockKind
import kotlin.time.Duration

/**
 * What went wrong inside an adapter.
 *
 * Errors are returned as `StoreError`, never thrown: no exception may leave a `StoreAdapter`
 * method. The reason is not stylistic — with nine stores queried in parallel, an exception
 * escaping one cancels the scope and takes the other eight with it. A returned error is a partial
 * result; an exception is an empty search.
 */
sealed interface StoreError {

    /** No network, DNS, timeout, TLS, 5xx. */
    data class Network(val cause: Throwable?, val httpCode: Int? = null) : StoreError

    /** The store answered "too many requests". [retryAfter] comes from the header, if there was one. */
    data class RateLimited(val retryAfter: Duration?) : StoreError

    /** The store bars the way. */
    data class Blocked(val kind: BlockKind) : StoreError

    /**
     * The document arrived but does not contain what was expected.
     *
     * A failed parse produces a `ParseFailure`, never a disguised `NullPointerException` or a
     * silently empty field. The two fields exist to decide: [selector] says *what* changed in the
     * markup, [snippetHash] makes it possible to recognise the same page recurring without keeping
     * its content.
     */
    data class ParseFailure(val selector: String, val snippetHash: String) : StoreError

    /** The app or version does not (or no longer) exist on this store. */
    data object NotFound : StoreError

    /** The adapter does not support the requested operation with these parameters. */
    data class Unsupported(val what: String) : StoreError

    /** Everything else. If it shows up often, a case above is missing. */
    data class Unexpected(val cause: Throwable?) : StoreError
}

package com.multistore.store.api

/**
 * The outcome of a call to a store.
 *
 * Three cases and not two, because "unsupported" is not an error: it is the absence of a
 * capability, and the UI must react by hiding a tab, not by showing a red banner. It is the
 * default value of the interface's optional methods, so an adapter that does not implement
 * version history need write nothing.
 */
sealed interface StoreResult<out T> {

    data class Success<out T>(val value: T) : StoreResult<T>

    data class Failure(val error: StoreError) : StoreResult<Nothing>

    /** The adapter does not offer this operation. Consistent with [StoreCapabilities]. */
    data object Unsupported : StoreResult<Nothing>

    fun getOrNull(): T? = (this as? Success)?.value

    fun errorOrNull(): StoreError? = (this as? Failure)?.error
}

inline fun <T, R> StoreResult<T>.map(transform: (T) -> R): StoreResult<R> = when (this) {
    is StoreResult.Success -> StoreResult.Success(transform(value))
    is StoreResult.Failure -> this
    StoreResult.Unsupported -> StoreResult.Unsupported
}

fun <T> T.asStoreSuccess(): StoreResult<T> = StoreResult.Success(this)

fun StoreError.asStoreFailure(): StoreResult<Nothing> = StoreResult.Failure(this)

/** A page of results, with the little needed to know whether to ask for another. */
data class PagedResult<out T>(
    val items: List<T>,
    val page: Int,
    val hasMore: Boolean,
    /** `null` where the store does not declare it, which is the normal case among the nine. */
    val totalCount: Int? = null,
) {
    companion object {
        fun <T> single(items: List<T>): PagedResult<T> =
            PagedResult(items = items, page = 0, hasMore = false, totalCount = items.size)

        fun <T> empty(page: Int = 0): PagedResult<T> =
            PagedResult(items = emptyList(), page = page, hasMore = false, totalCount = 0)
    }
}

package com.multistore.core.testing

import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData

/**
 * A **finished** page: all the rows there are, and Paging knows it.
 *
 * ### Why `PagingData.from(rows)` is not enough
 *
 * With the single-argument form `asSnapshot()` **never returns**: the test hangs until `runTest`'s
 * timeout and fails with `UncompletedCoroutinesError`, accusing the wrong code of being slow. It
 * happened on the first draft of `StoreListingViewModel`'s tests — five out of six red, and not one
 * line naming Paging.
 *
 * ### What bears the weight, measured
 *
 * What unblocks `asSnapshot()` is **declaring `sourceLoadStates`**, not the value put inside. Proven
 * by injection on 26/08/2026:
 *
 * | form | outcome |
 * |---|---|
 * | `PagingData.from(items)` | the tests hang |
 * | `sourceLoadStates` with `endOfPaginationReached = false` | the tests pass |
 * | `sourceLoadStates` with `endOfPaginationReached = true` | the tests pass |
 *
 * The value stays `true` because it is the **true** one — a static list is complete, and calling it
 * `false` would promise pages that will not arrive — but it has to be written down that it is not
 * what makes the tests work: if one day it were changed by mistake, nothing would turn red.
 */
fun <T : Any> completePage(items: List<T>): PagingData<T> = PagingData.from(
    data = items,
    sourceLoadStates = LoadStates(
        refresh = LoadState.NotLoading(endOfPaginationReached = true),
        prepend = LoadState.NotLoading(endOfPaginationReached = true),
        append = LoadState.NotLoading(endOfPaginationReached = true),
    ),
)

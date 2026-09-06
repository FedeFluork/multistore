package com.multistore.core.model

/**
 * What "My apps" remembers between two visits.
 *
 * ### Why the sort is remembered and the search text is not
 *
 * They look like the same kind of state and are not. An order is a **standing preference** — "I want
 * this list alphabetical" is true tomorrow as well — and one that reset on every visit would be a
 * choice the user re-makes every time, which is the definition of an interface that does not learn.
 * A search box is the opposite: it narrows the list to answer one question, and coming back to the
 * screen with somebody's earlier query still filtering it would hide apps that are installed with no
 * visible reason why.
 *
 * That is why exactly one of the two is a field in `settings.proto` with its entry in Settings, and
 * the other is transient state in the ViewModel — the same split the search screen already makes
 * between `default_sort` and the filters of one search.
 */
data class MyAppsSettings(
    /**
     * [MyAppsSort.NAME] is also the proto zero value: it is the order that does not rearrange
     * itself while the list is being read.
     */
    val sort: MyAppsSort = MyAppsSort.NAME,
)

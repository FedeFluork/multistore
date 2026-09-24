package com.multistore.core.data.store

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A search one screen asked for and another performs.
 *
 * ### Why this is not a navigation argument
 *
 * The Search destination is a **top-level** one: the bottom bar reaches it with `popUpTo(start) {
 * saveState }` plus `restoreState`, so the tab keeps the state it had. Giving the route an argument
 * would mean two different entries for one tab — the bar's and the one carrying a query — and the
 * user tapping Search after a developer search would land on whichever of the two the back stack
 * happened to restore. The argument would be compiler-checked and the behaviour would be wrong.
 *
 * So the intent travels beside navigation, in the shape this module already uses for exactly this
 * kind of hand-off (see `SearchGroupMemory`): one screen leaves a request, the shell switches tab,
 * and the search picks it up.
 *
 * ### It is consumed, once
 *
 * [take] clears it. Without that, returning to the Search tab a week later would re-run a search
 * somebody asked for once — and worse, would overwrite whatever they had typed since. A request is
 * an **event**, and an event kept in state is an event that happens again.
 */
@Singleton
class PendingSearch @Inject constructor() {

    private val state = MutableStateFlow<Request?>(null)

    /**
     * Observed rather than read once, because the request is left **before** the destination exists.
     *
     * The listing writes it and the shell then switches tab; the search's ViewModel is built during
     * that switch. Whichever order those two happen in, a flow delivers.
     */
    val requests: StateFlow<Request?> = state.asStateFlow()

    fun request(request: Request) {
        state.value = request
    }

    /** Takes the request and clears it. Returns `null` if there was none. */
    fun take(): Request? = state.getAndUpdate { null }

    /**
     * What to search for, and **how it should be read**.
     *
     * [byDeveloper] is not a detail of the query: eight stores of nine cannot search by publisher and
     * will receive the name as ordinary text, so their results are whatever contains that string. A
     * namesake is not the same person, and the screen has to be able to say so — which it can only do
     * if it knows the search was meant that way.
     */
    data class Request(val query: String, val byDeveloper: Boolean = false)
}

private fun <T> MutableStateFlow<T>.getAndUpdate(transform: (T) -> T): T {
    while (true) {
        val current = value
        if (compareAndSet(current, transform(current))) return current
    }
}

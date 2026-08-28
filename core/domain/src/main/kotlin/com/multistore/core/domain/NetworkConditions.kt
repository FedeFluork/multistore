package com.multistore.core.domain

/**
 * What the domain needs to know about the network to decide whether to start by itself.
 *
 * It is an interface and not a direct `ConnectivityManager` read for the usual reason: the rule
 * "automatic if the network is not metered, with confirmation if it is" has to be tested in both
 * cases, and on the JVM.
 */
fun interface NetworkConditions {
    /** `true` if the active connection is metered (mobile data, hotspot). */
    suspend fun isMetered(): Boolean
}

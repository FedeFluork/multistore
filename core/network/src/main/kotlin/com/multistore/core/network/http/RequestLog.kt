package com.multistore.core.network.http

import com.multistore.core.model.StoreId
import kotlin.time.Duration

/**
 * Where the trace of a **successful** request ends up.
 *
 * ### Why it exists, given that `health_events` already does
 *
 * That table records what goes wrong, and the reason it does not record the rest is next to
 * `StoreHttpClient.recordTier`: a row per ordinary request would fill diagnostics with the news
 * that nothing happened.
 *
 * The flaw in that choice only shows when someone tries to *use* the diagnostics. The two most
 * common questions — "why is search so slow?", "why does this store find nothing?" — both have
 * the shape where **the failure log is empty**, and empty precisely because nothing failed: the
 * requests went out, answered 200, and took six seconds each. Without rows for the successes,
 * that case is indistinguishable from "the app asked for nothing".
 *
 * Hence the switch rather than a change of default: on it costs one write per request, off the
 * table stays as it always was. It is read by whoever implements this interface, not by
 * `:core:network` — which is pure Kotlin and sees neither Room nor the DataStore.
 *
 * ### The same shape as `ChallengeTierRecorder`, and for the same reason
 *
 * It is implemented by `:core:data`, received by `StoreHttpClients` — which every adapter already
 * gets injected — and called by the store's client, which knows its own [StoreId]. No adapter
 * changes by a line.
 */
fun interface RequestLog {

    /**
     * A request that reached its destination.
     *
     * [elapsed] is the time to the **headers**, not to the last byte of the body: it is the
     * number that answers "the store is slow to respond", whereas the body's depends on how large
     * the object is and on the viewer's bandwidth. For a 57 MB index the two measurements differ
     * by orders of magnitude, and mixing them would make the log useless on exactly the store
     * that needs it most.
     */
    fun record(storeId: StoreId, method: String, url: String, code: Int, elapsed: Duration)

    companion object {
        /** The default: nobody is listening. Used by every JVM test and every build without Room. */
        val NONE: RequestLog = RequestLog { _, _, _, _, _ -> }
    }
}

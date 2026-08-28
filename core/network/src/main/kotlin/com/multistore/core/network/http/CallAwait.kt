package com.multistore.core.network.http

import java.io.IOException
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response

/**
 * Runs a `Call` by suspending, and cancels it if the coroutine is cancelled.
 *
 * OkHttp offers `execute()` (blocks the thread) and `enqueue()` (callback). Neither cooperates
 * with structured cancellation: without this bridge, closing the search screen while nine stores
 * are answering would leave nine requests completing into the void, burning rate limit that the
 * next search will need.
 */
suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation {
        runCatching { cancel() }
    }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isCancelled) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) {
                    continuation.resume(response) { _, value, _ -> value.closeQuietly() }
                } else {
                    response.closeQuietly()
                }
            }
        },
    )
}

internal fun Response.closeQuietly() {
    runCatching { close() }
}

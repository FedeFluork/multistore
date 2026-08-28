package com.multistore.core.network.http

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Imposes the store's User-Agent on every request, redirects included.
 *
 * This is rung 0 of the escalation ladder, and it is not cosmetic: apkmirror answers **403 with
 * 153 bytes** to OkHttp's default UA and **200** to a Chrome mobile UA. That is why
 * `StoreCapabilities.userAgent` is mandatory and the contract test checks it.
 *
 * It is an application interceptor rather than a header on the individual request so it also
 * covers the hops OkHttp follows by itself: forgetting it on a redirect is exactly the kind of
 * bug that shows up on one store in nine.
 */
class UserAgentInterceptor(private val userAgent: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        // An explicit one on the request wins: `ChallengeResolver` uses that to retry under a
        // different identity without rebuilding the client.
        if (request.header(HEADER) != null) return chain.proceed(request)
        return chain.proceed(request.newBuilder().header(HEADER, userAgent).build())
    }

    private companion object {
        const val HEADER = "User-Agent"
    }
}

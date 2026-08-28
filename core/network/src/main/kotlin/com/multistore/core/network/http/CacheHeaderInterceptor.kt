package com.multistore.core.network.http

import kotlin.time.Duration
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Gives an expiry to responses that **declare none**, and only to those.
 *
 * Measured on 24/08/2026 across the stores' search pages, with a Chrome mobile UA:
 *
 * | Store | What it sends | What OkHttp does with it |
 * |---|---|---|
 * | f-droid | `ETag` + `Last-Modified`, no `Cache-Control` | heuristic and revalidation: works |
 * | apkmody | `public, max-age=7200` | two hours of cache: already right |
 * | **apkmirror** | **nothing** — no `Cache-Control`, no `Expires`, no validator | **never cached** |
 * | apkcombo | `no-store, no-cache, must-revalidate, max-age=0` | not cached, **at the site's request** |
 * | uptodown | `private, no-store, no-cache` | not cached, **at the site's request** |
 *
 * ### The line this interceptor does not cross
 *
 * The last two rows resemble the third only in effect. apkmirror **said nothing**; apkcombo and
 * uptodown said "do not store". Filling a silence and contradicting an answer are two different
 * things, and we do not do the second: we behave like a browser, and a browser that ignores
 * `no-store` is not aggressive, it is broken.
 *
 * On the one store that stays silent the practical consequence is concrete: without an override,
 * going back to the same listing twice is two requests to a site that declares `Crawl-delay: 3`
 * and answers 429 to whoever ignores it.
 */
internal class CacheHeaderInterceptor(private val ttl: Duration) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (!response.isSuccessful) return response
        if (response.request.method != "GET") return response

        // **One** of these is enough for the site to have had its say: `Cache-Control` and
        // `Expires` give freshness, `ETag` and `Last-Modified` give something to revalidate
        // with. Only when none of the four is present is the response unusable for the cache,
        // and that is the only case where this interceptor speaks.
        val speaks = SPOKEN_HEADERS.any { response.header(it) != null }
        if (speaks) return response

        return response.newBuilder()
            .header(CACHE_CONTROL, "max-age=${ttl.inWholeSeconds}")
            .build()
    }

    private companion object {
        const val CACHE_CONTROL = "Cache-Control"
        val SPOKEN_HEADERS = listOf(CACHE_CONTROL, "Expires", "ETag", "Last-Modified")
    }
}

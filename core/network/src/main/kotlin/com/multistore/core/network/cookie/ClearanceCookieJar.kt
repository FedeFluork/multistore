package com.multistore.core.network.cookie

import java.util.concurrent.atomic.AtomicLong
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * The stores' cookies, in memory, for the lifetime of the process.
 *
 * `CookieJar.NO_COOKIES` is OkHttp's default, and with that rung 3 would have no reason to exist.
 * A WebView that really **executes** Cloudflare's challenge obtains a `cf_clearance`, and that
 * cookie is the only thing rung 3 produces: with nowhere to put it, the retried OkHttp request
 * would be identical to the one that just got a 403.
 *
 * ### Why memory only
 *
 * A `cf_clearance` is bound to the IP address and User-Agent that obtained it, and is short-lived.
 * Writing it to disk would almost always restore an already-invalid token on the next launch —
 * while keeping, in a file, an identifier the site can use to recognise this device. The cost of
 * not persisting it is one extra WebView load after the process dies.
 *
 * ### Why one jar for nine stores
 *
 * Because cookies are per host **by construction**: [Cookie.matches] compares domain, path and
 * `Secure`, so what apkmirror set cannot end up in a request to uptodown even by accident. Nine
 * separate jars would give the same guarantee in exchange for nine structures to keep aligned.
 */
class ClearanceCookieJar(
    /**
     * The clock, injectable.
     *
     * It serves one purpose, and it cannot be deferred: proving that an **expired** cookie is not
     * presented. With the system clock that test could only be written by really waiting, and a
     * `cf_clearance` lasts half an hour.
     */
    private val now: () -> Long = System::currentTimeMillis,
) : CookieJar {

    private val lock = Any()
    private val stored = LinkedHashMap<Key, Cookie>()

    /**
     * How many times a WebView has delivered cookies for a host.
     *
     * Not a statistic: it is what stops two parallel searches from opening two WebViews for the
     * same challenge. Whoever is about to climb to rung 3 reads the number **before** queueing;
     * when their turn comes, a changed number means someone else already did the work and the
     * request merely needs retrying.
     *
     * A counter and not a clock, because the question is "has anything happened since I looked?",
     * not "how much time has passed": a counter answers exactly that and needs no injected
     * `Clock` to be testable.
     */
    private val harvests = HashMap<String, AtomicLong>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        synchronized(lock) { cookies.forEach(::put) }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(lock) {
        val instant = now()
        val expired = mutableListOf<Key>()
        val matching = mutableListOf<Cookie>()
        stored.forEach { (key, cookie) ->
            when {
                cookie.expiresAt <= instant -> expired += key
                cookie.matches(url) -> matching += cookie
            }
        }
        expired.forEach(stored::remove)
        matching
    }

    /**
     * Transfers into the jar the cookies a WebView obtained by executing the challenge.
     *
     * [cookieHeader] is what `CookieManager.getCookie(url)` returns: a line of `name=value` pairs
     * separated by `; ` and with **no attributes at all** — no `Domain`, no `Path`, no `Expires`.
     * That is not a loss to make up by guessing: those cookies are ours for the host just loaded,
     * and we want them for that host only. They are therefore rebuilt as **host** cookies, path
     * `/`, session-scoped — i.e. lasting as long as this jar does.
     *
     * @return how many cookies were accepted. Zero means the WebView obtained nothing, and the
     * caller must know: retrying the request identical to the one that just got a 403 would only
     * be another refused request.
     */
    fun acceptFromWebView(url: HttpUrl, cookieHeader: String?): Int {
        val parsed = cookieHeader.orEmpty()
            .split(PAIR_SEPARATOR)
            .mapNotNull { pair -> Cookie.parse(url, "${pair.trim()}$HOST_WIDE_PATH") }
        if (parsed.isEmpty()) return 0
        synchronized(lock) { parsed.forEach(::put) }
        counterFor(url.host).incrementAndGet()
        return parsed.size
    }

    /** The transfer counter for this host. See [harvests]. */
    fun harvestCount(host: String): Long = counterFor(host).get()

    /** The cookies currently valid for a URL. For tests and the diagnostic export. */
    fun cookiesFor(url: HttpUrl): List<Cookie> = loadForRequest(url)

    private fun put(cookie: Cookie) {
        val key = Key(cookie.name, cookie.domain, cookie.path)
        if (cookie.expiresAt <= now()) {
            // A `Set-Cookie` with a past date *is* that cookie's deletion: it is how a site
            // withdraws a `cf_clearance` it no longer wants presented.
            stored.remove(key)
        } else {
            stored[key] = cookie
        }
    }

    private fun counterFor(host: String): AtomicLong =
        synchronized(lock) { harvests.getOrPut(host) { AtomicLong() } }

    private data class Key(val name: String, val domain: String, val path: String)

    private companion object {
        const val PAIR_SEPARATOR = ";"

        /**
         * The attribute appended to the WebView's line.
         *
         * Without it `Cookie.parse` derives the path from the loaded URL: a cookie obtained on
         * `/spotify-2.html` would apply to `/spotify-2.html` and not to the page next to it, so
         * rung 3 would be needed again on the same store's very next request.
         */
        const val HOST_WIDE_PATH = "; Path=/"
    }
}

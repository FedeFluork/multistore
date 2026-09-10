package com.multistore.store.common.html

import com.multistore.core.network.challenge.ChallengeEscalator
import com.multistore.core.network.challenge.ChallengeOutcome
import com.multistore.core.network.http.StoreHttpClient
import com.multistore.store.api.StoreError
import com.multistore.store.api.StoreResult
import com.multistore.store.common.StoreErrors
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response

/**
 * Fetching a page from a store and getting its HTML, or a `StoreError`.
 *
 * It gathers in one place the four things every scraping adapter must do identically, and which
 * copied nine times would be nine chances to diverge:
 *
 *  1. **go through the escalation ladder** rather than calling OkHttp directly — an adapter never
 *     handles challenges itself;
 *  2. tell a **block** from a **404** from a network failure, because the circuit breaker reacts
 *     differently to each;
 *  3. **close the response**, always, including on error paths;
 *  4. return the **final URL**, not the requested one.
 *
 * Point 4 looks like pedantry and is not: apkcombo answers **301** towards the canonical slug when
 * the requested one is not, and apkmirror redirects to a signed URL on another host. Resolving
 * relative links against the starting URL instead of the arriving one produces URLs that do not
 * exist — a fault that shows only on apps whose slug has changed, i.e. rarely and for no apparent
 * reason.
 *
 * **The rung reached goes into diagnostics.** Not from here directly: an adapter is stateless and
 * cannot see where the log lives. The datum passes through the store's client — which already
 * knows its own store id — and from there to a recorder. Only rungs **above zero** are recorded:
 * the ordinary request is nearly all of them, and recording it would fill diagnostics with rows
 * saying nothing happened.
 */
class PageFetcher(
    private val http: StoreHttpClient,
    /**
     * The ladder, defaulting to **the client's**.
     *
     * With a network-only default the silent WebView rung would exist with nobody walking it:
     * every adapter builds its own `PageFetcher` passing nothing, and wiring it would have meant
     * modifying all of them. Taking it from the client — which adapters already receive injected —
     * gets the full ladder to everyone and leaves the `:store:*` perimeter as it was.
     */
    private val escalator: ChallengeEscalator = http.escalator,
) {

    /** A downloaded page: the text, the URL it actually came from, and the code it arrived with. */
    data class Page(val url: String, val html: String, val code: Int) {
        /**
         * Whether this body arrived with a 404 — which only happens if the caller asked for it.
         *
         * Without [get]'s `readBodyOnNotFound` a 404 never becomes a [Page] at all, so this is
         * never true by accident: whoever reads it has already decided that on this store a 404
         * can carry a real page.
         */
        val isNotFound: Boolean get() = code == HTTP_NOT_FOUND
    }

    /**
     * A `GET`, and the page it answered with.
     *
     * ### `readBodyOnNotFound`, and why it is opt-in and named after one code
     *
     * A 404 normally becomes [StoreError.NotFound] here without the body ever being read, and that
     * is right nearly everywhere: the address does not exist, so there is nothing on the other side
     * to interpret. pdalife is the measured exception — since 06/09/2026 it answers **404 with its
     * complete search page** when a query matches nothing, or when a page is past the last one.
     * Collapsing that into `NotFound` reports a store that answered correctly as a store that
     * failed.
     *
     * It is opt-in because the default is the safe reading: a caller that has not measured its
     * store must not start handing 404 bodies to a parser, where "this address is gone" would
     * quietly turn into "no results". And it names **one** code rather than taking a set, because
     * one code is what has been measured — a set would be branches no configuration walks.
     *
     * What this does **not** decide is whether that body means anything: it hands over the page
     * with [Page.isNotFound] set and leaves the reading to the adapter, which is the only one that
     * knows what its store's search page looks like.
     */
    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        readBodyOnNotFound: Boolean = false,
    ): StoreResult<Page> {
        val request = Request.Builder().url(url).apply {
            headers.forEach { (name, value) -> header(name, value) }
        }.build()

        return when (val outcome = escalator.execute(request, http)) {
            is ChallengeOutcome.Passed -> {
                http.recordTier(outcome.tier)
                outcome.response.use { it.toPage(readBodyOnNotFound) }
            }

            is ChallengeOutcome.Blocked, is ChallengeOutcome.Failed ->
                StoreResult.Failure(StoreErrors.fromChallenge(outcome))
        }
    }

    /**
     * A POST with an `application/x-www-form-urlencoded` body.
     *
     * It exists for one case, and it is worth saying which: modyolo writes the file's URL on no
     * page. The theme asks for it with a POST to its admin AJAX endpoint, and **derives which file
     * to serve from the `Referer`** — without it, the same request answers 200 with twenty empty
     * bytes. It is the same request the browser makes, and that endpoint is the only path their
     * `robots.txt` explicitly allows.
     *
     * The four-second countdown the page shows is a `setTimeout` running **after** the response
     * has already arrived: not waiting for it skips nothing, because there is nothing to skip.
     */
    suspend fun post(
        url: String,
        form: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
    ): StoreResult<Page> {
        val body = FormBody.Builder().apply {
            form.forEach { (name, value) -> add(name, value) }
        }.build()
        val request = Request.Builder().url(url).post(body).apply {
            headers.forEach { (name, value) -> header(name, value) }
        }.build()

        return when (val outcome = escalator.execute(request, http)) {
            is ChallengeOutcome.Passed -> {
                http.recordTier(outcome.tier)
                outcome.response.use { it.toPage(readBodyOnNotFound = false) }
            }

            is ChallengeOutcome.Blocked, is ChallengeOutcome.Failed ->
                StoreResult.Failure(StoreErrors.fromChallenge(outcome))
        }
    }

    /**
     * A `HEAD`, and **what the server answered** — including an error.
     *
     * It differs from [resolveRedirect] on one point, which is the entire reason it exists: here a
     * 500 is a **successful request** reporting 500, not a failure. The preflight on modyolo needs
     * that, where roughly one binary in four answers 500: treating it as a store failure would trip
     * the circuit breaker on a perfectly live source, and with it three quarters of a working
     * catalogue would vanish from search. "This file is not there" and "this store is not
     * answering" are two different diagnoses and must stay apart.
     *
     * What stays a failure is what really concerns the store: a block, an unsolved challenge, a
     * dropped connection.
     */
    suspend fun head(url: String, headers: Map<String, String> = emptyMap()): StoreResult<HeadResult> {
        val request = Request.Builder().url(url).head().apply {
            headers.forEach { (name, value) -> header(name, value) }
        }.build()

        return when (val outcome = escalator.execute(request, http)) {
            is ChallengeOutcome.Passed -> outcome.response.use { response ->
                http.recordTier(outcome.tier)
                StoreResult.Success(
                    HeadResult(
                        url = response.request.url.toString(),
                        code = response.code,
                        contentLength = response.header(CONTENT_LENGTH)?.toLongOrNull(),
                        contentType = response.header(CONTENT_TYPE),
                        // Lowercased: HTTP headers are case-insensitive, and an1 writes
                        // `x-amz-meta-checksum-sha256` while a proxy in front of it might write
                        // `X-Amz-Meta-Checksum-Sha256`. An ordinary map would tell them apart, and
                        // the hash check would disappear without an error.
                        headers = response.headers.toMultimap()
                            .mapNotNull { (name, values) ->
                                values.firstOrNull()?.let { name.lowercase() to it }
                            }
                            .toMap(),
                    ),
                )
            }
            is ChallengeOutcome.Blocked, is ChallengeOutcome.Failed ->
                StoreResult.Failure(StoreErrors.fromChallenge(outcome))
        }
    }

    /** What a `HEAD` answered: where it ended up, with what code, and with which headers. */
    data class HeadResult(
        val url: String,
        val code: Int,
        val contentLength: Long?,
        val contentType: String?,
        val headers: Map<String, String>,
    ) {
        val isSuccessful: Boolean get() = code in HTTP_OK_RANGE

        /** One header by name, case-insensitively. */
        fun header(name: String): String? = headers[name.lowercase()]

        private companion object {
            val HTTP_OK_RANGE = 200..299
        }
    }

    /**
     * The final URL after redirects, **without** downloading the body.
     *
     * Two uses, different from each other: resolving the last hop of a signed download without
     * pulling a hundred megabytes, and the preflight on stores with dead binaries. A `HEAD`
     * response's body is empty by definition, so there is nothing to read here: what matters is
     * the code and the URL.
     */
    suspend fun resolveRedirect(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): StoreResult<Redirected> {
        val request = Request.Builder().url(url).head().apply {
            headers.forEach { (name, value) -> header(name, value) }
        }.build()

        return when (val outcome = escalator.execute(request, http)) {
            is ChallengeOutcome.Passed -> outcome.response.use { response ->
                http.recordTier(outcome.tier)
                if (!response.isSuccessful) {
                    StoreResult.Failure(StoreErrors.fromResponse(response))
                } else {
                    StoreResult.Success(
                        Redirected(
                            url = response.request.url.toString(),
                            contentLength = response.header(CONTENT_LENGTH)?.toLongOrNull(),
                            contentType = response.header(CONTENT_TYPE),
                        ),
                    )
                }
            }
            is ChallengeOutcome.Blocked, is ChallengeOutcome.Failed ->
                StoreResult.Failure(StoreErrors.fromChallenge(outcome))
        }
    }

    /** The outcome of a HEAD: where it lands, and what there would be to download. */
    data class Redirected(val url: String, val contentLength: Long?, val contentType: String?)

    private fun Response.toPage(readBodyOnNotFound: Boolean): StoreResult<Page> {
        // A 404 the caller has asked to read is the one non-2xx that gets this far. Every other
        // code, and a 404 nobody asked about, keeps the old reading and never reaches the body.
        val readable = isSuccessful || (readBodyOnNotFound && code == HTTP_NOT_FOUND)
        if (!readable) return StoreResult.Failure(StoreErrors.fromResponse(this))
        val text = runCatching { body.string() }.getOrElse {
            return StoreResult.Failure(StoreError.Network(it, code))
        }
        if (text.isBlank()) {
            // A 200 with an empty body is not a page: it is a failure disguised as success, and
            // handing it to the parser would give a parse failure that sends people looking for a
            // markup change that never happened.
            //
            // A **404** with an empty body is the ordinary 404 the caller was hoping was something
            // else, so it goes back to being one: reporting `Network` there would replace a
            // diagnosis that is right with one that is not.
            return if (isSuccessful) {
                StoreResult.Failure(StoreError.Network(cause = null, httpCode = code))
            } else {
                StoreResult.Failure(StoreErrors.fromResponse(this))
            }
        }
        return StoreResult.Success(Page(url = request.url.toString(), html = text, code = code))
    }

    private companion object {
        const val CONTENT_LENGTH = "Content-Length"
        const val CONTENT_TYPE = "Content-Type"
        const val HTTP_NOT_FOUND = 404
    }
}

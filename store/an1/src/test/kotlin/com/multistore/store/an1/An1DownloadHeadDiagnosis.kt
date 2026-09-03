package com.multistore.store.an1

import com.multistore.core.model.Sha256

/**
 * What a missing `expectedSha256` on an1 actually means: a header the store never published, or a
 * `HEAD` that never answered.
 *
 * ### Why the two have to be told apart
 *
 * [An1StoreAdapter.getDownloadLink] resolves the file's metadata with **one un-retried `HEAD`**,
 * and every way that request can go wrong collapses into the same `null`:
 *
 * ```kotlin
 * val head = (fetcher.head(file.url) as? StoreResult.Success)?.value?.takeIf { it.isSuccessful }
 * ```
 *
 * `as? StoreResult.Success` drops an IOException; `takeIf { it.isSuccessful }` drops a 429 and
 * every 5xx. After that, `expectedSha256` and `expectedSize` are **both** null and the download
 * URL has silently fallen back to the pre-redirect one.
 *
 * The canary used to assert both fields non-null with bare Truth, so the whole of that arrived as
 *
 * > expected not to be null
 *
 * naming none of the four jobs — and, worse, arriving under the heading `canary.yml` reserves for
 * this store: *"an1, 'the hash is gone' — an1 becomes a store with no integrity verification and
 * the capability must go back to `NONE`"*. A 429 on a shared CDN budget would therefore be read as
 * an1 having stopped publishing checksums, which is a conclusion about the store drawn from a fact
 * about the network. And because nothing ever reached `orFail`, the `RateLimited` branch written
 * **specifically** for an1's shared budget was structurally unreachable from the one surface that
 * produces it.
 *
 * ### The asymmetry that separates them, measured
 *
 * `content-length` is not a `SOMETIMES`: it was present on **21 of 21** successful `HEAD`s across
 * the sampled objects. The checksum header is — `x-amz-meta-checksum-sha256` sits on the canary's
 * reference and on 12 of 12 listings linked from the homepage, and on **0 of 8** older on-host
 * listings, which are served by a second uploader path that publishes nothing. Hence
 * `providesHash = SOMETIMES` rather than `ALWAYS`.
 *
 * So a **size** that is null is not "an1 published no size": it is *the request did not answer*.
 * That single implication is the whole of this function.
 *
 * Measured through the adapter on 03/09/2026 for `2971-telegram`: url
 * `files.an1.net/telegram_12.4.3-an1.com.apk`, size 83,757,788, checksum
 * `c62171f0…2ea20a3d` — i.e. the healthy path, both fields present.
 */
internal enum class HeadVerdict {
    /** Both fields arrived: the CDN answered and published a checksum. */
    ANSWERED,

    /**
     * The `HEAD` answered but carried no checksum — the `SOMETIMES` case, and real news.
     *
     * It stays a **failure** rather than a skip, and deliberately: `canary.yml` treats a
     * disappeared checksum as one of its five single-store cases, because an1 with no hash is an
     * an1 with no integrity verification at all. What the message must not do is state the
     * conclusion — it is equally likely that this one object was re-uploaded through the older
     * path, and the next step is to look at whether *other* objects still carry the header.
     */
    HASH_NOT_PUBLISHED,

    /**
     * The `HEAD` did not answer at all, and nothing here says anything about checksums.
     *
     * Recognised by the **size** being absent, which the census above makes decisive.
     */
    HEAD_DID_NOT_ANSWER,
}

/**
 * The verdict for a resolution's two metadata fields.
 *
 * Keyed on [size] and not on [sha] because only one of the two is a `SOMETIMES`. Reading it the
 * other way round — "no hash means the request failed" — is exactly the conflation that produced
 * the wrong heading, and it would also make the honest `HASH_NOT_PUBLISHED` case unreachable.
 */
internal fun an1HeadVerdict(sha: Sha256?, size: Long?): HeadVerdict = when {
    size == null -> HeadVerdict.HEAD_DID_NOT_ANSWER
    sha == null -> HeadVerdict.HASH_NOT_PUBLISHED
    else -> HeadVerdict.ANSWERED
}

/**
 * Whether a status code from the file host is an1's **shared** budget refusing us rather than
 * anything of ours.
 *
 * `x-ratelimit-limit` and `x-ratelimit-remaining` exist on `files.an1.net` and **do not describe
 * us**: three identical `HEAD`s in a row left `remaining` unmoved at 1355, three on another object
 * saw it fall by three while we made one request, and between two measurements it *rose* from 1346
 * to 1355. It is a budget the world shares and that recharges, so a 429 can arrive without our
 * having consumed anything — which is why it is the one code here that skips instead of failing.
 *
 * Everything else fails, 5xx included: a CDN erroring on an object is a fact worth an issue, and
 * this predicate must not grow into "any unhappy code is somebody else's problem".
 */
internal fun an1HeadIsSharedBudget(code: Int?): Boolean = code == SHARED_BUDGET_CODE

/** The one code that means the shared budget, not us. */
internal const val SHARED_BUDGET_CODE = 429

package com.multistore.store.modyolo

/**
 * How to read a `preflight` that came back **false** on modyolo: a dead binary of theirs, or
 * something of ours.
 *
 * ### The red that carried no message at all
 *
 * The line this replaces was `assertThat(modyolo.preflight(direct).orFail("preflight")).isTrue()`,
 * and it is worth being precise about why that is the worst-behaved assertion in the nine canaries.
 *
 * [ModyoloStoreAdapter.preflight] is `fetcher.head(url).map { it.isSuccessful }`, and
 * [com.multistore.store.common.html.PageFetcher.head] exists **precisely** so that a 500 is a
 * successful request reporting 500 rather than a failure of the store — because on modyolo roughly
 * one binary in four answers 500, measured 11/40 on the oldest layer of posts and 15/40 in the
 * middle. So a dead file produces `StoreResult.Success(false)`; `orFail` sees a `Success` and hands
 * the `false` straight through; and Truth prints
 *
 * > expected: true
 * > but was: false
 *
 * That is the entire issue body. No host, no status code, no job, and no hint that the most likely
 * cause is a documented property of the store. It is the one assertion in the class that bypasses
 * `orFail` completely, sitting on the single most likely thing to be true about modyolo.
 *
 * It is not a remote hazard, either. The file name is built from the post's `lastest_version` —
 * their typo, ours to live with — so a version bumped in the CMS before the object reaches the CDN
 * produces exactly this, transiently, on a store that is otherwise perfectly healthy.
 *
 * ### Why a status code and not just "false"
 *
 * Because aborting on every `false` would give away a real regression. `getDownloadLink` resolves
 * the file URL through `admin-ajax.php`; if that endpoint started handing back a **wrong** URL,
 * the resolution would still succeed and `preflight` would still be false — and that is ours to
 * fix. What separates the two is the code the CDN answers, and modyolo's dead binaries have a
 * measured signature: **500**. So 500 is their catalogue rotting, and anything else is a question
 * worth asking loudly.
 *
 * Measured through the adapter on 03/09/2026 for the canary's own reference: `HEAD` on
 * `files-2.modyolo.com/Minecraft/Minecraft_v1_26_50_27.apk` answered **200** with
 * `content-length: 1145807996`, and `preflight` answered `Success(true)`.
 *
 * The extra `HEAD` this needs costs nothing on the happy path: it is only made once `preflight`
 * has already said false, so a healthy night makes exactly the requests it made before.
 */
internal enum class DeadBinaryVerdict {
    /**
     * Their catalogue, not our adapter: skip the check.
     *
     * The premise of the measurement — that this reference app's binary is still on the CDN — has
     * expired, and no change to this repository would make it true again.
     */
    THEIR_DEAD_BINARY,

    /**
     * Ours, or at least not explained by the known rot: fail, naming the code.
     *
     * A 404 belongs here deliberately. modyolo's dead objects answer 500; a 404 on a URL that
     * `admin-ajax.php` handed us a moment earlier is a different animal — a path convention that
     * has moved, or a resolved URL that was never right — and that is a repair.
     */
    WORTH_FAILING,
}

/**
 * The verdict for a `preflight` that answered false, given the status code the same URL returns.
 *
 * [code] is `null` when the diagnostic `HEAD` itself could not be made, and that is
 * [DeadBinaryVerdict.WORTH_FAILING]: two requests to the same URL disagreeing about whether they
 * can even be completed is not the store's dead-binary signature, and guessing in the permissive
 * direction is how a canary starts skipping its way to permanent silence.
 */
internal fun deadBinaryVerdict(code: Int?): DeadBinaryVerdict =
    if (code == DEAD_BINARY_CODE) DeadBinaryVerdict.THEIR_DEAD_BINARY else DeadBinaryVerdict.WORTH_FAILING

/**
 * The code modyolo's missing objects answer: 500.
 *
 * Not 404, and that is the measurement worth keeping. A store whose absent files answered 404
 * would need the opposite mapping, and reading this constant as "the not-found code" would invert
 * the whole function.
 */
internal const val DEAD_BINARY_CODE = 500

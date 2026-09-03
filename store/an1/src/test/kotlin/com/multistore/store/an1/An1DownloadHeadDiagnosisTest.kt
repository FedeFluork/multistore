package com.multistore.store.an1

import com.google.common.truth.Truth.assertThat
import com.multistore.core.model.Sha256
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The an1 canary's reading of a missing checksum, exercised **offline**.
 *
 * Necessary for the reason the function exists: on a healthy night both fields arrive, so a green
 * canary never runs either of the two branches that matter. The distinction they draw is between
 * *"an1 stopped publishing hashes"* — which `canary.yml` treats as one of its five single-store
 * cases — and *"one request did not answer"*, which says nothing about hashes at all. Getting it
 * backwards is how a rate limit becomes a decision to drop a store's integrity verification.
 *
 * No `@Tag("canary")`: this touches nothing and runs with the offline suite on every build.
 */
@DisplayName("an1 — reading a missing checksum")
class An1DownloadHeadDiagnosisTest {

    private val hash = Sha256.parseOrNull(
        "c62171f089a1eef035642eb7d92388f451307bef9d345e2d70766ee72ea20a3d",
    )!!

    @Test
    @DisplayName("both fields present is the healthy path")
    fun bothPresent() {
        assertThat(an1HeadVerdict(hash, 83_757_788)).isEqualTo(HeadVerdict.ANSWERED)
    }

    /**
     * The `SOMETIMES` case: the request worked, the header was not there.
     *
     * Recognised by the **size** being present, which is what makes it distinguishable at all.
     */
    @Test
    @DisplayName("a size with no hash is the store's SOMETIMES, and stays a failure")
    fun sizeWithoutHash() {
        assertThat(an1HeadVerdict(null, 83_757_788)).isEqualTo(HeadVerdict.HASH_NOT_PUBLISHED)
    }

    /**
     * **The case that used to be reported as the one above.**
     *
     * `content-length` was present on 21 of 21 successful `HEAD`s, so a null size is not an1
     * declining to publish a size: it is the request not having answered. Both fields come from
     * the same `HEAD`, so this is also the only shape a failed request can produce.
     */
    @Test
    @DisplayName("no size means the HEAD did not answer, whatever the hash says")
    fun noSizeIsNoAnswer() {
        assertThat(an1HeadVerdict(null, null)).isEqualTo(HeadVerdict.HEAD_DID_NOT_ANSWER)
        // Not reachable from the adapter — both fields come from one response — but the function
        // must not read a hash as evidence that the request succeeded.
        assertThat(an1HeadVerdict(hash, null)).isEqualTo(HeadVerdict.HEAD_DID_NOT_ANSWER)
    }

    /**
     * The three verdicts stay three, which is the assertion that survives a rewording.
     *
     * If someone folds `HASH_NOT_PUBLISHED` and `HEAD_DID_NOT_ANSWER` back together — the exact
     * conflation this file exists to prevent — this goes red regardless of how the branches are
     * phrased.
     */
    @Test
    @DisplayName("the three readings do not collapse into two")
    fun theReadingsStayDistinct() {
        val verdicts = listOf(
            an1HeadVerdict(hash, 1L),
            an1HeadVerdict(null, 1L),
            an1HeadVerdict(null, null),
        )

        assertThat(verdicts.toSet()).hasSize(3)
    }

    /**
     * Only 429 is somebody else's budget.
     *
     * Asserted over the whole range rather than case by case, so that letting a second code skip
     * has to be a deliberate edit here. A 5xx in particular must keep failing: a CDN erroring on
     * an object is worth an issue, and this predicate is one careless `in` away from meaning "any
     * unhappy code is not our problem".
     */
    @Test
    @DisplayName("429 is the only code that counts as the shared budget")
    fun onlyTooManyRequests() {
        val shared = (200..599).filter { an1HeadIsSharedBudget(it) }

        assertThat(shared).containsExactly(SHARED_BUDGET_CODE)
        assertThat(an1HeadIsSharedBudget(null)).isFalse()
    }
}

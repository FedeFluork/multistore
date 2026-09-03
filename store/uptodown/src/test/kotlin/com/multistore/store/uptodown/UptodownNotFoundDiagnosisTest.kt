package com.multistore.store.uptodown

import com.google.common.truth.Truth.assertThat
import com.multistore.core.model.BlockKind
import com.multistore.store.api.StoreError
import com.multistore.store.api.StoreResult
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The 404 diagnosis, offline.
 *
 * It is the only test in this module about a **message**, and it exists because the thing that
 * failed on 31/08/2026 was a message: five red checks naming one cause, and the one cause it could
 * not have been. A diagnostic exercised only by the failure it describes is a diagnostic nobody
 * ever checks — the canary that prints it cannot run without the network, and by the time it does
 * run, whoever reads it has already been sent somewhere.
 *
 * So this is deliberately **untagged**: it runs in `test`, offline, in milliseconds, and it touches
 * no site. Its subject is the pair that reads a 404 — `uptodownNotFoundMessage`, which chooses the
 * words, and `uptodownIsEgressRefusal`, which chooses whether the nightly opens an issue at all.
 * They are tested together because they are two functions of one answer, and the way they break is
 * by disagreeing.
 */
@DisplayName("uptodown — reading a 404")
class UptodownNotFoundDiagnosisTest {

    @Test
    fun `the language root answering makes it this one address`() {
        val message = uptodownNotFoundMessage(WHAT, StoreResult.Success(Unit))

        // The reader has to be sent to the address, and only to the address.
        assertThat(message).contains(WHAT)
        assertThat(message).contains("{slug}.en.uptodown.com/android")
        assertThat(message).contains("UptodownConfig")
    }

    @Test
    fun `the language root answering 404 too is a refusal, and says do not touch the parsers`() {
        val message = uptodownNotFoundMessage(WHAT, StoreResult.Failure(StoreError.NotFound))

        // The three sentences this branch exists for. The first two are what the old message got
        // wrong by omission; the third is why nothing upstream noticed — a refusal dressed as a 404
        // never becomes `StoreError.Blocked`, so no rung of the escalation ladder is offered it.
        assertThat(message).contains("not a changed URL scheme")
        assertThat(message).contains("Do not rewrite selectors")
        assertThat(message).contains("StoreError.Blocked")
        // And the job it *does* send the reader to: re-measure from where the app actually lives.
        assertThat(message).contains("consumer connection")
        // The address named is read from the config rather than retyped: a hard-coded copy here
        // would keep saying `en.uptodown.com` on the day the language root changed, which is the
        // one day this message is read closely.
        assertThat(message).contains(UptodownConfig.DEFAULT_BASE_URL)
    }

    /**
     * **The message has to declare the coverage it lost**, because this is the only reading whose
     * outcome is a *skip* — and a skip is the one outcome that looks like nothing happened.
     *
     * An earlier draft of this branch closed with "if they answer, what is red is the canary's
     * egress": framing written for a failure, kept after the outcome had become an abort, when
     * nothing is red at all. Its KDoc claimed the text said the run had verified nothing; it did
     * not, and no assertion noticed — the same defect class the whole file exists to fix, one level
     * up. So the two facts a reader of a green nightly needs are pinned here: that this run checked
     * nothing, and that the check was skipped rather than passed.
     */
    @Test
    fun `the skipped reading says out loud that it verified nothing`() {
        val message = uptodownNotFoundMessage(WHAT, StoreResult.Failure(StoreError.NotFound))

        assertThat(message).contains("verified nothing")
        assertThat(message).contains("skipped, not failed")
        // And it must not go back to describing itself as a failure: "red" belongs to the readings
        // that fail, and this one does not.
        assertThat(message).doesNotContain("what is red")
    }

    /**
     * The two independent roots, named — because the sentence that used to stand here was false.
     *
     * `UptodownConfig.baseUrl` builds search, the chart and the recent list; `appUrlTemplate` builds
     * the listing and the download page, and no code path derives one from the other. The old text
     * called the root "the address every URL in this adapter is built from", which made the probe
     * sound like an independent question for all five checks when it is one for two of them. A
     * reader who believes the wrong version will not think of the case where the base URL itself is
     * simply wrong — which produces exactly this reading.
     */
    @Test
    fun `the message does not claim the root builds every address`() {
        val message = uptodownNotFoundMessage(WHAT, StoreResult.Failure(StoreError.NotFound))

        assertThat(message).contains("appUrlTemplate")
        assertThat(message).doesNotContain("every URL")
        // And it says what to change if the root is unreachable from a consumer connection too,
        // instead of the old flat "do not change the base URL".
        assertThat(message).contains("base URL that needs changing")
    }

    @Test
    fun `a root that failed differently hands the reader the root's own error`() {
        val blocked = StoreError.Blocked(BlockKind.FORBIDDEN)

        val message = uptodownNotFoundMessage(WHAT, StoreResult.Failure(blocked))

        // A 403 on the root is a better diagnosis than a 404 anywhere else, and the message must
        // carry it verbatim instead of flattening it into "not found".
        assertThat(message).contains(blocked.toString())
        assertThat(message).contains("symptom")
    }

    /**
     * **The assertion that would have caught the original defect**, and the reason it is phrased
     * about difference rather than about wording.
     *
     * The old branch was not badly worded: it was *one* wording for every 404. Any future collapse
     * back to that — a shared string, a `when` that stops branching, a helpful de-duplication —
     * reddens here, whatever the words are. Asserting on the words alone would not: three
     * identical-but-correct sentences would pass.
     */
    @Test
    fun `the four readings of a 404 are four different messages`() {
        val messages = listOf(
            StoreResult.Success(Unit),
            StoreResult.Failure(StoreError.NotFound),
            StoreResult.Failure(StoreError.Blocked(BlockKind.FORBIDDEN)),
            StoreResult.Unsupported,
        ).map { uptodownNotFoundMessage(WHAT, it) }

        assertThat(messages.toSet()).hasSize(messages.size)
    }

    /**
     * **Which reading is allowed to make the canary skip**, and the assertion that keeps the two
     * halves from drifting.
     *
     * The choice of words and the choice of outcome are two functions of the same one answer, and
     * splitting them fails silently in both directions: a canary that opens an issue while printing
     * *"do not rewrite selectors"*, or one that quietly skips while printing *"the URL scheme
     * moved"*. Either way the reader is told one thing and the pipeline does another, and neither is
     * visible in a report that only records pass and fail.
     *
     * The width is the whole point. Exactly **one** of the four readings may abort — and it is the
     * one about the network, not about us. Widening the predicate by a single reading would start
     * hiding a moved URL scheme, which is the failure this canary exists to find; narrowing it to
     * none brings back the nightly issue about a store that answers 200 from where the app runs.
     */
    @Test
    fun `only the reading that says touch nothing is the one allowed to skip`() {
        val roots = listOf(
            StoreResult.Success(Unit),
            StoreResult.Failure(StoreError.NotFound),
            StoreResult.Failure(StoreError.Blocked(BlockKind.FORBIDDEN)),
            StoreResult.Unsupported,
        )

        val skippable = roots.filter { uptodownIsEgressRefusal(it) }

        // One of the four, and it is the root-404 one: the case where the whole store is
        // unreachable from here. A root that *answers* while one address 404s stays a failure.
        assertThat(skippable).containsExactly(StoreResult.Failure(StoreError.NotFound))
        // And it is the reading whose own message forbids touching the parsers. Asserting the pair
        // rather than the predicate alone is what stops the outcome and the words from diverging.
        assertThat(uptodownNotFoundMessage(WHAT, skippable.single()))
            .contains("Do not rewrite selectors")
        // The mirror of the line above, and the one that would catch a predicate widened "just for
        // the blocked case": the reading that sends the reader to an address must never skip.
        assertThat(uptodownIsEgressRefusal(StoreResult.Success(Unit))).isFalse()
    }

    private companion object {
        /**
         * What the canary passes: the name of the call that failed. It must survive into the
         * text — five identical sentences with no subject would be five failures nobody can
         * line up against the five checks.
         */
        const val WHAT = "search"
    }
}

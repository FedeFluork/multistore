package com.multistore.store.modyolo

import com.google.common.truth.Truth.assertThat
import com.multistore.core.model.BlockKind
import com.multistore.store.api.StoreError
import com.multistore.store.api.StoreResult
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * The two decisions the modyolo canary makes about a failure, exercised **offline**.
 *
 * They need a test of their own for the reason [ModyoloNotFoundDiagnosis][modyoloNotFoundMessage]
 * and [ModyoloDeadBinary][deadBinaryVerdict] exist at all: on a healthy night neither case occurs,
 * so a green canary never runs a line of either. A diagnostic exercised only by the failure it
 * describes is a diagnostic nobody has ever checked — and the two shapes it exists to tell apart
 * lead to opposite jobs, so getting it silently wrong costs somebody a night.
 *
 * Note this file carries **no** `@Tag("canary")`: it touches nothing and runs in `test`, with the
 * offline suite, on every build.
 */
@DisplayName("modyolo — reading a failure")
class ModyoloCanaryDiagnosisTest {

    @Nested
    @DisplayName("a 404")
    inner class NotFound {

        @Test
        @DisplayName("with the root answering, sends the reader to the REST path and not to the post")
        fun rootAliveNamesTheRestPath() {
            val message = modyoloNotFoundMessage("search", StoreResult.Success(Unit))

            assertThat(message).contains("the site root answers")
            // The repair the old sentence ruled out, and the one that fits three of the four
            // checks: this adapter speaks nothing but `wp-json`.
            assertThat(message).contains(ModyoloConfig.SEARCH_PATH)
            assertThat(message).contains(ModyoloConfig.DETAIL_PATH)
            assertThat(message).contains("rest_no_route")
            // And it must not close the question the way its predecessor did.
            assertThat(message).doesNotContain("Not an adapter fault")
        }

        @Test
        @DisplayName("with the root 404ing too, says this run verified nothing")
        fun rootDeadIsNoMeasurement() {
            val root = StoreResult.Failure(StoreError.NotFound)

            assertThat(modyoloIsUnreachable(root)).isTrue()
            val message = modyoloNotFoundMessage("detail", root)
            assertThat(message).contains("verified nothing")
            assertThat(message).contains("skipped, not failed")
            assertThat(message).contains(ModyoloConfig.DEFAULT_BASE_URL)
            // The instruction that matters: do not go rewriting things from a datacentre.
            assertThat(message).contains("consumer connection")
        }

        @Test
        @DisplayName("a root that failed some other way sends the reader to that error instead")
        fun rootFailedDifferently() {
            val root = StoreResult.Failure(StoreError.Blocked(BlockKind.FORBIDDEN))

            assertThat(modyoloIsUnreachable(root)).isFalse()
            assertThat(modyoloNotFoundMessage("download", root)).contains("failed too — differently")
        }

        /**
         * The width of the skip, asserted as a **set** and not case by case.
         *
         * This is the assertion that keeps the predicate honest as it is edited. Any widening —
         * treating a block, a 429 or a dropped connection as "unreachable" — starts hiding the
         * failure the canary exists to find, and it would do so silently, because a skipped check
         * opens no issue.
         */
        @Test
        @DisplayName("only the root-404 reading skips; every other answer stays a failure")
        fun onlyTheRootFourOhFourSkips() {
            val skipping = candidateRootAnswers().filter { modyoloIsUnreachable(it) }

            assertThat(skipping).containsExactly(StoreResult.Failure(StoreError.NotFound))
        }

        /**
         * Every reading of the root the canary can actually receive, and four messages that differ.
         *
         * Asserting the wording of each one would be asserting our own prose against itself. What
         * has to hold is that the readings do not **collapse**: the day someone simplifies this
         * into one sentence for every case — which is what f-droid's `orFail` did for nine
         * canaries' worth of time — this goes red whatever the words are.
         */
        @Test
        @DisplayName("the readings stay distinct, whatever the wording")
        fun theReadingsDoNotCollapse() {
            val messages = candidateRootAnswers().map { modyoloNotFoundMessage("detail", it) }

            assertThat(messages.toSet()).hasSize(messages.size)
            messages.forEach { assertThat(it).startsWith("detail: ") }
        }

        private fun candidateRootAnswers(): List<StoreResult<Unit>> = listOf(
            StoreResult.Success(Unit),
            StoreResult.Failure(StoreError.NotFound),
            StoreResult.Failure(StoreError.Blocked(BlockKind.FORBIDDEN)),
            StoreResult.Unsupported,
        )
    }

    @Nested
    @DisplayName("a preflight that came back false")
    inner class DeadBinary {

        @Test
        @DisplayName("a 500 is their catalogue ageing, and skips")
        fun fiveHundredIsTheirs() {
            assertThat(deadBinaryVerdict(DEAD_BINARY_CODE)).isEqualTo(DeadBinaryVerdict.THEIR_DEAD_BINARY)
        }

        /**
         * **A 404 is not the same news, and this is the assertion that says so.**
         *
         * It would be the easy simplification — "the file is not there, skip it" — and it would
         * give away the regression this test exists to protect: `admin-ajax.php` resolving to a URL
         * that is no longer right produces a `preflight` of false that looks identical from the
         * outside. modyolo's rotted objects answer 500; a 404 means the path convention moved.
         */
        @Test
        @DisplayName("a 404 is ours, and fails")
        fun fourOhFourIsOurs() {
            assertThat(deadBinaryVerdict(404)).isEqualTo(DeadBinaryVerdict.WORTH_FAILING)
        }

        @Test
        @DisplayName("an unreadable probe fails rather than guessing permissively")
        fun noCodeFails() {
            assertThat(deadBinaryVerdict(null)).isEqualTo(DeadBinaryVerdict.WORTH_FAILING)
        }

        /**
         * The same width assertion as for the 404 branch, and for the same reason: exactly one code
         * may skip. Written over a range so that adding a second skipping code has to be a
         * deliberate edit to this test and not a quiet edit to the function.
         */
        @Test
        @DisplayName("500 is the only code that skips")
        fun onlyFiveHundredSkips() {
            val skipping = (200..599).filter {
                deadBinaryVerdict(it) == DeadBinaryVerdict.THEIR_DEAD_BINARY
            }

            assertThat(skipping).containsExactly(DEAD_BINARY_CODE)
        }
    }
}

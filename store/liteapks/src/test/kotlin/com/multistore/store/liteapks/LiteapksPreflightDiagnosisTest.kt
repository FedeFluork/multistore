package com.multistore.store.liteapks

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The liteapks canary's reading of a false `preflight`, exercised **offline**.
 *
 * It needs its own test for the reason the function exists: on a healthy night the file answers 200
 * and neither branch runs, so a green canary never exercises the distinction. And the distinction
 * is between an issue nobody can act on — a third party's storage quota — and a CDN migration that
 * somebody must.
 *
 * No `@Tag("canary")`: this touches nothing and runs with the offline suite on every build.
 */
@DisplayName("liteapks — reading a false preflight")
class LiteapksPreflightDiagnosisTest {

    /**
     * 429 is the shared quota, and it is the code that never reaches `orFail` on its own.
     *
     * `ChallengeDetector` recognises 403, 503 and 451, so a 429 is not a challenge: it comes back
     * as a successful `HeadResult(429)`, `preflight` maps it to `false`, and the `RateLimited`
     * branch written about exactly this host cannot be reached from here.
     */
    @Test
    @DisplayName("429 is somebody else's quota, and skips")
    fun tooManyRequestsSkips() {
        assertThat(liteapksPreflightVerdict(SHARED_QUOTA_CODE))
            .isEqualTo(PreflightVerdict.SOMEONE_ELSES_QUOTA)
    }

    /**
     * **404 fails, and this is the assertion that keeps the skip narrow.**
     *
     * "Any unhappy code on a CDN that is not liteapks' is not our problem" was the easy reading,
     * and it would swallow a CDN migration in silence: the resolution would keep succeeding,
     * `preflight` would keep saying false, and the canary would report nothing for ever.
     */
    @Test
    @DisplayName("404 is a moved object and fails")
    fun notFoundFails() {
        assertThat(liteapksPreflightVerdict(404)).isEqualTo(PreflightVerdict.WORTH_FAILING)
    }

    @Test
    @DisplayName("an unreadable probe fails rather than guessing permissively")
    fun noCodeFails() {
        assertThat(liteapksPreflightVerdict(null)).isEqualTo(PreflightVerdict.WORTH_FAILING)
    }

    /**
     * Exactly one code may skip, asserted over the range.
     *
     * Written this way so that letting a second one through has to be a deliberate edit here
     * rather than a quiet edit to the function — a skipped check opens no issue, so the cost of
     * getting this wrong is invisible.
     */
    @Test
    @DisplayName("429 is the only code that skips")
    fun onlyOneCodeSkips() {
        val skipping = (200..599).filter {
            liteapksPreflightVerdict(it) == PreflightVerdict.SOMEONE_ELSES_QUOTA
        }

        assertThat(skipping).containsExactly(SHARED_QUOTA_CODE)
    }
}

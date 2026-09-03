package com.multistore.store.apkmody

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The apkmody canary's reading of its own root, exercised **offline**.
 *
 * The reason it needs a test is the reason the function exists: on a healthy night the root answers
 * from its own host, so a green canary only ever runs one of the three branches. The branch that
 * matters is the one that has never run — a root answering from **another domain** — and it is the
 * single event this store's history says to expect.
 *
 * No `@Tag("canary")`: this touches nothing and runs with the offline suite on every build.
 */
@DisplayName("apkmody — reading the root")
class ApkModyRootDiagnosisTest {

    private val own = "apkmody.mobi"

    @Test
    @DisplayName("the root on its own host explains nothing by itself")
    fun ownHost() {
        assertThat(apkModyRootReading(own, own)).isEqualTo(RootReading.OWN_HOST)
    }

    /**
     * The host comparison is case-insensitive, because a DNS name is.
     *
     * A `Location` header echoing a differently-cased host would otherwise read as the store having
     * run away — the loudest message this class can emit, for nothing.
     */
    @Test
    @DisplayName("host comparison ignores case")
    fun caseInsensitive() {
        assertThat(apkModyRootReading("APKMody.Mobi", own)).isEqualTo(RootReading.OWN_HOST)
    }

    /**
     * **The branch `healthCheck` cannot produce.**
     *
     * `fetcher.resolveRedirect(baseUrl).map { }` discards the URL it resolved to, so the adapter's
     * own probe answers `Success` for exactly this case. Both hosts named here are real: deep paths
     * on `apkmody.com` already 301 to `wokogames.com`.
     */
    @Test
    @DisplayName("a root answering from another domain is the store having moved")
    fun movedAway() {
        assertThat(apkModyRootReading("wokogames.com", own)).isEqualTo(RootReading.MOVED_AWAY)
    }

    @Test
    @DisplayName("an unresolvable root explains nothing and is not a move")
    fun noAnswer() {
        assertThat(apkModyRootReading(null, own)).isEqualTo(RootReading.NO_ANSWER)
        // A missing expectation is our bug, not theirs, and must not be reported as a move.
        assertThat(apkModyRootReading(own, null)).isEqualTo(RootReading.NO_ANSWER)
    }

    /**
     * The three readings stay three.
     *
     * Folding `MOVED_AWAY` back into `OWN_HOST` is the simplification that would restore
     * `healthCheck`'s blind spot inside the canary, and it would do so silently: the nightly would
     * go on passing while pointing at a parked domain.
     */
    @Test
    @DisplayName("the readings do not collapse")
    fun readingsStayDistinct() {
        val readings = listOf(
            apkModyRootReading(own, own),
            apkModyRootReading("wokogames.com", own),
            apkModyRootReading(null, own),
        )

        assertThat(readings.toSet()).hasSize(3)
    }
}

package com.multistore.store.apkcombo

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Reading the feed's titles, offline.
 *
 * The subject is [survivingFeedMarker], and this class is the only thing that ever exercises it: on
 * a healthy day the live feed carries neither a surviving marker nor an app whose name opens with a
 * bracket, so the nightly canary can go green for months without either case having occurred. Hence
 * **untagged** — it runs in `test`, offline, in microseconds — for the same reason
 * `UptodownNotFoundDiagnosisTest` exists next door.
 *
 * Every case here is a mirror of another, and both sides are real: one of them is the defect of
 * 01/09/2026, when the check that stood here forbade a shape apkcombo publishes.
 */
@DisplayName("apkcombo — reading the feed's titles")
class ApkComboFeedMarkerTest {

    @Test
    fun `a marker that survived leads every row, and is caught`() {
        // What a broken `FEED_PREFIX` looks like: the strip matched nothing, so the marker apkcombo
        // puts on every entry is still on every entry. Measured 03/09/2026: 96 raw titles out of 96
        // began `[apk_updated]`.
        val titles = (1..96).map { "[apk_updated] App number $it" }

        val marker = survivingFeedMarker(titles)

        assertThat(marker).isNotNull()
        assertThat(marker!!.token).isEqualTo("[apk_updated]")
        // The count and the total both travel, because the message states the ratio: "96 of 96" is
        // a marker, and a bare "96" is a number the reader has to go and divide.
        assertThat(marker.count).isEqualTo(96)
        assertThat(marker.of).isEqualTo(96)
    }

    /**
     * **The case that reddened the nightly on 01/09/2026**, reproduced exactly.
     *
     * The window was ninety-six entries and one of them was `[Official] Atomy shop` — the app's own
     * name, left behind after the marker had been stripped precisely as it should be. The listing
     * page and search, neither of which passes through the stripping, both call it that, and the
     * publisher ships four such apps. So all four are here: this must be silent.
     */
    @Test
    fun `apps that bracket their own names are not a marker`() {
        val titles = ATOMY + (1..92).map { "App number $it" }

        assertThat(survivingFeedMarker(titles)).isNull()
    }

    /**
     * The same four names on **the narrowest window the canary accepts**, which is where the share
     * alone was unsafe.
     *
     * `ApkComboCanaryTest.MIN_FEED_ITEMS` is 10 and the comment there says a feed's width depends on
     * how much the store published that day, so a thin night is legal. A quarter of ten is two, and
     * four real `[Official] …` names clear that on their own: without [MARKER_FLOOR] this reproduced
     * the 01/09 false positive, and the message would have blamed a regex that had not moved. Both
     * ends of the legal range are asserted because only one of them was ever broken.
     */
    @Test
    fun `four real names stay silent on a thin night as well as a fat one`() {
        assertThat(survivingFeedMarker(ATOMY + (1..6).map { "App $it" })).isNull()
        assertThat(survivingFeedMarker(ATOMY + (1..16).map { "App $it" })).isNull()
    }

    /**
     * A marker whose payload **varies per row**, which is the shape that defeats grouping by the
     * token verbatim.
     *
     * `FEED_PREFIX` is `^\[[a-z_]+]` and cannot match a digit or a space, so a marker that starts
     * carrying a version or a date survives on every row — exactly the failure this check exists
     * for — while no two rows share a token. Counted verbatim the commonest group is one row and the
     * check stays green, which is **weaker than the `startsWith("[")` line it replaced**. Grouping
     * by the leading word inside the brackets is what closes it.
     */
    @Test
    fun `a marker with a payload that varies per row is still a marker`() {
        val versioned = (1..96).map { "[apk_updated 3.1.$it] App $it" }
        val dated = (1..96).map { "[2026-09-${it % 28 + 1}] App $it" }

        // Grouped under the leading word, every payload of one marker is one group.
        assertThat(survivingFeedMarker(versioned)?.count).isEqualTo(96)
        assertThat(survivingFeedMarker(versioned)?.key).isEqualTo("apk_updated")
        // And a token with no leading word at all keys to the empty string, so varying dates pool
        // rather than each counting as one.
        assertThat(survivingFeedMarker(dated)?.count).isEqualTo(96)
    }

    /**
     * A shape `FEED_PREFIX` **cannot** match, which is the whole justification for
     * [LEADING_BRACKET] being a different, wider regex.
     *
     * Without this case nothing distinguishes the two patterns — every other title in this class is
     * matched by both — so a later "share one regex, single source of truth" tidy-up would pass the
     * suite while deleting the only breakage class this check reliably catches.
     */
    @Test
    fun `the check does not share the regex it is watching`() {
        val titles = (1..96).map { "[apk-updated] App $it" }

        // A hyphen: `^\[[a-z_]+]` has no way to match it, so this is precisely the marker that
        // survives a stripping failure.
        assertThat(survivingFeedMarker(titles)?.count).isEqualTo(96)
    }

    /**
     * A real breakage happening on a night that also contains a real bracketed name.
     *
     * This is the case that pins **which** group is counted: the marker leads 95 rows and Atomy's
     * name leads one, so taking the rarest group instead of the commonest reports one row, stays
     * under the threshold and goes green in the middle of the failure. Without a population whose
     * groups have *different* sizes, `maxByOrNull` and `minByOrNull` are indistinguishable and the
     * suite passes either way.
     */
    @Test
    fun `the commonest group is the one counted, not the rarest`() {
        val titles = (1..95).map { "[apk_updated] App $it" } + "[Official] Atomy shop"

        val marker = survivingFeedMarker(titles)

        assertThat(marker?.key).isEqualTo("apk_updated")
        assertThat(marker?.count).isEqualTo(95)
    }

    /**
     * The share's boundary, from both sides, on a window wide enough for the share to govern.
     *
     * Asserting the two adjacent counts rather than one comfortable number is what pins the
     * threshold: a check that only ever saw four-against-ninety-six would pass with almost any
     * value. Below about thirty-two entries it is [MARKER_FLOOR] that governs, which the thin-night
     * case above covers.
     */
    @Test
    fun `the share is a quarter, and both sides of it are checked`() {
        fun windowOf(bracketed: Int) =
            (1..bracketed).map { "[Official] App $it" } + (1..(96 - bracketed)).map { "App $it" }

        // A quarter exactly is still a name: 24 of 96 is not "more than a quarter".
        assertThat(survivingFeedMarker(windowOf(24))).isNull()
        // One more is a marker.
        assertThat(survivingFeedMarker(windowOf(25))?.count).isEqualTo(25)
    }

    /**
     * Two publishers bracketing their own names must not add up to a marker.
     *
     * This fixes *what* is being counted. Counting bracketed titles rather than grouping them would
     * read twelve here and cry marker — and the distinction this function exists for is that a
     * marker is **one** token group on many rows, not many groups on many rows.
     */
    @Test
    fun `different tokens do not pool into one marker`() {
        val titles = (1..4).map { "[Official] App $it" } +
            (1..4).map { "[Beta] App $it" } +
            (1..4).map { "[Mod] App $it" } +
            (1..8).map { "App $it" }

        assertThat(titles.count { it.startsWith("[") }).isEqualTo(12)
        assertThat(survivingFeedMarker(titles)).isNull()
    }

    @Test
    fun `an empty window is not a marker, because it is a different failure`() {
        // "The feed is empty" is checked by `MIN_FEED_ITEMS` in the canary, and answering it here
        // too would give two lines about one fact. There is no guard behind this: with no titles
        // there are no groups, so the answer falls out of the grouping.
        assertThat(survivingFeedMarker(emptyList())).isNull()
    }

    private companion object {
        /**
         * Four **real** app names, measured 03/09/2026: one publisher, all four brackets its own
         * name, and the first of them is the entry that reddened the nightly.
         */
        val ATOMY = listOf(
            "[Official] Atomy shop",
            "[Official] Atomy Mobile",
            "[Official] CH.ATOMY",
            "[Official] Atomy Ticket",
        )
    }
}

package com.multistore.core.common.identity

import com.google.common.truth.Truth.assertThat
import com.multistore.core.model.MatchMethod
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Cross-store matching, and above all the cases where it must **not** fire.
 *
 * The tests that count here are the negative ones: a missed merge leaves two rows where one would
 * do, a wrong merge downloads another app's APK.
 */
@DisplayName("Cross-store identity")
class IdentityMatcherTest {

    private fun signals(
        packageName: String? = null,
        title: String,
        developer: String? = null,
    ) = IdentitySignals(packageName = packageName, title = title, developer = developer)

    // --- The package veto --------------------------------------------------------------------

    @Test
    @DisplayName("same packageName: it is the same app, and there is nothing else to discuss")
    fun samePackageNameIsCertain() {
        val match = IdentityMatcher.compare(
            signals(packageName = "org.telegram.messenger", title = "Telegram"),
            signals(packageName = "org.telegram.messenger", title = "Telegram Messenger MOD APK"),
        )

        assertThat(match.confidence).isEqualTo(1.0f)
        assertThat(match.method).isEqualTo(MatchMethod.PACKAGE_NAME)
        assertThat(match.merges).isTrue()
    }

    @Test
    @DisplayName("different packageNames: veto, however well everything else agrees")
    fun differentPackageNamesAreVetoed() {
        // **Measured, not invented.** uptodown redistributes Telegram as
        // `org.telegram.messenger.web`; apkcombo publishes `org.telegram.messenger`. Identical
        // title, same developer, same icon: every signal says "same app", and the package says
        // no. The package is right.
        val match = IdentityMatcher.compare(
            signals(
                packageName = "org.telegram.messenger",
                title = "Telegram",
                developer = "Telegram FZ-LLC",
            ),
            signals(
                packageName = "org.telegram.messenger.web",
                title = "Telegram",
                developer = "Telegram FZ-LLC",
            ),
        )

        assertThat(match.confidence).isEqualTo(0.0f)
        assertThat(match.merges).isFalse()
        assertThat(match.isCandidate).isFalse()
        // The method stays `PACKAGE_NAME` even at zero confidence: it says *who decided*, and
        // that is information — "two different packages" is not "I do not know".
        assertThat(match.method).isEqualTo(MatchMethod.PACKAGE_NAME)
    }

    // --- Title and developer -----------------------------------------------------------------

    @Test
    @DisplayName("identical title and developer, no package: merges at 0.90")
    fun sameTitleAndDeveloperMerges() {
        val match = IdentityMatcher.compare(
            signals(title = "AntennaPod", developer = "AntennaPod Contributors"),
            signals(title = "AntennaPod MOD APK v3.5.0", developer = "AntennaPod Contributors"),
        )

        assertThat(match.confidence).isEqualTo(0.90f)
        assertThat(match.method).isEqualTo(MatchMethod.TITLE_DEV)
        assertThat(match.merges).isTrue()
    }

    @Test
    @DisplayName("identical title but unknown developer: possible, never automatic")
    fun unknownDeveloperNeverMerges() {
        // The case of apkmody and uptodown, which in **search results** publish neither the
        // package nor the publisher. They stay two rows, and the detail screen will offer them
        // as a possible match: search shows what it can demonstrate.
        val match = IdentityMatcher.compare(
            signals(title = "Spotify"),
            signals(title = "Spotify MOD APK"),
        )

        assertThat(match.confidence).isEqualTo(0.80f)
        assertThat(match.merges).isFalse()
        assertThat(match.isCandidate).isTrue()
    }

    @Test
    @DisplayName("one extra word in the title: offered, not merged")
    fun oneExtraWordIsOnlyACandidate() {
        // "Spotify" on uptodown, "Spotify Premium" on apkmody: `premium` is not noise to strip
        // — on a store redistributing modified builds it may genuinely be something else. Half
        // the words in common is [IdentityMatcher.MIN_SIMILARITY], the bottom of the band: shown
        // as a possibility, not merged.
        val match = IdentityMatcher.compare(
            signals(title = "Spotify"),
            signals(title = "Spotify Premium APK"),
        )

        assertThat(match.confidence).isEqualTo(0.50f)
        assertThat(match.merges).isFalse()
        assertThat(match.isCandidate).isTrue()
    }

    @Test
    @DisplayName("same title, different developers: suspicion, not a merge")
    fun differentDeveloperStaysLow() {
        val match = IdentityMatcher.compare(
            signals(title = "Minecraft", developer = "Mojang"),
            signals(title = "Minecraft", developer = "ModderX Team"),
        )

        assertThat(match.confidence).isEqualTo(0.50f)
        assertThat(match.merges).isFalse()
        assertThat(match.isCandidate).isTrue()
    }

    @Test
    @DisplayName("Telegram and Telegram X are not the same app")
    fun siblingsDoNotMerge() {
        // **The defect this test actually found.** Taking the maximum of Jaccard and
        // Jaro-Winkler over the space-stripped string, "telegram" and "telegramx" scored `0.977`
        // — the common-prefix bonus — which in the "same developer" band became `0.854`: four
        // thousandths above the merge threshold. Two different apps, silently merged. Now one
        // extra word changes the word count, the Jaro-Winkler branch does not apply, and
        // Jaccard's `0.5` stands.
        val match = IdentityMatcher.compare(
            signals(title = "Telegram", developer = "Telegram FZ-LLC"),
            signals(title = "Telegram X", developer = "Telegram FZ-LLC"),
        )

        assertThat(match.merges).isFalse()
        // Not merging does not mean ignoring: the pair is offered to the user, who will
        // sometimes say yes — on another store "X" really is just a marketing suffix.
        assertThat(match.isCandidate).isTrue()
    }

    @Test
    @DisplayName("a typo does not make two apps, but only at equal word counts")
    fun aTypoStillMerges() {
        val match = IdentityMatcher.compare(
            signals(title = "Nova Launcher", developer = "TeslaCoil Software"),
            signals(title = "Nova Launchor", developer = "TeslaCoil Software"),
        )

        // Same two words, one letter different: Jaro-Winkler gets a say, and says they are the
        // same thing. It is the only case where it gets a say.
        assertThat(match.merges).isTrue()
    }

    @Test
    @DisplayName("Firefox and Firefox Beta are not the same app")
    fun channelsDoNotMerge() {
        val match = IdentityMatcher.compare(
            signals(title = "Firefox", developer = "Mozilla"),
            signals(title = "Firefox Beta", developer = "Mozilla"),
        )

        assertThat(match.merges).isFalse()
    }

    @Test
    @DisplayName("spacing does not make two apps: DuckDuckGo and Duck Duck Go")
    fun spacingDoesNotSplitAnApp() {
        // Here Jaccard is zero — the tokens are disjoint — and what saves the comparison is
        // Jaro-Winkler over the space-stripped string. That is why there are two measures.
        val match = IdentityMatcher.compare(
            signals(title = "DuckDuckGo Private Browser", developer = "DuckDuckGo"),
            signals(title = "Duck Duck Go Private Browser", developer = "DuckDuckGo"),
        )

        assertThat(match.merges).isTrue()
    }

    @Test
    @DisplayName("title noise does not count, and neither does word order")
    fun noiseAndWordOrderAreIgnored() {
        val match = IdentityMatcher.compare(
            signals(title = "Nova Launcher", developer = "TeslaCoil Software"),
            signals(title = "Launcher Nova (Premium Unlocked) v8.0.3", developer = "TeslaCoil Software"),
        )

        assertThat(match.merges).isTrue()
    }

    @Test
    @DisplayName("two apps with nothing in common are not matched")
    fun unrelatedAppsDoNotMatch() {
        val match = IdentityMatcher.compare(
            signals(title = "AntennaPod", developer = "AntennaPod Contributors"),
            signals(title = "Signal", developer = "Signal Foundation"),
        )

        assertThat(match.confidence).isEqualTo(0.0f)
        assertThat(match.isCandidate).isFalse()
    }

    @Test
    @DisplayName("a title that normalisation empties matches nothing")
    fun emptyNormalizedTitleMatchesNothing() {
        // `"APK"` is entirely noise: normalised it is the empty string. Two such listings would
        // be "identical" to any similarity measure, which is exactly how an aggregator ends up
        // merging everything with everything.
        val match = IdentityMatcher.compare(signals(title = "APK"), signals(title = "MOD APK"))

        assertThat(match.confidence).isEqualTo(0.0f)
    }

    @Test
    @DisplayName("only one store publishes the package: the title decides, not the package")
    fun onePackageNameFallsBackToTitle() {
        val match = IdentityMatcher.compare(
            signals(packageName = "com.duckduckgo.mobile.android", title = "DuckDuckGo", developer = "DuckDuckGo"),
            signals(title = "DuckDuckGo", developer = "DuckDuckGo"),
        )

        assertThat(match.method).isEqualTo(MatchMethod.TITLE_DEV)
        assertThat(match.merges).isTrue()
    }

    @Test
    @DisplayName("the merge threshold is 0.85, and the user's confirmation beats everything")
    fun thresholdsAreWhatThePlanSays() {
        assertThat(IdentityMatcher.MERGE_THRESHOLD).isEqualTo(0.85f)
        assertThat(IdentityMatch.CONFIRMED.merges).isTrue()
        assertThat(IdentityMatch.CONFIRMED.method).isEqualTo(MatchMethod.USER_CONFIRMED)
    }
}

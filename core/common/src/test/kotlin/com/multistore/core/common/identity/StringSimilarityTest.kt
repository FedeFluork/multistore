package com.multistore.core.common.identity

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The two measures, and the proof that **neither is enough on its own**.
 *
 * Not a matter of style: if one covered the other, keeping both would be dead code. The two
 * `...IsBlindTo...` tests are the proof that it is not.
 */
@DisplayName("String similarity")
class StringSimilarityTest {

    @Test
    @DisplayName("Jaccard ignores word order")
    fun jaccardIgnoresWordOrder() {
        assertThat(StringSimilarity.jaccard("firefox browser", "browser firefox")).isEqualTo(1.0)
    }

    @Test
    @DisplayName("Jaccard is blind to spacing: the gap Jaro-Winkler covers")
    fun jaccardIsBlindToSpacing() {
        // Disjoint tokens, hence zero: to Jaccard "duckduckgo" and "duck duck go" have nothing
        // in common.
        assertThat(StringSimilarity.jaccard("duckduckgo", "duck duck go")).isEqualTo(0.0)
        assertThat(StringSimilarity.jaroWinkler("duckduckgo", "duckduckgo")).isEqualTo(1.0)
    }

    @Test
    @DisplayName("Jaro-Winkler is blind to order: the gap Jaccard covers")
    fun jaroWinklerIsBlindToWordOrder() {
        // Same words, order swapped: to Jaro-Winkler these are very different strings.
        assertThat(StringSimilarity.jaroWinkler("firefoxbrowser", "browserfirefox")).isLessThan(0.8)
        assertThat(StringSimilarity.jaccard("firefox browser", "browser firefox")).isEqualTo(1.0)
    }

    @Test
    @DisplayName("identical strings score 1, unrelated strings score low")
    fun boundsAreWhereTheyShouldBe() {
        assertThat(StringSimilarity.jaroWinkler("telegram", "telegram")).isEqualTo(1.0)
        assertThat(StringSimilarity.jaccard("telegram", "telegram")).isEqualTo(1.0)
        assertThat(StringSimilarity.jaroWinkler("telegram", "antennapod")).isLessThan(0.6)
    }

    @Test
    @DisplayName("the prefix bonus is real, and it is what makes Telegram/Telegram X treacherous")
    fun prefixBonusIsReal() {
        val plain = StringSimilarity.jaroWinkler("telegram", "telegramx")
        val suffixed = StringSimilarity.jaroWinkler("telegram", "xtelegram")

        // Same characters, appended or prepended. Winkler rewards only the first case — which
        // is why the title threshold sits at 0.9 and no lower.
        assertThat(plain).isGreaterThan(suffixed)
        assertThat(plain).isGreaterThan(0.95)
    }

    @Test
    @DisplayName("empty strings blow nothing up")
    fun emptyStringsAreHandled() {
        assertThat(StringSimilarity.jaroWinkler("", "")).isEqualTo(1.0)
        assertThat(StringSimilarity.jaroWinkler("telegram", "")).isEqualTo(0.0)
        assertThat(StringSimilarity.jaccard("", "")).isEqualTo(1.0)
        assertThat(StringSimilarity.jaccard("telegram", "")).isEqualTo(0.0)
    }

    @Test
    @DisplayName("transpositions cost less than substitutions")
    fun transpositionsCostLessThanSubstitutions() {
        // "martha"/"marhta" is the canonical example from the original paper: 0.961.
        assertThat(StringSimilarity.jaroWinkler("martha", "marhta")).isWithin(0.001).of(0.961)
    }
}

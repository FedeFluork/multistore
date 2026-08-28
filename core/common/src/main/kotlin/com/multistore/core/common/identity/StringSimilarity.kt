package com.multistore.core.common.identity

/**
 * Two string-similarity measures, and why **both** are needed.
 *
 * They answer the two mistakes stores make with titles, and neither measure covers the other:
 *
 *  - **extra or missing words** — `"Firefox Browser"` against `"Firefox"`, `"Telegram Messenger"`
 *    against `"Telegram"`. Here what counts is the set of words, not the order nor the
 *    character-by-character distance: that is [jaccard] over tokens.
 *  - **the same word written slightly differently** — `"WhatsApp"` against `"Whats App"`,
 *    `"DuckDuckGo"` against `"Duck Duck Go"`. Here the tokens are **disjoint** and Jaccard gives
 *    zero, while the space-stripped string is identical: that is [jaroWinkler].
 *
 * Neither knows anything about apps: they take already-normalised strings and return a number.
 * Deciding what to do with it is [IdentityMatcher]'s job.
 */
object StringSimilarity {

    /**
     * Similarity between the two **sets** of words: `|A ∩ B| / |A ∪ B|`.
     *
     * Sets and not lists: `"clash of clans"` and `"clans of clash"` score 1, and that is
     * intended — stores reorder titles more often than they translate them.
     */
    fun jaccard(a: String, b: String): Double {
        val left = a.split(' ').filter { it.isNotEmpty() }.toSet()
        val right = b.split(' ').filter { it.isNotEmpty() }.toSet()
        if (left.isEmpty() && right.isEmpty()) return 1.0
        if (left.isEmpty() || right.isEmpty()) return 0.0
        val intersection = left.count { it in right }
        return intersection.toDouble() / (left.size + right.size - intersection)
    }

    /**
     * Jaro-Winkler: character-by-character distance, with a bonus for the common prefix.
     *
     * The prefix bonus is why Winkler is chosen over bare Jaro: app titles almost always differ
     * at the **end** (`"Firefox"` / `"Firefox Beta"`, `"Telegram"` / `"Telegram X"`), so two
     * names starting alike are more often the same thing than two ending alike.
     *
     * It is also why it **is not enough on its own**: that same bonus makes `"Telegram"` and
     * `"Telegram X"` look alike, and they are two different apps. Callers combine it with more.
     */
    fun jaroWinkler(a: String, b: String): Double {
        val jaro = jaro(a, b)
        if (jaro < WINKLER_FLOOR) return jaro
        var prefix = 0
        while (prefix < MAX_PREFIX && prefix < a.length && prefix < b.length && a[prefix] == b[prefix]) {
            prefix++
        }
        return jaro + prefix * WINKLER_SCALE * (1.0 - jaro)
    }

    private fun jaro(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0

        // The window within which two characters can still count as "the same, shifted".
        // The `- 1` is in the original definition; on short strings it can go below zero, and
        // then no character matches except in the same position.
        val window = (maxOf(a.length, b.length) / 2 - 1).coerceAtLeast(0)
        val matchedA = BooleanArray(a.length)
        val matchedB = BooleanArray(b.length)

        var matches = 0
        for (i in a.indices) {
            val from = (i - window).coerceAtLeast(0)
            val to = (i + window + 1).coerceAtMost(b.length)
            for (j in from until to) {
                if (matchedB[j] || a[i] != b[j]) continue
                matchedA[i] = true
                matchedB[j] = true
                matches++
                break
            }
        }
        if (matches == 0) return 0.0

        // Transpositions: characters that match but in a different order, counted in pairs.
        var transpositions = 0
        var k = 0
        for (i in a.indices) {
            if (!matchedA[i]) continue
            while (!matchedB[k]) k++
            if (a[i] != b[k]) transpositions++
            k++
        }

        val m = matches.toDouble()
        return (m / a.length + m / b.length + (m - transpositions / 2.0) / m) / 3.0
    }

    /** Below this threshold the prefix bonus does not apply: that is Winkler's definition. */
    private const val WINKLER_FLOOR = 0.7

    private const val WINKLER_SCALE = 0.1
    private const val MAX_PREFIX = 4
}

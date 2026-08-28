package com.multistore.core.common.identity

import com.multistore.core.common.text.TextNormalizer
import com.multistore.core.model.MatchMethod
import com.multistore.core.model.StoreListingSummary

/**
 * What can be read about an app from a store, reduced to what identifies it.
 *
 * Not a lazily impoverished [StoreListingSummary]: it is the boundary that keeps the matcher in
 * `:core:common` — testable on the JVM without a database or a network — and at the same time the
 * way to tell callers **which** fields actually count. The rest of a listing — rating, size,
 * download count — plays no part in a judgement of identity, and having it to hand would invite
 * using it.
 */
data class IdentitySignals(
    val packageName: String?,
    val title: String,
    val developer: String?,
) {
    companion object {
        fun of(summary: StoreListingSummary): IdentitySignals = IdentitySignals(
            packageName = summary.packageName,
            title = summary.title,
            developer = summary.developer,
        )
    }
}

/**
 * How far two listings are the same app, and on what basis.
 *
 * [method] says **which signal decided**, including when the answer is no: a `PACKAGE_NAME` with
 * confidence `0` means "the two packages differ", which is information, not the absence of it.
 */
data class IdentityMatch(
    val confidence: Float,
    val method: MatchMethod,
) {
    /** They merge without asking anyone anything. */
    val merges: Boolean get() = confidence >= IdentityMatcher.MERGE_THRESHOLD

    /** They do not merge, but are worth showing as a "possible match". */
    val isCandidate: Boolean
        get() = !merges && confidence >= IdentityMatcher.CANDIDATE_THRESHOLD

    companion object {
        /** The user said they are the same app. No heuristic beats a person. */
        val CONFIRMED: IdentityMatch = IdentityMatch(1.0f, MatchMethod.USER_CONFIRMED)
    }
}

/**
 * Cross-store identity matching, the hard problem of aggregating nine stores.
 *
 * The ladder, in the order it is applied:
 *
 *  1. **same `packageName`** → `1.0`. The only *demonstrated* match.
 *  2. **different `packageName`s** → `0.0`, and **no other signal can raise it**. A veto, not a
 *     low score, and it is needed immediately: uptodown redistributes Telegram as
 *     `org.telegram.messenger.web`, apkcombo as `org.telegram.messenger`. Same title, same icon,
 *     same developer, **two different packages** — and installing one instead of the other is
 *     exactly the worst possible failure.
 *  3. **at least one of the two does not publish a `packageName`** → normalised title and
 *     developer. [similarity] decides how alike the titles are, the developer band decides where
 *     that number lands: `0.90` — above the merge threshold — is reached only when title **and**
 *     developer agree.
 *
 * **The merge threshold is `0.85` and is not negotiable**: below it a listing does not join the
 * group but a "possible match" section the user can confirm or reject. A wrong merge must be made
 * impossible by construction, not merely improbable.
 *
 * ### Why the title cut-off is `0.5` and not `0.9`
 *
 * A `0.9` cut-off on *title* similarity would leave the "possible match" section **empty**: with
 * the package veto on one side and a `0.9` cut on the other, almost nothing lands in the
 * `0.5–0.85` band. The cases that must land there are real and frequent — `Telegram` against
 * `Telegram X`, `Firefox` against `Firefox Beta`, `Spotify` against `Spotify Premium`. Those are
 * pairs that must **not** be merged and must **be shown**, because sometimes they are the same
 * app and sometimes not, and only the person looking can tell.
 *
 * ### What is not here
 *
 * A **perceptual icon hash** as a tiebreaker, and hints from the remote index. Neither is
 * implemented. The icon hash would cost, for a search across nine stores, up to a hundred images
 * downloaded and decoded **before** the first row could be shown — and decoding is not even
 * expressible here, where there is no Android. Writing the branch now would leave a parameter in
 * `compare` that no configuration populates, and a branch no configuration walks is a branch
 * nobody tests.
 */
object IdentityMatcher {

    /** Above this confidence two listings merge on their own. */
    const val MERGE_THRESHOLD: Float = 0.85f

    /** Below this it is not even shown as a possibility: it would be noise. */
    const val CANDIDATE_THRESHOLD: Float = 0.5f

    /**
     * Below this title similarity the two listings have nothing to say to each other.
     *
     * `0.5` is the point where two titles share **half** their words: `Telegram` and `Telegram X`,
     * `Firefox` and `Firefox Beta`. Pairs to show and not to merge, and indeed they land at the
     * bottom of their band.
     */
    const val MIN_SIMILARITY: Double = 0.5

    fun compare(a: StoreListingSummary, b: StoreListingSummary): IdentityMatch =
        compare(IdentitySignals.of(a), IdentitySignals.of(b))

    fun compare(a: IdentitySignals, b: IdentitySignals): IdentityMatch {
        val packageA = a.packageName?.trim()?.takeIf { it.isNotEmpty() }
        val packageB = b.packageName?.trim()?.takeIf { it.isNotEmpty() }

        // The veto. When **both** declare a package, that is the answer: matching means the
        // same app, differing means not, and the title has no say. It is the only rung of the
        // ladder that also decides in the negative.
        if (packageA != null && packageB != null) {
            val same = packageA == packageB
            return IdentityMatch(if (same) 1.0f else 0.0f, MatchMethod.PACKAGE_NAME)
        }

        val titleA = TextNormalizer.normalizeTitle(a.title)
        val titleB = TextNormalizer.normalizeTitle(b.title)
        if (titleA.isEmpty() || titleB.isEmpty()) return NO_TITLE_MATCH

        val titleSimilarity = similarity(titleA, titleB)
        if (titleSimilarity < MIN_SIMILARITY) return NO_TITLE_MATCH

        val developerA = a.developer?.let(TextNormalizer::normalizeTitle)?.takeIf { it.isNotEmpty() }
        val developerB = b.developer?.let(TextNormalizer::normalizeTitle)?.takeIf { it.isNotEmpty() }

        val band = when {
            developerA == null || developerB == null -> Band.UNKNOWN_DEVELOPER
            similarity(developerA, developerB) >= DEVELOPER_FLOOR -> Band.SAME_DEVELOPER
            else -> Band.DIFFERENT_DEVELOPER
        }
        return IdentityMatch(band.scale(titleSimilarity), MatchMethod.TITLE_DEV)
    }

    /**
     * How alike two **already normalised** titles are, with three rules instead of one.
     *
     * The first version took the maximum of Jaccard over tokens and Jaro-Winkler over the
     * space-stripped string, and **merged `Telegram` with `Telegram X`**: Jaro-Winkler rewards
     * the common prefix, `0.977`, which multiplied by the "same developer" band gave `0.854` —
     * four thousandths above the merge threshold. Two different apps, silently joined. The test
     * that caught it is still there.
     *
     * The three rules, and which store mistake each answers:
     *
     *  1. **[StringSimilarity.jaccard] over tokens** — extra or missing words, different order:
     *     `Firefox Browser` against `Browser Firefox`. The base measure, and the only one that
     *     costs a whole word when a whole word is added — which is why `Telegram` and
     *     `Telegram X` stop at `0.5`.
     *  2. **Same string, spaced differently** — `DuckDuckGo` against `Duck Duck Go`. Here the
     *     tokens are **disjoint** and Jaccard is zero, while with the spaces removed the two
     *     strings are **identical**. The condition is exact equality, not similarity: "the same
     *     letters, split differently" is a fact, not an estimate.
     *  3. **Jaro-Winkler over the space-stripped string, but only from `0.9` up and only at equal
     *     word counts** — typos and endings. The two conditions together are what keeps the
     *     `Telegram X` case out: one extra word changes the token count, and the branch does not
     *     apply at all.
     */
    fun similarity(normalizedA: String, normalizedB: String): Double {
        val jaccard = StringSimilarity.jaccard(normalizedA, normalizedB)

        val concatA = normalizedA.replace(" ", "")
        val concatB = normalizedB.replace(" ", "")
        if (concatA == concatB) return 1.0

        // A normalised title has single spaces and none at the edges: counting spaces is
        // counting words minus one, and comparing them is comparing word counts.
        if (normalizedA.count { it == ' ' } != normalizedB.count { it == ' ' }) return jaccard

        val fuzzy = StringSimilarity.jaroWinkler(concatA, concatB)
        return if (fuzzy >= TYPO_FLOOR) maxOf(jaccard, fuzzy) else jaccard
    }

    /**
     * The confidence band, decided by what is known about the developer.
     *
     * The three bands do not overlap, and that is the point: with an unknown developer the
     * highest reachable value is `0.80`, i.e. **below** the merge threshold. Two stores
     * publishing the same title and nothing else never merge on their own — they ask.
     */
    private enum class Band(val low: Float, val high: Float) {
        /** Title and developer agree: as close to proof as this gets. */
        SAME_DEVELOPER(0.50f, 0.90f),

        /**
         * At least one of the two stores does not say who wrote it.
         *
         * The ceiling is `0.80`, i.e. **below the merge threshold**, and that is not a tuning
         * choice: two stores publishing the same title and nothing else have produced no proof.
         * This is the case for apkmody and uptodown in search results, and the reason their match
         * is confirmed by the user on the listing rather than by the aggregator.
         */
        UNKNOWN_DEVELOPER(0.50f, 0.80f),

        /**
         * Same title, different developers.
         *
         * Not a veto like differing packages, and it must not be: stores write the same publisher
         * in ways that do not resemble each other ("Telegram FZ-LLC", "Telegram Messenger LLP"),
         * and on sites redistributing modified builds the field is often the name of whoever made
         * the modification. But it remains the case where a wrong merge is likeliest, and the
         * band says so: at most `0.50`, i.e. always to be confirmed — and with a title that
         * already does not agree it drops below [CANDIDATE_THRESHOLD] and is not even offered.
         */
        DIFFERENT_DEVELOPER(0.30f, 0.50f),
        ;

        /** From title similarity in `[MIN_SIMILARITY, 1]` to confidence in `[low, high]`. */
        fun scale(titleSimilarity: Double): Float {
            val span = ((titleSimilarity - MIN_SIMILARITY) / (1.0 - MIN_SIMILARITY)).coerceIn(0.0, 1.0)
            return (low + (high - low) * span).toFloat()
        }
    }

    /**
     * From how high up Jaro-Winkler gets a say: typos only, not extra words.
     *
     * Half the defence against the `Telegram` / `Telegram X` case. The other half is the equal
     * word count, checked alongside this.
     */
    private const val TYPO_FLOOR = 0.9

    /** Developer names are compared normalised too, and the threshold is high. */
    private const val DEVELOPER_FLOOR = 0.9

    private val NO_TITLE_MATCH = IdentityMatch(0.0f, MatchMethod.TITLE_DEV)
}

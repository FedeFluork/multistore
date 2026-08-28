package com.multistore.tools.index

import com.multistore.core.common.identity.AppKeys
import com.multistore.core.model.StoreListingSummary

/**
 * Reciprocal Rank Fusion, and **how little it has to fuse** on these stores.
 *
 * The plan asked for it with a sound justification: absolute download counts are not comparable
 * between stores, rankings are. The formula is the classic one — `score(app) = Σ 1 / (k + position)`
 * — and it rewards whatever appears **in more than one ranking**.
 *
 * The first measurement looked at how many apps appeared in more than one ranking, comparing titles
 * with a permissive criterion (the shorter title's tokens contained in the longer): out of
 * twenty-seven distinct apps, **three** — CapCut, Spotify, YouTube. Little, but something.
 *
 * The second measurement came from **running the pipeline**, and it is the one that counts: the
 * published entries are 22, that is 10 + 12, that is **no fusion at all**. The reason is the identity
 * used here, which is the app's — `AppKeys`, the same one that aggregates search results — and which
 * compares normalised title and developer:
 *
 * ```
 * Spotify  vs  Spotify Pro Mod APK  ->  different keys
 * CapCut   vs  CapCut - Video Editor -> different keys
 * YouTube  vs  YouTube Premium      ->  different keys
 * ```
 *
 * This is not a defect to fix by widening the comparison, and that is why it is written here: a wrong
 * merge has to be impossible by construction, not merely improbable, and none of the three surfaces
 * publishes the `packageName` that would correct the inference. A more permissive criterion in the
 * pipeline would publish as "one app" two listings the app itself, on the device, would show
 * separately — and it would publish it **signed**.
 *
 * So, today: **RRF is a rank-weighted interleaving**, and its `sources` all equal 1. It stays because
 * the day a source publishes the package — apkcombo already does, but for new releases and not for a
 * ranking — the fusion will begin to fuse without anybody having to rewrite anything, and because the
 * ordering it produces is the right one anyway: it alternates the lists respecting rank instead of
 * concatenating them.
 *
 * `k` = 60 is the value from the original publication (Cormack, Clarke, Buettcher 2009) and the one
 * everybody uses. Tuning it would require a reference set — "which really are the twenty most popular
 * apps" — that we do not have and cannot build. With three lists and one overlap in nine, `k` does
 * not change the order anyway: it only shifts how far ahead of the others the three shared apps sit.
 */
internal object Fusion {

    private const val K = 60.0

    /**
     * A fused entry: the app, and **how many rankings it comes from**.
     *
     * `sources` ends up in the document because it is the only part of the score that survives
     * serialisation, and it is what lets whoever reads `index.json` understand why the order is what
     * it is.
     */
    data class Fused(val app: StoreListingSummary, val sources: Int, val score: Double)

    /**
     * @param rankings one list per store, already in the order the store declared.
     */
    fun fuse(rankings: List<List<StoreListingSummary>>): List<Fused> {
        val byKey = LinkedHashMap<String, MutableList<Pair<StoreListingSummary, Int>>>()
        rankings.forEach { ranking ->
            // Deduplication **within** a single ranking comes before fusion. Neither of the two
            // current sources needs it — uptodown and apkmody rank apps, not files — and it stays for
            // the case that made it get written: apkmirror's "Popular In Last 30 Days" widget ranks
            // **releases**, and its ten rows are CapCut four times and Play Store three. Without this,
            // that single source would give CapCut four contributions and the score would say "four
            // stores rank it highly". That store is not among the sources today (see `Adapters`), but
            // the defect this line closes belongs to the "ranking of files" format, not to that site.
            val seen = mutableSetOf<String>()
            ranking.forEachIndexed { position, app ->
                val key = keyOf(app)
                if (!seen.add(key)) return@forEachIndexed
                byKey.getOrPut(key) { mutableListOf() } += app to position
            }
        }

        return byKey.values
            .map { occurrences ->
                val score = occurrences.sumOf { (_, position) -> 1.0 / (K + position + 1) }
                Fused(
                    // Among several listings of the same app the **best placed** is kept: it is the
                    // source that knows that app best, and it carries its own ref.
                    app = occurrences.minByOrNull { it.second }!!.first,
                    sources = occurrences.size,
                    score = score,
                )
            }
            .sortedWith(compareByDescending<Fused> { it.score }.thenBy { it.app.title })
    }

    /**
     * The identity with which two rankings talk about the same app.
     *
     * `AppKeys` is the same code the app uses to aggregate search results — `pkg:{package}` where the
     * store publishes it, `sig:{digest of title + developer}` where it does not. Reusing it here is
     * not convenience: if the pipeline fused on a criterion of its own, it would publish as "one app"
     * two listings the app would then show separately, or vice versa.
     *
     * The limitation, worth knowing: none of the three rankings publishes the `packageName`, so one
     * always ends up in the `sig:` form. On the three shared apps measured it works because the
     * normalised titles match; on an app two stores spell differently it would not, and the result
     * would be two entries instead of one — that is, the prudent error, not the one that fuses
     * different things.
     */
    private fun keyOf(app: StoreListingSummary): String =
        app.packageName?.let { "pkg:$it" } ?: AppKeys.inferred(app.title, app.developer)
}

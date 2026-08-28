package com.multistore.core.common.identity

import com.multistore.core.model.AggregatedApp
import com.multistore.core.model.AggregatedListing
import com.multistore.core.model.MatchMethod
import com.multistore.core.model.ResultOrigin
import com.multistore.core.model.StoreId
import com.multistore.core.model.StoreListingSummary

/** What **one** store answered: its listings, in the order it put them in. */
data class StoreResults(
    val storeId: StoreId,
    val origin: ResultOrigin,
    val items: List<StoreListingSummary>,
)

/**
 * From nine result lists to one, without inventing matches.
 *
 * ### How things merge: [IdentityMatcher], and never below `0.85`
 *
 * Every listing looks for an already-open group it has a **merging** match with. Failing that it
 * opens its own. What stays below the threshold neither disappears nor blends in: it remains its
 * own row, and it is the detail screen that offers it as a "possible match". A wrong merge means
 * offering another app's APK.
 *
 * ### How things are ordered: **Reciprocal Rank Fusion**
 *
 * Store scores are not comparable — some publish a rating, some a download count, some nothing —
 * while **positions** are: being the first result means the same thing everywhere. RRF sums
 * `1/(k + position)` across all a group's listings, so an app three stores put at the top beats
 * one a single store puts first.
 *
 * **The declared cost:** every time a store answers the order is recomputed, so rows can move
 * while results stream in. The alternative — appending new arrivals at the bottom — is worse and
 * measurably so: on "spotify" F-Droid answers first, from the local index, with marginal matches,
 * and would nail the real app below them forever. A row that moves is an annoyance; the right row
 * out of reach is a defect. What a group never does is lose members or vanish: once opened, it
 * can only grow.
 */
object AppAggregator {

    /**
     * The RRF constant. `60` is the value from the original paper, and it is not arbitrary: it
     * damps the weight of the very top positions just enough that second place on three stores
     * beats first place on one.
     */
    const val RRF_K: Int = 60

    fun aggregate(perStore: List<StoreResults>): List<AggregatedApp> {
        val entries = perStore.flatMap { store ->
            store.items.mapIndexed { rank, summary ->
                Entry(summary = summary, origin = store.origin, rank = rank)
            }
        }
        // Seeding order decides who represents the group: whoever their own store placed
        // highest wins, ties going to the store with the lowest ordinal. Deterministic, so two
        // runs over the same data give the same list.
        val seeded = entries.sortedWith(
            compareBy({ it.rank }, { it.summary.storeId.ordinal }, { it.summary.ref.value }),
        )

        val groups = mutableListOf<MutableGroup>()
        for (entry in seeded) {
            val best = groups
                .mapNotNull { group ->
                    val match = IdentityMatcher.compare(group.seed.summary, entry.summary)
                    if (match.merges) group to match else null
                }
                .maxByOrNull { (_, match) -> match.confidence }

            if (best == null) {
                groups += MutableGroup(entry)
            } else {
                val (group, match) = best
                group.members += Member(entry, match.confidence, match.method)
            }
        }

        return groups
            .map { it.toAggregated() }
            .sortedWith(compareByDescending<Scored> { it.score }.thenBy { it.app.appKey })
            .map { it.app }
    }

    private data class Entry(
        val summary: StoreListingSummary,
        val origin: ResultOrigin,
        val rank: Int,
    )

    private data class Member(val entry: Entry, val confidence: Float, val method: MatchMethod)

    private data class Scored(val app: AggregatedApp, val score: Double)

    private class MutableGroup(val seed: Entry) {
        val members = mutableListOf(
            // The seed is the group's yardstick, so its confidence against the group is `1`:
            // not a heuristic that worked out well, but the definition.
            Member(
                entry = seed,
                confidence = 1.0f,
                method = if (seed.summary.packageName != null) {
                    MatchMethod.PACKAGE_NAME
                } else {
                    MatchMethod.TITLE_DEV
                },
            ),
        )

        fun toAggregated(): Scored {
            val ordered = members.sortedWith(
                compareByDescending<Member> { it.confidence }
                    .thenBy { it.entry.rank }
                    .thenBy { it.entry.summary.storeId.ordinal },
            )
            // The group key comes from whoever has a `packageName`: it is the only exact one,
            // and using it where present makes a search group match the row already written in
            // `store_listings` for the same package.
            val exact = ordered.firstNotNullOfOrNull { it.entry.summary.packageName }
            val appKey = AppKeys.of(exact, seed.summary.title, seed.summary.developer)
            return Scored(
                app = AggregatedApp(
                    appKey = appKey,
                    listings = ordered.map {
                        AggregatedListing(
                            summary = it.entry.summary,
                            origin = it.entry.origin,
                            confidence = it.confidence,
                            method = it.method,
                        )
                    },
                ),
                score = ordered.sumOf { 1.0 / (RRF_K + it.entry.rank + 1) },
            )
        }
    }
}

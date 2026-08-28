package com.multistore.tools.index

import com.google.common.truth.Truth.assertThat
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.model.StoreListingSummary
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Fusing the rankings, and the two cases where the result is surprising.
 */
@DisplayName("RRF fusion of the rankings")
class FusionTest {

    @Test
    @DisplayName("an app in two rankings beats the first of a single one")
    fun sharedAppWins() {
        val first = listOf(app(StoreId.UPTODOWN, "First only"), app(StoreId.UPTODOWN, "Condivisa"))
        val second = listOf(app(StoreId.APKMODY, "Second only"), app(StoreId.APKMODY, "Condivisa"))

        val fused = Fusion.fuse(listOf(first, second))

        // It is the only thing RRF does and an interleaving does not: two second places beat one
        // first place. The `packageName` is present here — see the other test for what happens when
        // it is missing.
        assertThat(fused.first().app.title).isEqualTo("Condivisa")
        assertThat(fused.first().sources).isEqualTo(2)
        assertThat(fused.drop(1).map { it.sources }).containsExactly(1, 1)
    }

    @Test
    @DisplayName("with no packageName, Spotify and Spotify Pro stay two apps")
    fun titlesWithStoreSuffixesDoNotFuse() {
        // **It is what actually happens** on the two real rankings, and the reason the produced
        // `index.json` has 22 entries and none with `sources` greater than 1: apkmody writes "Spotify
        // Pro Mod APK", uptodown writes "Spotify", and `AppKeys` compares normalised titles because
        // neither publishes the package.
        //
        // The test exists to pin the choice down, not to complain about it: a more permissive
        // comparison would also fuse different things, and it would publish that **signed**. A wrong
        // merge has to be impossible by construction.
        val fused = Fusion.fuse(
            listOf(
                listOf(app(StoreId.UPTODOWN, "Spotify", packageName = null)),
                listOf(app(StoreId.APKMODY, "Spotify Pro", packageName = null)),
            ),
        )
        assertThat(fused).hasSize(2)
        assertThat(fused.map { it.sources }).containsExactly(1, 1)
    }

    @Test
    @DisplayName("a ranking of files does not give the same app four votes")
    fun duplicatesInsideOneRankingCountOnce() {
        // The case is apkmirror's "Popular In Last 30 Days" widget: ten rows that are five apps, with
        // CapCut four times. Without internal deduplication, that single source would give it four
        // contributions.
        val releases = listOf(
            app(StoreId.APKMIRROR, "CapCut", packageName = "com.lemon.lvoverseas"),
            app(StoreId.APKMIRROR, "CapCut", packageName = "com.lemon.lvoverseas"),
            app(StoreId.APKMIRROR, "CapCut", packageName = "com.lemon.lvoverseas"),
            app(StoreId.APKMIRROR, "Another", packageName = "com.other"),
        )
        val fused = Fusion.fuse(listOf(releases))

        assertThat(fused).hasSize(2)
        assertThat(fused.single { it.app.title == "CapCut" }.sources).isEqualTo(1)
        // And it stays first: the place that counts is the best one, not the last one seen.
        assertThat(fused.first().app.title).isEqualTo("CapCut")
    }

    @Test
    @DisplayName("the packageName wins over the title when present")
    fun packageNameIsTheStrongerIdentity() {
        val fused = Fusion.fuse(
            listOf(
                listOf(app(StoreId.UPTODOWN, "Telegram", packageName = "org.telegram.messenger")),
                listOf(app(StoreId.APKCOMBO, "Telegram Messenger", packageName = "org.telegram.messenger")),
            ),
        )
        // Two different titles, one package: it is one app. It is the case that will make the fusion
        // useful the day a ranking publishes the package.
        assertThat(fused).hasSize(1)
        assertThat(fused.single().sources).isEqualTo(2)
    }

    private fun app(storeId: StoreId, title: String, packageName: String? = null) =
        StoreListingSummary(
            storeId = storeId,
            ref = StoreAppRef(title.lowercase().replace(' ', '-')),
            title = title,
            packageName = packageName,
        )
}

package com.multistore.core.common.identity

import com.google.common.truth.Truth.assertThat
import com.multistore.core.model.ContentKind
import com.multistore.core.model.ResultOrigin
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.model.StoreListingSummary
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Merging several stores' results into one list.
 *
 * The cases are modelled on the real stores: F-Droid and apkcombo publish the `packageName` in
 * search results, apkmody and uptodown do not.
 */
@DisplayName("Result aggregation")
class AppAggregatorTest {

    private fun summary(
        store: StoreId,
        ref: String,
        title: String,
        packageName: String? = null,
        developer: String? = null,
    ) = StoreListingSummary(
        storeId = store,
        ref = StoreAppRef(ref),
        title = title,
        packageName = packageName,
        developer = developer,
    )

    private fun results(store: StoreId, vararg items: StoreListingSummary) =
        StoreResults(store, ResultOrigin.REMOTE, items.toList())

    @Test
    @DisplayName("two stores with the same package give a single row")
    fun samePackageAcrossStoresBecomesOneRow() {
        val groups = AppAggregator.aggregate(
            listOf(
                results(
                    StoreId.FDROID,
                    summary(StoreId.FDROID, "org.telegram.messenger", "Telegram", "org.telegram.messenger"),
                ),
                results(
                    StoreId.APKCOMBO,
                    summary(StoreId.APKCOMBO, "telegram/org.telegram.messenger", "Telegram", "org.telegram.messenger"),
                ),
            ),
        )

        assertThat(groups).hasSize(1)
        assertThat(groups.single().storeCount).isEqualTo(2)
        assertThat(groups.single().appKey).isEqualTo("pkg:org.telegram.messenger")
    }

    @Test
    @DisplayName("two different packages stay two rows, however identical the title")
    fun differentPackagesStayApart() {
        val groups = AppAggregator.aggregate(
            listOf(
                results(
                    StoreId.APKCOMBO,
                    summary(StoreId.APKCOMBO, "telegram", "Telegram", "org.telegram.messenger"),
                ),
                results(
                    StoreId.UPTODOWN,
                    summary(StoreId.UPTODOWN, "telegram", "Telegram", "org.telegram.messenger.web"),
                ),
            ),
        )

        // The real, measured pair: uptodown redistributes `…messenger.web`. Merging them would
        // mean offering the user a different package from the one they asked for.
        assertThat(groups).hasSize(2)
    }

    @Test
    @DisplayName("no package and no developer means no merge: two rows, not one wrong one")
    fun titleOnlyDoesNotMerge() {
        val groups = AppAggregator.aggregate(
            listOf(
                results(StoreId.APKMODY, summary(StoreId.APKMODY, "apps/spotify", "Spotify")),
                results(StoreId.UPTODOWN, summary(StoreId.UPTODOWN, "spotify", "Spotify")),
            ),
        )

        assertThat(groups).hasSize(2)
    }

    @Test
    @DisplayName("no result is lost: the total number of listings is unchanged")
    fun nothingIsDropped() {
        val perStore = listOf(
            results(
                StoreId.FDROID,
                summary(StoreId.FDROID, "a", "AntennaPod", "de.danoeh.antennapod"),
                summary(StoreId.FDROID, "b", "Signal", "org.thoughtcrime.securesms"),
            ),
            results(
                StoreId.APKCOMBO,
                summary(StoreId.APKCOMBO, "c", "AntennaPod", "de.danoeh.antennapod"),
                summary(StoreId.APKCOMBO, "d", "Element", "im.vector.app"),
            ),
        )

        val groups = AppAggregator.aggregate(perStore)

        assertThat(groups.sumOf { it.listings.size }).isEqualTo(4)
        assertThat(groups).hasSize(3)
    }

    @Test
    @DisplayName("the order is RRF: two second places beat a single first place")
    fun rankFusionPrefersAgreementAcrossStores() {
        val onlyOnce = summary(StoreId.FDROID, "solo", "Solo", "com.example.solo")
        val onTwoStores = summary(StoreId.FDROID, "shared", "Shared", "com.example.shared")

        val groups = AppAggregator.aggregate(
            listOf(
                results(StoreId.FDROID, onlyOnce, onTwoStores),
                results(
                    StoreId.APKCOMBO,
                    summary(StoreId.APKCOMBO, "x", "Other", "com.example.other"),
                    summary(StoreId.APKCOMBO, "shared", "Shared", "com.example.shared"),
                ),
            ),
        )

        // `Solo` is first on one store; `Shared` is second on two. RRF: 1/61 against 2/62.
        assertThat(groups.first().appKey).isEqualTo("pkg:com.example.shared")
    }

    @Test
    @DisplayName("the displayed row borrows icon and package from whoever publishes them")
    fun displaySummaryBorrowsWhatTheOthersPublish() {
        val withoutIcon = summary(StoreId.FDROID, "a", "AntennaPod", "de.danoeh.antennapod")
        val withIcon = summary(StoreId.APKCOMBO, "b", "AntennaPod", "de.danoeh.antennapod")
            .copy(iconUrl = "https://example.invalid/icon.png")

        val group = AppAggregator.aggregate(
            listOf(results(StoreId.FDROID, withoutIcon), results(StoreId.APKCOMBO, withIcon)),
        ).single()

        assertThat(group.primary.summary.storeId).isEqualTo(StoreId.FDROID)
        assertThat(group.displaySummary.iconUrl).isEqualTo("https://example.invalid/icon.png")
        assertThat(group.displaySummary.packageName).isEqualTo("de.danoeh.antennapod")
    }

    /**
     * Rating and rating count come from the **same** listing.
     *
     * Two independent `firstNotNullOfOrNull` calls compile, work on every single-listing group,
     * and on the first group with two produce "4.5 from 96 ratings" with the 4.5 from one store
     * and the 96 from another: a figure that exists nowhere, and that no error would report.
     */
    @Test
    @DisplayName("rating and rating count come from the same listing")
    fun ratingAndItsCountComeFromTheSameListing() {
        // The configuration that makes the difference: the **first** listing carries the
        // rating but not the count, the second carries both. Not a contrived case — none of the
        // nine stores publishes the count in search results (only three *detail* parsers read
        // it), so a group joining a search row with a listing already in the catalogue has
        // exactly this shape.
        //
        // With two independent `firstNotNullOfOrNull` calls it would read "3.9 from 12 ratings":
        // apkcombo's 3.9 and pdalife's 12, a figure that exists nowhere.
        val ratingOnly = summary(StoreId.APKCOMBO, "a", "AntennaPod", "de.danoeh.antennapod")
            .copy(rating = 3.9f)
        val both = summary(StoreId.PDALIFE, "b", "AntennaPod", "de.danoeh.antennapod")
            .copy(rating = 3.1f, ratingCount = 12)

        val group = AppAggregator.aggregate(
            listOf(results(StoreId.APKCOMBO, ratingOnly), results(StoreId.PDALIFE, both)),
        ).single()

        assertThat(group.displaySummary.rating).isEqualTo(3.9f)
        assertThat(group.displaySummary.ratingCount).isNull()
    }

    /** The kind is declared by whoever knows it: eight of nine do not publish it in listings. */
    @Test
    @DisplayName("the kind comes from whoever declares it, not from whoever answers first")
    fun contentKindComesFromWhoeverDeclaresIt() {
        val silent = summary(StoreId.FDROID, "a", "Solitario", "org.example.solitario")
        val declaring = summary(StoreId.APKMODY, "b", "Solitario", "org.example.solitario")
            .copy(contentKind = ContentKind.GAME)

        val group = AppAggregator.aggregate(
            listOf(results(StoreId.FDROID, silent), results(StoreId.APKMODY, declaring)),
        ).single()

        assertThat(group.displaySummary.contentKind).isEqualTo(ContentKind.GAME)
    }

    @Test
    @DisplayName("several listings from the same store still count as one store")
    fun severalPagesOnOneStoreAreStillOneStore() {
        val groups = AppAggregator.aggregate(
            listOf(
                results(
                    StoreId.APKMIRROR,
                    summary(StoreId.APKMIRROR, "firefox-1", "Firefox", "org.mozilla.firefox"),
                    summary(StoreId.APKMIRROR, "firefox-2", "Firefox", "org.mozilla.firefox"),
                ),
            ),
        )

        val group = groups.single()
        assertThat(group.listings).hasSize(2)
        assertThat(group.storeCount).isEqualTo(1)
    }

    @Test
    @DisplayName("same list in, same list out")
    fun aggregationIsDeterministic() {
        val perStore = listOf(
            results(
                StoreId.FDROID,
                summary(StoreId.FDROID, "a", "Alpha", "com.example.alpha"),
                summary(StoreId.FDROID, "b", "Beta", "com.example.beta"),
            ),
            results(
                StoreId.APKMIRROR,
                summary(StoreId.APKMIRROR, "c", "Beta", "com.example.beta"),
                summary(StoreId.APKMIRROR, "d", "Gamma", "com.example.gamma"),
            ),
        )

        val first = AppAggregator.aggregate(perStore).map { it.appKey }
        val second = AppAggregator.aggregate(perStore).map { it.appKey }

        assertThat(first).isEqualTo(second)
    }

    @Test
    @DisplayName("an open group never loses members when another store arrives")
    fun groupsOnlyGrow() {
        val fdroid = results(
            StoreId.FDROID,
            summary(StoreId.FDROID, "a", "AntennaPod", "de.danoeh.antennapod"),
        )
        val late = results(
            StoreId.APKMIRROR,
            summary(StoreId.APKMIRROR, "b", "AntennaPod", "de.danoeh.antennapod"),
        )

        val partial = AppAggregator.aggregate(listOf(fdroid))
        val complete = AppAggregator.aggregate(listOf(fdroid, late))

        // This is the guarantee that makes reordering during streaming acceptable: what is on
        // screen can move, but it cannot disappear.
        val before = partial.flatMap { it.listings }.map { it.ref.value }.toSet()
        val after = complete.flatMap { it.listings }.map { it.ref.value }.toSet()
        assertThat(after).containsAtLeastElementsIn(before)
    }

    /**
     * Two distinct groups can share an `appKey`, and the list must cope regardless.
     *
     * Found on the emulator as soon as an1 was added: **`IllegalArgumentException: Key
     * "sig:00dd5ba43c5daf7a47bb8d7f" was already used`** — `LazyColumn` closing the search
     * screen. Not a wrong result: the app crashing.
     *
     * The cause is that `AppKeys.inferred` derives the key from normalised title and developer,
     * so two listings with no `packageName` and no declared publisher share it **while remaining
     * at `0.80` for the matcher**, i.e. while remaining two groups. With four stores out of nine
     * publishing no package, and an1 publishing it on no page at all, the case stopped being
     * theoretical.
     *
     * The test pins both halves of the wanted behaviour: the groups stay **two** (the
     * different-package veto is untouched) and their list keys are **different**.
     */
    @Test
    @DisplayName("two groups sharing a domain key have different list keys")
    fun collidingAppKeysStillGiveDistinctListKeys() {
        // Reproducing the real case needs two listings **without** a package: with packages
        // present the key would come from the first that has one.
        val groups = AppAggregator.aggregate(
            listOf(
                results(
                    StoreId.AN1,
                    summary(StoreId.AN1, "2971-telegram", "Telegram"),
                    summary(StoreId.AN1, "9001-telegram-mod", "Telegram"),
                ),
            ),
        )

        // Two listings from the same store never merge — they are two pages, not two sources —
        // and with neither package nor developer they produce the same digest.
        assertThat(groups).hasSize(2)
        assertThat(groups.map { it.appKey }.toSet()).hasSize(1)

        // And this is the line the crash would have failed on.
        assertThat(groups.map { it.listKey }.toSet()).hasSize(2)
    }
}

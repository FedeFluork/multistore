package com.multistore.tools.index

import com.google.common.truth.Truth.assertThat
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.model.StoreListingSummary
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("The document the pipeline publishes")
class BuildIndexTest {

    @Test
    @DisplayName("new releases interleave between stores rather than concatenating")
    fun feedsAreInterleaved() {
        val long = (1..5).map { app(StoreId.PDALIFE, "pdalife-$it") }
        val short = listOf(app(StoreId.APKMIRROR, "apkmirror-1"))

        val merged = BuildIndex.interleave(listOf(long, short))

        // The defence: pdalife publishes a hundred entries and apkmirror ten. Concatenating, or
        // ordering by date, the section would be pdalife and nothing else — and the two feeds' dates
        // are not even comparable (one dates the file, the other the listing).
        assertThat(merged.map { it.title }).containsExactly(
            "pdalife-1", "apkmirror-1", "pdalife-2", "pdalife-3", "pdalife-4", "pdalife-5",
        ).inOrder()
    }

    @Test
    @DisplayName("an empty feed leaves no gaps in the interleaving")
    fun emptyFeedsAreSkipped() {
        val merged = BuildIndex.interleave(
            listOf(emptyList(), listOf(app(StoreId.APKCOMBO, "a")), emptyList()),
        )
        assertThat(merged.map { it.title }).containsExactly("a")
    }

    private fun app(storeId: StoreId, title: String) = StoreListingSummary(
        storeId = storeId,
        ref = StoreAppRef(title),
        title = title,
    )
}

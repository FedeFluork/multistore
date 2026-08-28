package com.multistore.core.data.repository

import com.google.common.truth.Truth.assertThat
import com.multistore.core.model.StoreId
import com.multistore.core.remoteconfig.IndexDocument
import com.multistore.core.remoteconfig.IndexEntry
import com.multistore.core.remoteconfig.IndexStoreState
import org.junit.Test

/**
 * From `index.json` to what the Home draws.
 *
 * Four rules, and three of them protect against faults that would only show on the device: a
 * switched-off store still appearing, a store the app does not know bringing everything else down,
 * and two entries with the same key closing the app.
 */
class RemoteIndexTranslationTest {

    private val allEnabled = StoreId.entries.toSet()

    @Test
    fun `a switched-off store's entries do not appear`() {
        val document = IndexDocument(
            schemaVersion = 1,
            popular = listOf(entry(StoreId.UPTODOWN, "a"), entry(StoreId.APKMODY, "b")),
        )

        val home = document.toHome(enabledStores = setOf(StoreId.UPTODOWN))

        // The defence: without it, the Home would offer an app from a store the user has disabled,
        // and touching it would open a listing the search would never show them.
        assertThat(home.popular.map { it.title }).containsExactly("a")
    }

    @Test
    fun `a store this version does not know drops its own entry, not the others`() {
        val document = IndexDocument(
            schemaVersion = 1,
            recent = listOf(
                IndexEntry(store = "store-from-the-future", ref = "x", title = "Future one"),
                entry(StoreId.APKCOMBO, "Note"),
            ),
        )

        val home = document.toHome(allEnabled)

        // The pipeline can publish a tenth store before the app can read it, and in that case the
        // right thing is to show the other nine.
        assertThat(home.recent.map { it.title }).containsExactly("Note")
    }

    @Test
    fun `two entries with the same store-ref pair become one`() {
        val document = IndexDocument(
            schemaVersion = 1,
            popular = listOf(entry(StoreId.UPTODOWN, "First"), entry(StoreId.UPTODOWN, "Second")),
        )

        val home = document.toHome(allEnabled)

        // It is not generic caution: it is what makes the key of the `LazyRow` drawing them true. With
        // two identical keys Compose throws `IllegalArgumentException` and the app closes — it really
        // happened with `sig:00dd5ba4…`.
        assertThat(home.popular).hasSize(1)
        assertThat(home.popular.single().title).isEqualTo("First")
    }

    @Test
    fun `an entry with no ref or no title is discarded`() {
        val document = IndexDocument(
            schemaVersion = 1,
            popular = listOf(
                IndexEntry(store = StoreId.AN1.wireName, ref = "", title = "Without ref"),
                IndexEntry(store = StoreId.AN1.wireName, ref = "x", title = ""),
                entry(StoreId.AN1, "Buona"),
            ),
        )

        assertThat(document.toHome(allEnabled).popular.map { it.title }).containsExactly("Buona")
    }

    @Test
    fun `unreachable stores arrive with the reason`() {
        val document = IndexDocument(
            schemaVersion = 1,
            stores = listOf(
                IndexStoreState(store = StoreId.AN1.wireName, reachable = false, detail = "blocked:forbidden"),
                IndexStoreState(store = StoreId.APKCOMBO.wireName, reachable = true),
            ),
        )

        val home = document.toHome(allEnabled)

        // Only those that did not answer: the "all fine" rows do not even reach the document.
        assertThat(home.unreachableStores).containsExactly(StoreId.AN1, "blocked:forbidden")
    }

    private fun entry(storeId: StoreId, title: String) = IndexEntry(
        store = storeId.wireName,
        // Deliberately the same ref as in the deduplication test: it is the pair that counts.
        ref = "ref",
        title = title,
    )
}

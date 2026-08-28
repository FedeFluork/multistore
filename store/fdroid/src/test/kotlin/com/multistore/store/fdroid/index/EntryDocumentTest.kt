package com.multistore.store.fdroid.index

import com.google.common.truth.Truth.assertThat
import com.multistore.core.model.Sha256
import com.multistore.store.fdroid.Fixtures
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("entry.json — the signed document everything else depends on")
class EntryDocumentTest {

    private val entry: EntryDocument =
        Fixtures.json.decodeFromString<EntryDocument>(Fixtures.text(Fixtures.ENTRY_JSON))

    @Test
    @DisplayName("declares the index's name, size and hash")
    fun describesTheIndex() {
        assertThat(entry.index.name).isEqualTo("/index-v2.json")
        assertThat(entry.index.size).isEqualTo(57_037_287L)
        assertThat(entry.index.numPackages).isEqualTo(4257)
        assertThat(Sha256.parseOrNull(entry.index.sha256)).isNotNull()
    }

    @Test
    @DisplayName("the diffs are exactly 10 discrete timestamps, not a continuous window")
    fun diffsAreExactlyTenDiscreteTimestamps() {
        // The number is fixed: the repository keeps ten, spaced roughly a day and a half apart.
        // Whoever syncs inside that window always finds a diff; whoever stays away more than two
        // weeks falls back on the 17.8 MB full pull, and that has to be treated as a normal case,
        // not as a fault.
        assertThat(entry.diffs).hasSize(10)
        assertThat(entry.diffs.keys.all { it.toLongOrNull() != null }).isTrue()
    }

    @Test
    @DisplayName("a diff is found only with the exact timestamp")
    fun diffLookupNeedsAnExactTimestamp() {
        val known = entry.diffs.keys.first().toLong()

        assertThat(entry.diffFrom(known)).isNotNull()
        // One millisecond off and it no longer exists. It is why the sync has to store the timestamp
        // the index served and not the time at which it happened.
        assertThat(entry.diffFrom(known + 1)).isNull()
        assertThat(entry.diffFrom(0)).isNull()
    }

    @Test
    @DisplayName("every diff is far smaller than the whole index")
    fun diffsAreWorthTheirComplexity() {
        val smallest = entry.diffs.values.minOf { it.size }
        // 761 KB against 57 MB: seventy times less. That is what justifies the existence of
        // JsonMergePatch and of the whole incremental path.
        assertThat(smallest).isLessThan(entry.index.size / 10)
    }

    @Test
    @DisplayName("maxAge is present: it is the defence against a mirror freezing the index")
    fun maxAgeIsPresent() {
        assertThat(entry.maxAge).isEqualTo(14)
    }
}

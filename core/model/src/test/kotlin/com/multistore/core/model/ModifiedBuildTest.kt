package com.multistore.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The three-state verdict, decided from the two declarations that exist.
 *
 * It is four lines of `when`, and it is worth a test of its own because getting it wrong is
 * invisible: every wrong answer is a plausible sentence next to an app, and the two mistakes it can
 * make are opposites — calling somebody's untouched upload a rework, or putting the reassuring
 * answer on a store that never says anything about the files it republishes.
 */
@DisplayName("Modified build — the verdict from two declarations")
class ModifiedBuildTest {

    @Test
    @DisplayName("a store that publishes no reworks says nothing, whatever the row says")
    fun aCleanStoreStaysSilent() {
        assertThat(ModifiedBuild.of(storeRedistributesModifiedBuilds = false, listingDeclaredModified = false))
            .isEqualTo(ModifiedBuild.NONE)
    }

    @Test
    @DisplayName("a marked row on a rework store is a declaration")
    fun aMarkedRowIsDeclared() {
        assertThat(ModifiedBuild.of(storeRedistributesModifiedBuilds = true, listingDeclaredModified = true))
            .isEqualTo(ModifiedBuild.DECLARED)
    }

    @Test
    @DisplayName("an unmarked row on a rework store is 'possible', not 'clean'")
    fun anUnmarkedRowIsPossible() {
        // The case that decides the whole design. apkmody and liteapks mark **nothing** per listing
        // — they write it only in the title — so if this answered NONE, two of the five stores that
        // redistribute reworks would show the reassuring answer on every single app they publish.
        assertThat(ModifiedBuild.of(storeRedistributesModifiedBuilds = true, listingDeclaredModified = false))
            .isEqualTo(ModifiedBuild.POSSIBLE)
    }

    @Test
    @DisplayName("the row can only raise the verdict, never lower it")
    fun theRowOnlyRaises() {
        // A row marked on a store that denies publishing reworks is a contradiction the contract
        // test catches at its source. Here the point is what happens meanwhile: the row is believed,
        // because the mistake it can make is the prudent one. Saying "clean" on the strength of a
        // capability while the row itself says otherwise is the mistake that cannot be undone by
        // the reader.
        assertThat(ModifiedBuild.of(storeRedistributesModifiedBuilds = false, listingDeclaredModified = true))
            .isEqualTo(ModifiedBuild.DECLARED)
    }

    @Test
    @DisplayName("only the silent state is unflagged")
    fun onlyNoneIsSilent() {
        // `isFlagged` is what the badge branches on, so a fourth value added later must decide
        // explicitly rather than inherit an answer from a `!=`.
        assertThat(ModifiedBuild.entries.filter { it.isFlagged })
            .containsExactly(ModifiedBuild.POSSIBLE, ModifiedBuild.DECLARED)
    }
}

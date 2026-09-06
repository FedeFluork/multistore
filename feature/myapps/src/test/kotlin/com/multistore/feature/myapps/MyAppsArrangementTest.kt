package com.multistore.feature.myapps

import com.google.common.truth.Truth.assertThat
import com.multistore.core.model.InstalledApp
import com.multistore.core.model.InstallerKind
import com.multistore.core.model.MyAppsSort
import com.multistore.core.model.StoreId
import kotlin.time.Instant
import org.junit.Test

/**
 * Searching and ordering the installed list, tested as what they are: two pure functions.
 *
 * ### Why not only through the ViewModel
 *
 * Both were `private` to `MyAppsViewModel.kt` at first, i.e. reachable only through a `StateFlow`
 * assembled from four asynchronous sources. That is a bad place to prove an ordering: an assertion
 * on the emitted list is one propagation away from the thing it is checking, and when it fails there
 * are two candidate causes. Made `internal`, they are what they look like — a list in, a list out —
 * and the injection that removes a tie-break turns exactly one assertion red.
 *
 * ### The tie-break is the part worth testing
 *
 * Three of the four criteria compare on something that repeats: two apps can both be updatable, both
 * be installed in the same second — which is what restoring a phone does to all of them — and both
 * come from the same store. Kotlin's sort is stable, so without a final comparison on the name the
 * order of the ties is **whatever the query returned**, and that is not stable across two emissions.
 * The visible symptom would be rows swapping places under the finger.
 */
class MyAppsArrangementTest {

    // --- Searching ---------------------------------------------------------------------------

    @Test
    fun `an empty query changes nothing, not even the order`() {
        val items = listOf(row("VLC"), row("Firefox"))

        assertThat(items.matching("").map { it.app.label }).containsExactly("VLC", "Firefox").inOrder()
        // Whitespace is not a query: a stray space from a keyboard suggestion would otherwise empty
        // the list and look like a bug in the search.
        assertThat(items.matching("   ")).hasSize(2)
    }

    @Test
    fun `the name matches regardless of case`() {
        val items = listOf(row("VLC"), row("Firefox"))

        assertThat(items.matching("fire").map { it.app.label }).containsExactly("Firefox")
        assertThat(items.matching("VLC").map { it.app.label }).containsExactly("VLC")
        assertThat(items.matching("vlc").map { it.app.label }).containsExactly("VLC")
    }

    /**
     * The package matches too, and it is not a developer's convenience.
     *
     * On this screen — unlike in a store's catalogue — the package is a thing the user has **seen**:
     * it is written on every listing they installed from. Somebody typing `org.mozilla` is looking
     * for something real.
     */
    @Test
    fun `the package name matches as well as the label`() {
        val items = listOf(row("VLC", pkg = "org.videolan.vlc"), row("Firefox", pkg = "org.mozilla.firefox"))

        assertThat(items.matching("org.videolan").map { it.app.label }).containsExactly("VLC")
        assertThat(items.matching("mozilla").map { it.app.label }).containsExactly("Firefox")
    }

    // --- Ordering ----------------------------------------------------------------------------

    @Test
    fun `by name, ignoring case`() {
        val items = listOf(row("vlc"), row("Aardvark"), row("Firefox"))

        assertThat(items.arrangedBy(MyAppsSort.NAME).map { it.app.label })
            .containsExactly("Aardvark", "Firefox", "vlc").inOrder()
    }

    /**
     * Updatable first — and the rest **alphabetical**, not in whatever order they arrived.
     *
     * Removing the `.then(BY_NAME)` leaves this list as `[VLC, Firefox, Aardvark]`, because the sort
     * is stable and that is the input order. Measured by injection.
     */
    @Test
    fun `updatable first, and the ties fall back to the name`() {
        val items = listOf(row("VLC", updatable = true), row("Firefox"), row("Aardvark"))

        assertThat(items.arrangedBy(MyAppsSort.UPDATABLE_FIRST).map { it.app.label })
            .containsExactly("VLC", "Aardvark", "Firefox").inOrder()
    }

    /**
     * Only "there is something newer" counts as updatable.
     *
     * A paused app and a pinned one are decisions the user already took, and hoisting them to the
     * top would be the screen insisting on something that was declined.
     */
    @Test
    fun `a paused or pinned app is not hoisted to the top`() {
        val items = listOf(
            row("Aardvark", update = UpdateState.Paused(available = true)),
            row("Firefox", update = UpdateState.Pinned(versionCode = 1, heldBack = "2")),
            row("VLC", updatable = true),
        )

        assertThat(items.arrangedBy(MyAppsSort.UPDATABLE_FIRST).map { it.app.label })
            .containsExactly("VLC", "Aardvark", "Firefox").inOrder()
    }

    @Test
    fun `newest first, and same-second installs fall back to the name`() {
        val items = listOf(
            row("VLC", installedAt = 300),
            row("Firefox", installedAt = 100),
            row("Aardvark", installedAt = 100),
        )

        assertThat(items.arrangedBy(MyAppsSort.RECENTLY_INSTALLED).map { it.app.label })
            .containsExactly("VLC", "Aardvark", "Firefox").inOrder()
    }

    /**
     * By store, and a row whose store this build no longer wires goes **last**.
     *
     * Its display name is `null`, and sorting it as an empty string would drop it into the middle of
     * the list under no heading anybody can read.
     */
    @Test
    fun `by store, with the nameless ones last and the ties by name`() {
        val items = listOf(
            row("Orphan", storeName = null),
            row("VLC", storeName = "F-Droid"),
            row("Aardvark", storeName = "F-Droid"),
            row("Firefox", storeName = "APKMirror"),
        )

        assertThat(items.arrangedBy(MyAppsSort.STORE).map { it.app.label })
            .containsExactly("Firefox", "Aardvark", "VLC", "Orphan").inOrder()
    }

    private fun row(
        label: String,
        pkg: String = "org.example.${label.lowercase()}",
        updatable: Boolean = false,
        update: UpdateState = if (updatable) UpdateState.Available("2") else UpdateState.UpToDate,
        installedAt: Long = 1,
        storeName: String? = "F-Droid",
    ) = InstalledAppItem(
        app = InstalledApp(
            packageName = pkg,
            label = label,
            versionName = "1",
            versionCode = 1,
            signerSha256 = null,
            installedAt = Instant.fromEpochSeconds(installedAt),
            installerKind = InstallerKind.SESSION,
            sourceStoreId = StoreId.FDROID,
        ),
        storeName = storeName,
        update = update,
    )
}

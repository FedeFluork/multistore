package com.multistore.feature.appdetail

import androidx.compose.runtime.Composable
import com.multistore.core.data.repository.StoreComparison
import com.multistore.core.data.repository.StoreComparisonRow
import com.multistore.core.model.ModifiedBuild
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.model.ThemeMode
import com.multistore.core.testing.ScreenshotTest
import com.multistore.store.api.HashAvailability
import kotlin.time.Instant
import org.junit.Test

/**
 * The comparison table in both themes, with **every** kind of cell it can draw.
 *
 * ### What is deliberately in the picture
 *
 * The five rows are not five variations of the same store: each one is a case the table has to be
 * able to say out loud, and together they are the whole vocabulary.
 *
 *  - **F-Droid**, the row one arrived from: marked, in the container colour, and still openable.
 *  - **APKMirror**: a published hash and a package name, i.e. the best a scraped source gets.
 *  - **uptodown**: `SOMETIMES` for the hash and **no version code** — the store publishes none
 *    anywhere on its site — so the version cell shows a name with nothing behind it.
 *  - **an1**: a rework the store declared, and **no package name at all**, which is a property of
 *    that site and not a gap in the parser.
 *  - **APKMody**: the row nobody has opened. Its empty cells must read "not read yet" and **not**
 *    "does not publish it", and having it next to an1's genuinely absent package is the only way a
 *    picture can show that the two sentences are different.
 *
 * That last pair is the reason this golden exists at all: the screen is made of absences, and the
 * regression it can suffer is one sentence quietly replacing the other.
 */
class StoreComparisonScreenScreenshotTest : ScreenshotTest() {

    @Test
    fun lightTheme() = capture(SCREEN_NAME, ThemeMode.LIGHT) { Content() }

    @Test
    fun darkTheme() = capture(SCREEN_NAME, ThemeMode.DARK) { Content() }

    /**
     * One store, and therefore nothing to compare.
     *
     * A pair of its own because it is the **common** case on a fresh install — cross-store matching
     * only knows what has been searched or synced — and because an empty state is a screen, not an
     * error: the golden has to show it looks like one.
     */
    @Test
    fun emptyLight() = capture(EMPTY_SCREEN_NAME, ThemeMode.LIGHT) { Empty() }

    @Test
    fun emptyDark() = capture(EMPTY_SCREEN_NAME, ThemeMode.DARK) { Empty() }

    @Composable
    private fun Content() {
        StoreComparisonScreen(
            comparison = StoreComparison(
                title = "Telegram",
                otherStoresUnexplored = 3,
                rows = listOf(
                    StoreComparisonRow(
                        storeId = StoreId.FDROID,
                        ref = StoreAppRef("org.telegram.messenger"),
                        current = true,
                        versionName = "11.13.2",
                        versionCode = 61_082,
                        lastUpdated = UPDATED,
                        sizeBytes = 74_500_000,
                        hashAvailability = HashAvailability.ALWAYS,
                        packageName = "org.telegram.messenger",
                        modifiedBuild = ModifiedBuild.NONE,
                        listingRead = true,
                    ),
                    StoreComparisonRow(
                        storeId = StoreId.APKMIRROR,
                        ref = StoreAppRef("telegram/telegram/telegram-11-13-2-release"),
                        current = false,
                        versionName = "11.13.2",
                        versionCode = 61_082,
                        lastUpdated = UPDATED,
                        sizeBytes = 82_100_000,
                        hashAvailability = HashAvailability.SOMETIMES,
                        packageName = "org.telegram.messenger",
                        modifiedBuild = ModifiedBuild.NONE,
                        listingRead = true,
                    ),
                    StoreComparisonRow(
                        storeId = StoreId.UPTODOWN,
                        ref = StoreAppRef("telegram"),
                        current = false,
                        versionName = "11.13.0",
                        // uptodown publishes no version code anywhere on its site: the cell shows a
                        // name and the comparison against the others cannot be made on the number.
                        versionCode = null,
                        lastUpdated = null,
                        sizeBytes = 79_300_000,
                        hashAvailability = HashAvailability.SOMETIMES,
                        // …and it redistributes Telegram under a **different** package. It is why
                        // this column shows the name rather than a tick: a tick in both cells would
                        // hide the one difference that makes an update impossible.
                        packageName = "org.telegram.messenger.web",
                        modifiedBuild = ModifiedBuild.NONE,
                        listingRead = true,
                    ),
                    StoreComparisonRow(
                        storeId = StoreId.AN1,
                        ref = StoreAppRef("2971-telegram"),
                        current = false,
                        versionName = "12.4.3",
                        versionCode = null,
                        lastUpdated = UPDATED,
                        sizeBytes = 96_800_000,
                        hashAvailability = HashAvailability.SOMETIMES,
                        // Genuinely absent: an1 publishes no package name on any page of its site.
                        packageName = null,
                        modifiedBuild = ModifiedBuild.DECLARED,
                        listingRead = true,
                    ),
                    StoreComparisonRow(
                        storeId = StoreId.APKMODY,
                        ref = StoreAppRef("telegram"),
                        current = false,
                        versionName = "12.4.3",
                        // Never opened: every empty cell here must say "not read yet".
                        listingRead = false,
                        hashAvailability = HashAvailability.NONE,
                        modifiedBuild = ModifiedBuild.POSSIBLE,
                    ),
                ),
            ),
            storeDisplayName = { it.wireName },
            onBack = {},
            onOpenListing = { _, _ -> },
        )
    }

    @Composable
    private fun Empty() {
        StoreComparisonScreen(
            comparison = StoreComparison(
                title = "Tor Browser",
                rows = listOf(
                    StoreComparisonRow(
                        storeId = StoreId.FDROID,
                        ref = StoreAppRef("org.torproject.torbrowser"),
                        current = true,
                        versionName = "14.0.4",
                        listingRead = true,
                    ),
                ),
                otherStoresUnexplored = 8,
            ),
            storeDisplayName = { it.wireName },
            onBack = {},
            onOpenListing = { _, _ -> },
        )
    }

    private companion object {
        const val SCREEN_NAME = "StoreComparisonScreen"
        const val EMPTY_SCREEN_NAME = "StoreComparisonScreen_empty"

        /** Fixed, because a golden that read the clock would not be comparable with itself. */
        val UPDATED: Instant = Instant.parse("2026-08-14T09:30:00Z")
    }
}

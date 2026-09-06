package com.multistore.core.data.mapper

import com.google.common.truth.Truth.assertThat
import com.multistore.core.database.dao.ListingWithDetails
import com.multistore.core.model.LocalizedText
import com.multistore.core.model.Screenshot
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.model.StoreListingDetail
import com.multistore.core.model.StoreListingSummary
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import org.junit.Test

/**
 * A listing goes to the database and comes back **whole**.
 *
 * ### The defect this exists to make impossible
 *
 * `StoreListingDetail.translationUrl` was populated by the F-Droid adapter from M1 and reached no
 * screen — not because nothing drew it, but because the entity mapper did not write it and the
 * domain mapper could not read it. The value lived exactly as long as the object the adapter had
 * just built. Nothing said so: no compiler warning, no failing test, no wrong number on screen.
 *
 * That is the shape of defect a round trip catches and nothing else does. A test asserting on the
 * entity alone would have passed — the entity was fine, it simply had no column — and a test on the
 * screen would have passed too, because a listing read straight from an adapter has the field.
 *
 * ### Why every optional field is asserted, and not a sample
 *
 * The two halves of the mapper are two hand-written lists of assignments, so the failure mode is
 * always the same: one line missing from one of them. Asserting a sample would leave the rest
 * exactly as unprotected as `translationUrl` was, and the next field to be added is precisely the one
 * a sample would not contain. Injecting the removal of either half's `translationUrl` line turns
 * this red; before it existed, both injections stayed green.
 */
class CatalogMappingTest {

    @Test
    fun `every field the adapter fills survives a round trip through the entities`() {
        val listing = fullListing()

        val rows = listing.toRows(now = NOW, ttl = 7.days)
        val back = ListingWithDetails(
            listing = rows.listing,
            versions = rows.versions,
            screenshots = rows.screenshots,
        ).toDetail(app = rows.app)

        // The seven author links of the "Links" block, which had no reader until 0.7.0 and — for one
        // of them — no column either.
        assertThat(back.sourceCodeUrl).isEqualTo(listing.sourceCodeUrl)
        assertThat(back.issueTrackerUrl).isEqualTo(listing.issueTrackerUrl)
        assertThat(back.webSiteUrl).isEqualTo(listing.webSiteUrl)
        assertThat(back.changelogUrl).isEqualTo(listing.changelogUrl)
        assertThat(back.translationUrl).isEqualTo(listing.translationUrl)
        assertThat(back.donateUrls).isEqualTo(listing.donateUrls)
        assertThat(back.authorName).isEqualTo(listing.authorName)

        // The rest of what a detail page shows and nothing used to.
        assertThat(back.whatsNew).isEqualTo(listing.whatsNew)
        // URL **and** language: the tag is what keeps a strip from showing the same screen in four
        // languages, and it is exactly the kind of field a hand-written mapper drops in silence —
        // which is how `translationUrl` spent four milestones going nowhere.
        assertThat(back.screenshots).isEqualTo(listing.screenshots)
        assertThat(back.license).isEqualTo(listing.license)
        assertThat(back.addedAt).isEqualTo(listing.addedAt)
        assertThat(back.summary.ratingCount).isEqualTo(listing.summary.ratingCount)
        assertThat(back.summary.downloadsLabel).isEqualTo(listing.summary.downloadsLabel)
        assertThat(back.summary.rating).isEqualTo(listing.summary.rating)
    }

    /**
     * And the absences survive too, which is the other half of being honest.
     *
     * Eight stores out of nine publish none of these, so a mapper turning `null` into `""` — or into
     * a row with a link whose address is empty — would put an unusable entry on almost every listing
     * in the catalogue.
     */
    @Test
    fun `a source that publishes none of them comes back with none of them`() {
        val bare = StoreListingDetail(
            summary = StoreListingSummary(
                storeId = StoreId.AN1,
                ref = StoreAppRef("12345-example"),
                title = "Example",
            ),
        )

        val rows = bare.toRows(now = NOW, ttl = 1.days)
        val back = ListingWithDetails(rows.listing, rows.versions, rows.screenshots).toDetail(rows.app)

        assertThat(back.sourceCodeUrl).isNull()
        assertThat(back.issueTrackerUrl).isNull()
        assertThat(back.webSiteUrl).isNull()
        assertThat(back.changelogUrl).isNull()
        assertThat(back.translationUrl).isNull()
        assertThat(back.donateUrls).isEmpty()
        assertThat(back.screenshots).isEmpty()
        assertThat(back.whatsNew.isEmpty).isTrue()
        assertThat(back.summary.ratingCount).isNull()
        assertThat(back.summary.downloadsLabel).isNull()
    }

    private fun fullListing() = StoreListingDetail(
        summary = StoreListingSummary(
            storeId = StoreId.FDROID,
            ref = StoreAppRef("org.fdroid.fdroid"),
            title = "F-Droid",
            packageName = "org.fdroid.fdroid",
            developer = "F-Droid Limited",
            rating = 4.6f,
            ratingCount = 128_461,
            downloadsLabel = "10M+",
        ),
        description = LocalizedText(mapOf("en" to "An installable catalogue of free software.")),
        whatsNew = LocalizedText(mapOf("en" to "Repository updates no longer stall.")),
        screenshots = listOf(
            Screenshot(url = "https://f-droid.org/en/shot-1.png", locale = "en-US"),
            Screenshot(url = "https://f-droid.org/de/shot-1.png", locale = "de"),
            // Untagged as well, because eight stores out of nine publish them that way and the
            // column has to carry the absence as faithfully as the value.
            Screenshot(url = "https://f-droid.org/shot-plain.png"),
        ),
        license = "GPL-3.0-or-later",
        sourceCodeUrl = "https://gitlab.com/fdroid/fdroidclient",
        issueTrackerUrl = "https://gitlab.com/fdroid/fdroidclient/-/issues",
        webSiteUrl = "https://f-droid.org",
        changelogUrl = "https://gitlab.com/fdroid/fdroidclient/-/releases",
        translationUrl = "https://hosted.weblate.org/projects/f-droid/",
        // Two, because F-Droid publishes several for the same package and keeping one would be
        // choosing for the author which of their channels gets shown.
        donateUrls = listOf("https://f-droid.org/donate", "https://liberapay.com/F-Droid-Data"),
        authorName = "F-Droid Limited",
        addedAt = Instant.fromEpochSeconds(1_600_000_000),
    )

    private companion object {
        /** Fixed, so nothing in the assertions depends on when the test runs. */
        val NOW: Instant = Instant.fromEpochSeconds(1_756_166_400)
    }
}

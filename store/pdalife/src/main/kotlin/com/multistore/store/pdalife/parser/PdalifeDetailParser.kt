package com.multistore.store.pdalife.parser

import com.multistore.core.model.AppVersion
import com.multistore.core.model.ArtifactType
import com.multistore.core.model.LocalizedText
import com.multistore.core.model.Screenshot
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.model.StoreListingDetail
import com.multistore.core.model.StoreListingSummary
import com.multistore.store.api.StoreResult
import com.multistore.store.common.html.HtmlPage
import com.multistore.store.common.html.TextValues
import com.multistore.store.common.html.Urls
import com.multistore.store.common.html.parseHtml
import com.multistore.store.pdalife.PdalifeConfig
import com.multistore.store.pdalife.PdalifeRefs

/**
 * pdalife's listing page, which is 96 KB with four adverts dressed as content.
 *
 * The solid parts are there and they are `schema.org` microdata: `name`, `applicationCategory`,
 * `author`, `description`, `aggregateRating`, `screenshot`, `offers`. The rest of the page is never
 * read by position — the cookies `advert_order_app_download_buttons` and
 * `advert_order_app_description` declare a server-decided slot order.
 *
 * ### The `packageName` is there, and taking it literally gets it wrong seventeen times out of
 * seventeen
 *
 * The `.game-download__stores` container carries the original app's Google Play link, and its query
 * carries the package. Across 17 sampled listings there are **12 real packages and 5 absences**
 * (apps that are not on Play).
 *
 * What makes the case instructive is how the naive read fails. Taking the page's first
 * `play.google.com` returns `cc.peacedeath.peacedeathapp` — an advert — **on all 17**. Telegram's
 * listing has five of them, four of which are that advert, and the real link is the **last**: so
 * "take the first" and "take the last" behave differently on the same page, which is the worst way
 * to be wrong — one of the two seems to work until the order changes, and here it changes by
 * itself.
 *
 * ### What could not be measured, and which way it errs
 *
 * For modyolo the same deduction was **verified against the bytes**: eight APKs downloaded and read
 * with `aapt2`, seven marked MOD, eight matches out of eight. Here that is impossible: the file
 * sits behind a reCAPTCHA v3 and the only way to get it is for a person to press the button.
 * pdalife redistributes modified builds ("Money Mod", "Mod Menu", "Premium", "Unlocked"), and a
 * repackaged build **could** change the package.
 *
 * The package is declared all the same, and the reason is that the two directions do not cost the
 * same:
 *
 *  - **declaring it**, if one day a MOD changed package, step 4 of the pre-install pipeline would
 *    block it — a legitimate installation refused, i.e. a visible fault in the cautious direction;
 *  - **not declaring it**, this store's download page — which serves three buttons, two of them
 *    adverts and one of them a real `.apk` of another app — would have **nothing** stopping the
 *    advert being installed. `WebViewDownloadViewModel` passes exactly this `packageName` to
 *    verification, and it is the only defence that path has.
 *
 * ### `data-version_id` is not a `versionCode`, and it is uptodown's trap again
 *
 * It is a monotonic discriminator, and it works as one: it grows over time, so it orders the
 * versions without comparing strings. But it grows **across the whole site together** — 96571 for a
 * 2023 Telegram version, 120868 for a 2026 Slime Rancher — so it is no app's `versionCode`. Putting
 * it there would give an anti-downgrade rule comparing 120868 with the installed app's real version
 * code. It is for ordering, and nothing else.
 */
internal class PdalifeDetailParser(private val config: PdalifeConfig) {

    fun parse(html: String, url: String, ref: StoreAppRef): StoreResult<StoreListingDetail> =
        parseHtml(html, url) { document ->
            val selectors = config.selectors
            val versions = versionsOf(document)
            val minSdk = minSdkOf(document)

            val summary = StoreListingSummary(
                storeId = StoreId.PDALIFE,
                ref = ref,
                title = document.text(selectors.detailTitle),
                packageName = packageNameOf(document),
                summary = LocalizedText.EMPTY,
                developer = document.attrOrNull(selectors.detailDeveloper, CONTENT),
                iconUrl = document.absUrlOrNull(selectors.detailIcon, SRC),
                categories = listOfNotNull(document.textOrNull(selectors.detailCategory)),
                contentKind = PdalifeRefs.contentKindOf(
                    document.all(selectors.detailBreadcrumb).mapNotNull { it.ownAttrOrNull(HREF) },
                ),
                latestVersionName = versions.firstOrNull()?.versionName,
                latestVersionCode = null,
                rating = ratingOf(document),
                ratingCount = document.attrOrNull(selectors.detailRatingCount, CONTENT)
                    ?.filter(Char::isDigit)
                    ?.toIntOrNull(),
                lastUpdated = versions.firstOrNull()?.publishedAt,
            )

            StoreListingDetail(
                summary = summary,
                description = LocalizedText.of(document.textOrNull(selectors.detailDescription)),
                screenshots = screenshotsOf(document),
                versions = versions.map { it.copy(minSdk = minSdk) },
            )
        }

    /**
     * The package, read **inside** the offers container. See the note at the head.
     *
     * An absence here is normal and not an error: they are the apps that are not on Google Play
     * (5 out of 17), including everything that has been removed.
     */
    private fun packageNameOf(document: HtmlPage): String? {
        val href = document.absUrlOrNull(config.selectors.detailPlayLink, HREF) ?: return null
        return Urls.queryParam(href, PLAY_ID_PARAM)?.takeIf { PACKAGE_NAME.matches(it) }
    }

    /**
     * The rating, out of ten, with the scale the page itself declares.
     *
     * `<meta itemprop='bestRating' content='10'/>` next to `ratingValue` at `9.2292`. `bestRating`
     * is read rather than trusting the configuration because it is written right next to the value
     * it has to normalise; [PdalifeConfig.ratingScale] remains the fallback for when the listing
     * does not declare it — and for search, where there is no `bestRating` to read.
     */
    private fun ratingOf(document: HtmlPage): Float? {
        val raw = document.attrOrNull(config.selectors.detailRatingValue, CONTENT) ?: return null
        val scale = document.attrOrNull(config.selectors.detailRatingBest, CONTENT)
            ?.toFloatOrNull()
            ?: config.ratingScale
        return TextValues.rating(raw, scale)?.takeIf { it > 0f }
    }

    /**
     * The screenshots, **read** and not derived from the `hash`.
     *
     * They are derivable: `/app/{hash}/img{N}.jpg`. Deriving them, though, requires knowing **how
     * many** there are, and that number is written nowhere — it would only be discovered by asking
     * for `img8.jpg` and taking a 404, i.e. one wasted request per listing. The gallery lists them
     * all: seven on Telegram, ten on Unleashed Pixel Dungeon, five on Real Gangster Crime.
     *
     * The anchor's `href` and not the `img`'s `src`: the second is the thumbnail (`th_img1.jpg`),
     * the first is the version the site opens when clicked (`m_img1.jpg`).
     */
    private fun screenshotsOf(document: HtmlPage): List<Screenshot> =
        document.all(config.selectors.detailScreenshot)
            .mapNotNull { it.ownAbsUrlOrNull(HREF) }
            .map { Screenshot(url = it) }

    /**
     * The minimum SDK, sought by **shape** and neither by position nor by label.
     *
     * The row is `<li>OS version: Android 2.2+</li>`, inside a `ul.game-download__list`. The
     * problem is that next to it sits a second one, "Help", with the same markup, and that the
     * label is translated by the server. All the `li`s are taken and the first from which
     * `TextValues.apiLevel` can extract a level is kept: "Android" followed by a number is the only
     * part of the row no translation touches.
     *
     * It holds for the whole listing and not for the individual version, although the block's title
     * says "Requirements to v9.7.3": pdalife publishes **only one**, the current version's.
     * Attributing it to older versions is an approximation, and it errs in the cautious direction —
     * a recent build's requirement is greater than or equal to an old one's, so at worst a version
     * that would have been installable gets discarded.
     */
    private fun minSdkOf(document: HtmlPage): Int? =
        document.all(config.selectors.detailRequirement)
            .firstNotNullOfOrNull { TextValues.apiLevel(it.textOrNull()) }

    /**
     * The versions, in decreasing [VERSION_ID] order, i.e. newest first.
     *
     * Each `div.accordion-item` is a version and carries three things in three different places:
     * the name in the title, the date and the changelog in the first panel, the file — with
     * `data-version_id` and the size — in the second. **Right after the file's `ul`** the template
     * puts `<div class="js-banner" data-type="app_download_buttons">`, an advert that calls itself
     * "download buttons" in its own attributes: that is why the anchor is the
     * `li[data-version_id]` and not "the panel's first link".
     *
     * **The next page is not requested.** `POST /app/moreVersions/` exists and returns the same
     * markup, but on Slime Rancher it answers `offset=4` with the **same two** versions the page
     * already shows, and goes on answering the same at every offset: a "while it returns something"
     * loop would never end. On Telegram it behaves (one extra version, then empty). An endpoint
     * that works on one app and loops on another is not pagination, and it is the one documented
     * capability of this store that goes unused.
     */
    private fun versionsOf(document: HtmlPage): List<AppVersion> =
        document.all(config.selectors.versionItem)
            .mapNotNull(::versionOf)
            .sortedByDescending { it.second }
            .map { it.first }

    private fun versionOf(item: HtmlPage): Pair<AppVersion, Long>? {
        val selectors = config.selectors
        val file = item.oneOrNull(selectors.versionFile) ?: return null
        val versionId = file.ownAttrOrNull(VERSION_ID)?.toLongOrNull() ?: return null
        val href = file.absUrlOrNull(selectors.versionFileLink, HREF) ?: return null
        val hash = PdalifeRefs.hashFromDownloadUrl(href) ?: return null
        val label = item.textOrNull(selectors.versionTitle)?.normalizeSpaces() ?: return null
        val changes = item.textOrNull(selectors.versionChanges)

        val version = AppVersion(
            // `v9.7.3   Original`, `v6.3.5   Money Mod`. The leading `v` is typography and is
            // dropped; the label next to it **is kept**, because "Money Mod" tells the user that is
            // not the developer's build. It is the same choice made for modyolo, and on a store
            // that redistributes modified builds it is information, not noise.
            versionName = label.removePrefix(VERSION_PREFIX).trim().ifBlank { label },
            // See the note at the head: `data-version_id` is not a version code.
            versionCode = null,
            ref = PdalifeRefs.versionRef(hash),
            // Every observed download is a single `.apk`, and the label confirms it ("Download
            // apk"). The real type is decided by the file name when the assisted download returns
            // anyway: see `supportsSplits` in the adapter.
            artifactType = ArtifactType.APK,
            // `68.83 Mb` — rounded, so for display and not for verification. See the note on
            // `AppVersion.sizeBytes`.
            sizeBytes = TextValues.byteSize(file.textOrNull(selectors.versionFileSize)),
            publishedAt = TextValues.dottedDayMonthYear(changes),
            changelog = LocalizedText.of(changelogOf(changes)),
        )
        return version to versionId
    }

    /**
     * The changelog without the date preceding it.
     *
     * The row is `26.07.2023  - Changes not specified.`: a date, a hyphen, and the prose. The date
     * has already gone into `publishedAt`, and leaving it here too would show it twice on the same
     * listing.
     *
     * **The hyphen is not always there**, though, and that is the mistake the obvious cut would
     * make: Telegram's fourth version writes `11.11.2022  Topics in groups and more`, with no
     * separator. A `substringAfter(" - ")` would return the empty string **on the one row of this
     * listing that has a real changelog**, and would leave the note only where there was nothing to
     * say. So the date is removed, the hyphen is not sought.
     *
     * What remains may well be "Changes not specified.", which is what pdalife writes when it does
     * not know what changed: it is the store's sentence, and it stays theirs.
     */
    private fun changelogOf(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val text = raw.replaceFirst(LEADING_DATE, MISSING).trim().trimStart(CHANGES_SEPARATOR)
        return text.trim().takeIf { it.isNotBlank() }
    }

    /**
     * Spaces unified.
     *
     * pdalife separates the number from the label with `&nbsp;&nbsp;`, and a non-breaking space is
     * not a space to `String.trim()`: without this, `versionName` would come out as
     * `9.7.3  Original`, which looks the same on screen and does not in a comparison.
     */
    private fun String.normalizeSpaces(): String =
        replace(NO_BREAK_SPACE, ' ').replace(WHITESPACE_RUN, " ").trim()

    private companion object {
        const val CONTENT = "content"
        const val SRC = "src"
        const val HREF = "href"
        const val VERSION_ID = "data-version_id"
        const val VERSION_PREFIX = "v"
        const val PLAY_ID_PARAM = "id"
        const val CHANGES_SEPARATOR = '-'
        const val MISSING = ""
        val LEADING_DATE = Regex("""^\s*\d{1,2}\.\d{1,2}\.\d{4}""")
        const val NO_BREAK_SPACE = '\u00A0'
        val WHITESPACE_RUN = Regex("""\s+""")

        /** An Android package name, so that a listing does not concatenate something that is not. */
        val PACKAGE_NAME = Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+""")
    }
}

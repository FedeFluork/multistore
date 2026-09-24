package com.multistore.store.api

import com.multistore.core.model.ContentKind
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * What a store can do, declared by the adapter and used by the UI to adapt.
 *
 * It exists so the UI never grows a `when (storeId)`: no "rating" chip on an F-Droid listing
 * (there is none), no "version history" tab where it is unsupported.
 *
 * The constraint that makes the declaration trustworthy: capabilities are declared **honestly**,
 * and a capability declared `true` and not populated fails the contract test. It is not
 * documentation, it is a verified contract.
 */
data class StoreCapabilities(
    val search: Boolean,
    val trending: Boolean,
    val recent: Boolean,
    val versionHistory: Boolean,
    val providesPackageName: Boolean,
    val providesRating: Boolean,
    val providesScreenshots: Boolean,
    val providesChangelog: Boolean,
    val providesHash: HashAvailability,
    val providesSignerFingerprint: Boolean,
    val supportsSplits: Boolean,
    /**
     * `true` if this source redistributes APKs somebody other than the developer has reworked.
     *
     * Five of the nine: apkmody, modyolo, an1, pdalife, liteapks. It is a fact about the **store**,
     * not about a file, and it is what lets a listing say something before a byte is downloaded —
     * until now the only place it was written was the title, in the store's own words.
     *
     * ### What it costs the user, and why it is not a warning
     *
     * It is the same limit `PreInstallVerifier` already declares: for a rework there is no original
     * developer signature to compare against, so the pipeline protects against the package being
     * **substituted** and not against the archive being **tampered with upstream**. The badge exists
     * to say that in a sentence, at the moment the choice between two stores is made, instead of
     * after the download.
     *
     * ### Only one direction of this is checkable, and the contract test checks that one
     *
     * If any fixture row comes back with [StoreListingSummary.declaredModified], this must be
     * `true`: an adapter cannot mark a single listing as a rework while denying that its store
     * publishes any. The converse — a store that redistributes reworks and marks none of them —
     * cannot be read off a fixture, because "no row is marked" is exactly what apkmody and liteapks
     * look like. That half is a declaration, like every other capability here, and the honesty rule
     * that governs the rest of this class governs it too.
     */
    val redistributesModifiedBuilds: Boolean,
    /**
     * `true` if this source distributes **only** free and open-source software.
     *
     * One of the nine: f-droid. It is a fact about the store's catalogue and not about any one
     * listing, which is why it sits here beside [redistributesModifiedBuilds] rather than on a
     * summary — and it is the other half of the same question a person asks when choosing where to
     * install from: *who built this file, and can anybody check?*
     *
     * ### It is a declaration, and it is not checkable from a fixture
     *
     * Like most of this class. There is no property of a downloaded page that says "everything this
     * store publishes is open source": the claim is about a catalogue, and a fixture is one page of
     * it. The honesty rule that governs the rest of the class governs this too — declaring it
     * wrongly would file a store under a heading that promises something it does not.
     *
     * It is deliberately **not** the negation of [redistributesModifiedBuilds]. A store can do
     * neither, and three of the nine do exactly that: apkcombo, apkmirror and uptodown mirror the
     * developer's own builds without being open-source-only.
     */
    val openSourceOnly: Boolean,
    val downloadMode: DownloadMode,
    val networkTier: NetworkTier,
    /**
     * The User-Agent this store must be queried with.
     *
     * Mandatory, and the contract test checks it. Not zeal: apkmirror answers **403 with 153
     * bytes** to library User-Agents (`okhttp`, `curl`) and 200 to a Chrome mobile UA. OkHttp's
     * default is a guaranteed block on at least one of the nine stores, so leaving the field
     * optional would let an adapter be born already broken.
     */
    val userAgent: String,
    val supportedFilters: Set<FilterCapability>,
    /**
     * The filters **the app** can apply to this store's search results by itself.
     *
     * This is [FilterPlan]'s second tier, and it says something different from
     * [supportedFilters]: not "the store can filter" but "the field to filter on is present on
     * **every** row this adapter produces", so discarding rows here gives the same set the store
     * would.
     *
     * **The declaration is verified, in both directions.** The contract test censuses the real
     * search fixtures and demands equivalence: declaring it with a row that lacks the field is a
     * filter that discards what it has not judged; *not* declaring it when the field is always
     * there is a store excluded from a search it could have answered. The first lies, the second
     * costs results, and neither would be visible by eye.
     *
     * The "on every row" criterion is not caution: on the 26/08/2026 fixtures apkcombo publishes
     * the rating on **19 rows out of 20**, and with a softer criterion that twentieth would vanish
     * from a rating-filtered search with nothing saying so.
     *
     * Only filters decidable from a list row belong here — today [FilterCapability.CONTENT_KIND],
     * [FilterCapability.CATEGORY] and [FilterCapability.MIN_RATING]. `MIN_SDK` and `ANTI_FEATURES`
     * live in `AppVersion`, the `SORT_*` values are not filters, and `NSFW_CONTENT` is a label
     * that, where absent, cannot be inferred.
     */
    val clientFilters: Set<FilterCapability> = emptySet(),
    /**
     * Where search results come from.
     *
     * This is the capability that lets F-Droid search **offline**. The remote API exists
     * (`search.f-droid.org/api/search_apps`) but is capped at 10 results, ignores `page` and
     * returns neither `packageName` nor version: fine for suggesting something while the first
     * sync runs, not for being the app's search. With the complete index in Room the search is
     * instant, pageable, complete and offline.
     */
    val searchSource: SearchSource = SearchSource.REMOTE,
    /** The content classes this store distinguishes. */
    val contentKinds: Set<ContentKind> = setOf(ContentKind.UNKNOWN),
    /**
     * How long a listing saved from this store stays valid.
     *
     * A **per-row** TTL, set by the adapter: it is a fact about the store, not about the database,
     * and the spread across the nine is wide. A scraped page ages in hours; a locally-indexed
     * store's signed index does not age on its own at all, because its freshness is decided by the
     * index's entry document and not by a timer — which is why a [SearchSource.LOCAL_INDEX]
     * declares far longer values.
     */
    val listingTtl: Duration = 6.hours,
) {
    init {
        require(userAgent.isNotBlank()) {
            "Every adapter MUST declare an explicit User-Agent (escalation ladder, tier 0). " +
                "OkHttp's default is a guaranteed 403 on apkmirror."
        }
    }
}

/** How often the store publishes a file hash. */
enum class HashAvailability {
    NONE,
    SOMETIMES,
    ALWAYS,
}

/**
 * How the file is reached.
 *
 * The four values correspond to the `ChallengeResolver` rungs normally needed; the full ladder is
 * described on that interface.
 */
enum class DownloadMode {
    /** OkHttp with the declared UA, no gate. */
    DIRECT,

    /** Normally direct, WebView only on the last hop. */
    DIRECT_WITH_FALLBACK,

    /** The WebView resolves the JS challenge without the user touching anything. */
    WEBVIEW_ASSISTED_SILENT,

    /** A real human tap is needed: interactive Turnstile, reCAPTCHA. */
    USER_ASSISTED_ONLY,
}

/** Which network stack this store needs. */
enum class NetworkTier { OKHTTP, CRONET, WEBVIEW }

/** Where the index being searched lives. */
enum class SearchSource {
    /** Every search is an HTTP request to the store. */
    REMOTE,

    /**
     * The store publishes a complete index that we download and keep: search queries the local
     * database and is instant, offline and free of rate limit.
     */
    LOCAL_INDEX,
}

/** The filters a store can actually apply. */
enum class FilterCapability {
    CONTENT_KIND,
    CATEGORY,
    SORT_NAME,
    SORT_RECENTLY_UPDATED,
    SORT_RECENTLY_ADDED,
    SORT_DOWNLOADS,
    SORT_RATING,
    MIN_SDK,
    ANTI_FEATURES,

    /**
     * The store **labels** adult content, and can filter it itself.
     *
     * Declaring it is a verifiable promise: the contract test demands a query that yields adult
     * results on the fixtures, and that with [SearchFilters.includeNsfw] at `false` a **strictly
     * smaller** set comes back. A filter that does not filter would otherwise pass unnoticed,
     * which is exactly how a safety setting becomes decorative.
     *
     * What the capability does **not** promise is completeness. Measured on modyolo on 25/08/2026:
     * the six declared adult categories cover 698 posts, but the site's three most recent articles
     * — adult visual novels distributed via Patreon — sit in "Role Playing" and survive the
     * exclusion. The store labels badly, and no filter can know more than the source.
     */
    NSFW_CONTENT,

    /**
     * "Only apps rated at least this much" can be asked for.
     *
     * None of the nine stores can do it themselves: it exists as a **second**-tier filter, applied
     * by the app reading `rating` — and indeed appears only in [StoreCapabilities.clientFilters].
     * It still belongs in this enum rather than a separate list, because the question it poses is
     * the same: the difference between the two sets is not which filter it is, but **who applies
     * it**.
     */
    MIN_RATING,
}

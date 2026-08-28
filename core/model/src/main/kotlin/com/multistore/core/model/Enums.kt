package com.multistore.core.model

/**
 * What kind of artifact the store publishes.
 *
 * Only [APK] is handed to `PackageInstaller` as-is. The other three are **containers** of
 * splits — sometimes of splits plus game data — that have to be opened first.
 *
 * It is a **declaration, not a diagnosis**: the adapter writes it from an extension or a label,
 * both authored by the store. On apkcombo the three declarations do not even agree with each
 * other: the R2 object is `….apks`, the `content-disposition` calls it `.xapk`, the
 * `content-type` says `application/xapk-package-archive` — and the contents are an XAPK. So the
 * type decides what to **show** and whether to offer the file; what to **do** with it is decided
 * by whoever opens it, by looking inside. See `ContainerReader` in `:core:installer`.
 */
enum class ArtifactType {
    APK,
    XAPK,
    APKM,
    APKS,
    ;

    /**
     * `true` if the file goes to `PackageInstaller` as-is.
     *
     * Read by the contract test, to check that an adapter with `supportsSplits = false` does not
     * declare containers. It no longer decides whether a version is installable: that question
     * belongs to `VersionSelection.supportedArtifactTypes`, whose answer is all four types.
     */
    val isSingleApk: Boolean get() = this == APK
}

/** App or game, where the store distinguishes them. */
enum class ContentKind { APP, GAME, UNKNOWN }

/**
 * How to order a list of results.
 *
 * It lives here rather than in `:store:api` because it has two readers that cannot see each
 * other: the filters of one search, and the preference saved in [SearchSettings].
 *
 * ### Six values, three offered, and the difference is a measurement
 *
 * The enum describes what a store *could* know how to order by, and it is right that it stays
 * broad — the F-Droid index can order by date. What the **aggregated search** can offer is only
 * what can be computed across nine sources at once, and a census of the search fixtures
 * (26/08/2026) says how little that is:
 *
 * | field | stores that populate it on **every** row |
 * |---|---|
 * | `title` | 9 of 9 |
 * | `rating` | 3 of 9 (an1, liteapks, pdalife; apkcombo stops at 19 rows of 20) |
 * | `lastUpdated` | **none** — apkmirror populates 9 of 10, the other eight zero |
 * | `downloadsLabel` | apkcombo 4 rows of 20, and it is a label (`10M+`), not a number |
 * | date added | does not exist in `StoreListingSummary` |
 *
 * Hence [SELECTABLE]. Offering "most recent" across nine stores when one publishes a date, and
 * not even on every row, would mean an ordering decided by eight absent values: a list that
 * looks sorted and is not.
 */
enum class SearchSort {
    RELEVANCE,
    NAME,
    RECENTLY_UPDATED,
    RECENTLY_ADDED,
    DOWNLOADS,
    RATING,
    ;

    companion object {
        /**
         * The three criteria the aggregated search can actually compute. See the table above.
         *
         * [RELEVANCE] is first, and is also the zero value of the corresponding proto field: it
         * is the order [AggregatedApp] produces by itself.
         */
        val SELECTABLE: List<SearchSort> = listOf(RELEVANCE, NAME, RATING)
    }
}

/** The three `Installer` implementations. */
enum class InstallerKind(val wireName: String) {
    SESSION("session"),
    SHIZUKU("shizuku"),
    ROOT("root"),
    ;

    companion object {
        fun fromWireNameOrNull(wireName: String): InstallerKind? =
            entries.firstOrNull { it.wireName == wireName }
    }
}

/** How the match between a listing and an aggregated app was established. */
enum class MatchMethod { PACKAGE_NAME, TITLE_DEV, ICON_HASH, INDEX_HINT, USER_CONFIRMED }

/** Circuit-breaker state of a store. */
enum class StoreHealthState { CLOSED, OPEN, HALF_OPEN, DEGRADED }

/** Lifecycle of a download. */
enum class DownloadState {
    QUEUED,
    RUNNING,
    PAUSED,
    VERIFYING,
    READY,
    INSTALLING,
    DONE,
    FAILED,
    ;

    /** The row has finished its cycle: it will not change again, by itself or on request. */
    val isTerminal: Boolean get() = this == DONE || this == FAILED

    /**
     * The **transfer** will not advance on its own any more.
     *
     * Distinct from [isTerminal], and the difference is the two states in between: [READY] means
     * the file is there and verified but nobody has installed it, and [PAUSED] that it stopped
     * leaving something reusable on disk. Code waiting for a download to end must wake on all
     * four; code looking for rows to archive, only on the two in [isTerminal]. Conflating them
     * means either waiting forever for an already-ready download, or deleting a file still in
     * use.
     */
    val isSettled: Boolean get() = isTerminal || this == READY || this == PAUSED
}

/** The device classes a store publishes screenshots for. */
enum class ScreenshotKind { PHONE, SEVEN_INCH, TEN_INCH, TV, WEAR }

package com.multistore.core.model

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * How much space the app may occupy, and for how long.
 *
 * ### The four levels, measured before writing a line
 *
 * On device, 26/08/2026, after a day's use with nine stores wired and the F-Droid index synced:
 *
 * | level | used | where |
 * |---|---|---|
 * | catalogue (Room) | **62.3 MB** | `databases/` |
 * | staged APKs | **28.2 MB** | `files/staging/` |
 * | images (Coil) | 4.3 MB | `cache/coil3_disk_cache/` |
 * | pages (OkHttp) | 1.5 MB | `cache/http/` |
 *
 * The proportions decide which knobs exist. The catalogue dominates, but 95% of it is **one**
 * store's local index — not a cache that ages, a catalogue the user asked to download — so it has
 * no automatic cap but a button that throws it away. Images have a cap because nobody had chosen
 * theirs (Coil uses 2% of free space). Pages have no knob at all: 1.5 MB against a 50 MB cap, and
 * the size is fixed at construction anyway.
 */
data class StorageSettings(
    /**
     * Keep the APK after a successful installation instead of deleting it.
     *
     * `false` — the proto3 zero value — is what the app has always done. Named
     * `deleteApkAfterInstall`, the zero value would have meant the opposite.
     *
     * When on, the file stays **and is reused**: the download row survives in [DownloadState.DONE]
     * and a second `enqueue` for the same version finds it. Without reuse the switch would keep
     * bytes nobody can read — `filesDir` is private to the app — i.e. a setting whose only effect
     * is occupying space.
     */
    val keepApkAfterInstall: Boolean = false,
    /** How many bytes of icons and screenshots to keep on disk. */
    val imageCacheMaxBytes: Long = DEFAULT_IMAGE_CACHE_BYTES,
    /** How long to keep an **already expired** listing from a scraped store. */
    val catalogRetention: CatalogRetention = CatalogRetention.THIRTY_DAYS,
) {
    companion object {
        const val DEFAULT_IMAGE_CACHE_MB: Int = 200
        val DEFAULT_IMAGE_CACHE_BYTES: Long = megabytes(DEFAULT_IMAGE_CACHE_MB)

        /**
         * **Decimal** megabytes, and the device decided it.
         *
         * The screen writes every size with `Formatter.formatShortFileSize`, which on Android uses
         * SI units. With 1024² a "200 MB" cap would appear as **210 MB** next to a list of choices
         * reading 67, 210, 537 and 1.07 GB — the only place in the app where a megabyte would mean
         * something other than what the system shows. Between the two conventions, the one the
         * user compares against wins.
         */
        fun megabytes(count: Int): Long = count * 1_000_000L

        /**
         * The permitted range, in megabytes.
         *
         * **The minimum must stay greater than zero**, the same link that holds
         * `SearchSettings.STORE_TIMEOUT_RANGE` together: it is also what makes the proto3 zero
         * value fall back to the default. Here the link is easier to break by accident, because
         * "zero megabytes of image cache" is a **plausible** request — an app that re-downloads
         * every icon does not look broken, it looks slow. Should that option ever be offered, it
         * cannot be this field's zero.
         *
         * The maximum: beyond a gigabyte the icon cache would exceed any downloaded catalogue,
         * i.e. the level this screen exists to show.
         */
        val IMAGE_CACHE_MB_RANGE: IntRange = 32..1024

        /**
         * The values the screen offers.
         *
         * [DEFAULT_IMAGE_CACHE_MB] is among them on purpose, as with the search timeout: choosing
         * it explicitly writes `200` where `0` was and changes nothing.
         */
        val IMAGE_CACHE_MB_CHOICES: List<Int> = listOf(64, DEFAULT_IMAGE_CACHE_MB, 512, 1000)
    }
}

/**
 * How long to keep an expired listing.
 *
 * An enum rather than a number of days, because on an `int32` zero means "never written" and
 * **two** legitimate choices would land there — "zero days" and "forever". When a legitimate
 * choice would fall on zero, the field cannot be a number.
 */
enum class CatalogRetention(val duration: Duration?) {
    SEVEN_DAYS(7.days),

    /** The default, matching the retention of `health_events`: a usefulness limit. */
    THIRTY_DAYS(30.days),

    NINETY_DAYS(90.days),

    /** Throw nothing away. `null` is not "zero": it is "no expiry". */
    KEEP(null),
}

/**
 * The four levels the user can act on separately.
 *
 * Levels, not tables: each has its own technology, eviction policy and rebuild cost, and merging
 * them into a single "clear the cache" would make it impossible to say what pressing it costs.
 * Refilling the images is a few hundred kilobytes; refilling the F-Droid catalogue is 18 MB
 * compressed.
 */
enum class StorageLevel {
    /** Room: local index, listings, versions, screenshots. */
    CATALOG,

    /** Coil, on disk and in memory. */
    IMAGES,

    /** OkHttp's HTTP cache: the store pages. */
    PAGES,

    /** APKs in `files/staging` that no in-flight download is using. */
    STAGED_APKS,
}

/**
 * How much each level occupies, right now.
 *
 * A snapshot and not a `Flow`: none of the four levels notifies when it changes — they are
 * directories on disk — and the only way to "observe" them would be to re-measure on a timer. The
 * screen re-reads them when it opens and after each action, the two moments when the number can
 * have changed for a reason the user knows about.
 */
data class StorageUsage(
    val catalogBytes: Long = 0,
    val imagesBytes: Long = 0,
    val pagesBytes: Long = 0,
    val stagedApkBytes: Long = 0,
) {
    val totalBytes: Long get() = catalogBytes + imagesBytes + pagesBytes + stagedApkBytes

    fun bytesOf(level: StorageLevel): Long = when (level) {
        StorageLevel.CATALOG -> catalogBytes
        StorageLevel.IMAGES -> imagesBytes
        StorageLevel.PAGES -> pagesBytes
        StorageLevel.STAGED_APKS -> stagedApkBytes
    }

    companion object {
        val UNKNOWN = StorageUsage()
    }
}

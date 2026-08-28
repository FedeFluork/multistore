package com.multistore.store.apkcombo

import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.VersionRef
import com.multistore.store.common.html.Urls

/**
 * How apkcombo identifies an app, and how that is wrapped into an opaque ref.
 *
 * A listing lives at `/{slug}/{packageName}/`, and the two segments do not carry equal weight.
 * A non-canonical slug does not 404 but **301**s to the right one.
 * The ref keeps both anyway, so as not to ask the store for a redirect we can avoid.
 *
 * `StoreAppRef` is opaque to the core; that in here it *is* a path is a detail of this store, and
 * it does not leave this file.
 */
internal object ApkComboRefs {

    /** From a listing URL to the ref: `https://apkcombo.com/telegram/org.telegram.messenger/`. */
    fun refFromUrl(url: String): StoreAppRef? {
        val segments = Urls.segments(url)
        if (segments.size < PATH_SEGMENTS) return null
        val slug = segments[segments.size - 2]
        val packageName = segments.last()
        if (!looksLikePackageName(packageName)) return null
        return StoreAppRef("$slug/$packageName")
    }

    /** The `slug/packageName` path to use in URLs. */
    fun pathOf(ref: StoreAppRef): String? =
        ref.value.trim('/').takeIf { it.count { c -> c == '/' } == 1 && looksLikePackageName(it.substringAfterLast('/')) }

    /** The `packageName`, which apkcombo puts in the URL and repeats in the information table. */
    fun packageNameOf(ref: StoreAppRef): String? =
        pathOf(ref)?.substringAfterLast('/')

    /**
     * The reference to a downloadable variant.
     *
     * Two parts separated by `#`: the **page segment** — `apk` for the current version,
     * `phone-12.9.2-apk` for an older one — and the **object key**, i.e. the file name inside the
     * signed URL.
     *
     * ### Why not the version code, which was the first choice
     *
     * Because it **is not unique**, and the defect only showed on the device. A download page lists
     * up to eight variants differing by ABI and format, and more than one shares a version code:
     * one app publishes the same code three times.
     *
     * `app_versions` has a unique constraint on `(listing_id, version_ref)`. With the version code
     * as discriminator, saving three variants left **one**, the last written — and for one app that
     * was the group's only XAPK. The result on screen was "this store publishes no installable
     * package for this app" in front of a page offering five.
     *
     * The R2 object key is per file: stable between signatures — only the query's signing
     * parameters change — and different for every variant.
     */
    fun versionRef(pageSegment: String, objectKey: String?): VersionRef =
        VersionRef(if (objectKey.isNullOrBlank()) pageSegment else "$pageSegment#$objectKey")

    fun pageSegmentOf(ref: VersionRef?): String =
        ref?.value?.substringBefore('#')?.takeIf { it.isNotBlank() && it.isSafeSegment() }
            ?: ApkComboConfig.LATEST_VERSION_SEGMENT

    /** The object key, used to find the variant again when the page is reopened. */
    fun objectKeyOf(ref: VersionRef?): String? =
        ref?.value?.substringAfter('#', "")?.takeIf { it.isNotBlank() }

    /**
     * A plausible `packageName`: at least one dot, and only characters Android allows.
     *
     * Not validator pedantry. The search page also contains links to category and tag pages, which
     * have the same two-segment shape: without this check the adapter would return "Communication"
     * among the results, with a ref leading nowhere.
     */
    private fun looksLikePackageName(value: String): Boolean =
        value.contains('.') && PACKAGE_NAME.matches(value)

    private fun String.isSafeSegment(): Boolean = SAFE_SEGMENT.matches(this)

    private const val PATH_SEGMENTS = 2
    private val PACKAGE_NAME = Regex("""[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)+""")
    private val SAFE_SEGMENT = Regex("""[A-Za-z0-9._-]+""")
}

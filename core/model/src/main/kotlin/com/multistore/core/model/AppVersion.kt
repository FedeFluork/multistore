package com.multistore.core.model

import kotlin.time.Instant

/**
 * A downloadable version of an app, as one store publishes it.
 *
 * Two fields deserve attention because they come from a measurement of the F-Droid index rather
 * than from a prediction:
 *
 *  - **[versionCode] is not unique within a package.** `juloo.keyboard2` publishes versionCode 50
 *    twice, with two different signers and two different file names (a reproducible build signed
 *    by the developer *and* one signed by F-Droid). A version's identity is the triple
 *    `(listing, versionCode, signerSha256)`, or [sha256] directly where the store publishes it.
 *  - **[releaseChannels] is not decorative.** 28 versions in the index sit in the `Beta` channel,
 *    including the highest of `org.fdroid.fdroid` (2.0-rc0, versionCode 2000040), while the
 *    version F-Droid actually suggests is 1.23.2 (1023052). Ignoring the channel means offering a
 *    release candidate as an ordinary update.
 */
data class AppVersion(
    val versionName: String,
    val versionCode: Long?,
    /** Opaque ref to hand back to the adapter when resolving the download. */
    val ref: VersionRef,
    val artifactType: ArtifactType = ArtifactType.APK,
    /**
     * The size to **display**, not to verify against.
     *
     * It can be approximate: apkcombo publishes `119 MB` rounded to the megabyte, i.e.
     * 124,780,544 bytes against a real 124,351,530. Using it as the expected value makes a
     * complete file look truncated, and the diagnosis that comes out is "no connection".
     *
     * The verification expectation is a different field,
     * `DownloadResolution.Direct.expectedSize`, which an adapter fills in **only** if the store
     * publishes an exact count.
     */
    val sizeBytes: Long? = null,
    val minSdk: Int? = null,
    val targetSdk: Int? = null,
    /** Required native ABIs. Empty = universal APK, installable anywhere. */
    val abis: List<String> = emptyList(),
    val sha256: Sha256? = null,
    val signerSha256: Sha256? = null,
    val publishedAt: Instant? = null,
    val changelog: LocalizedText = LocalizedText.EMPTY,
    val antiFeatures: List<AntiFeature> = emptyList(),
    /** Empty = default (stable) channel. Any value = a non-default channel. */
    val releaseChannels: Set<String> = emptySet(),
) {
    /** `true` if the version is in the stable channel, i.e. the one offered by default. */
    val isDefaultChannel: Boolean get() = releaseChannels.isEmpty()
}

/**
 * What is known about the device when deciding whether a version fits.
 *
 * A parameter rather than a direct read of `Build`, so the compatibility rule can be tested on
 * the JVM for every combination instead of only the one the current emulator happens to have.
 */
data class DeviceProfile(
    val sdkInt: Int,
    /** In preference order, like `Build.SUPPORTED_ABIS`. */
    val supportedAbis: List<String>,
    /**
     * `DisplayMetrics.densityDpi`, used to pick which resource split to install from a container.
     *
     * An XAPK carries **all** of them — Duolingo, measured 26/08/2026, has seven from `ldpi` to
     * `xxxhdpi` — and installing them all would hand the device six sets of resources it will
     * never use.
     *
     * Zero means "unknown" and is not a domain value: the density choice then falls back to the
     * largest available bucket, which is the prudent error — an app with oversized resources
     * looks fine, one with undersized resources looks blurry.
     */
    val densityDpi: Int = 0,
) {
    companion object {
        /** A conservative profile for tests and for contexts where the device is not known. */
        val UNKNOWN: DeviceProfile = DeviceProfile(sdkInt = Int.MAX_VALUE, supportedAbis = emptyList())
    }
}

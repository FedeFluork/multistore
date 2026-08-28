package com.multistore.core.model

/**
 * What a part inside a split container is for.
 *
 * ### The census that decided these six values, 26/08/2026
 *
 * Two real containers, downloaded with the app's own client and opened:
 *
 * | | XAPK (apkcombo, Duolingo 6.93.6) | APKM (apkmirror, Firefox 154.0) |
 * |---|---|---|
 * | compression | **`store`** | **`deflate`** |
 * | base | `com.duolingo.apk` | `base.apk` |
 * | splits | `config.<abi>.apk`, `config.<dpi>.apk` | `split_config.<abi>.apk`, `split_config.<dpi>.apk` |
 * | metadata | `manifest.json` (`xapk_version` 2) | `info.json` (`apkm_version` 5) |
 * | extras | `icon.png`, `APKComboInstaller.url` | `icon.png`, `APKM_installer.url`, `META-INF/` |
 * | total | 238 MB for 180 useful on arm64 | 286 MB compressed, **624 uncompressed** |
 *
 * The two base names differ — `<packageName>.apk` against `base.apk` — which is why [BASE] is
 * declared by the container rather than inferred from the file name.
 *
 * **Neither carries an [EXPANSION].** The only real OBB among the nine stores is an1's, and it
 * does not live in a container but on a second download page.
 */
enum class SplitKind {
    /** The base APK: without it there is nothing to install. */
    BASE,

    /** Native code for one ABI. **One** is installed, the device's. */
    ABI,

    /** Resources for one density bucket. **One** is installed. */
    DENSITY,

    /**
     * Resources for one language. **All of them are installed.**
     *
     * Play installs one and fetches the rest on demand; we have no such channel, and a user who
     * changes their system language would find the app in English with no way to fix it short of
     * reinstalling. Neither measured container had a single one — languages live in the base — so
     * the rule costs nothing measurable.
     */
    LANGUAGE,

    /**
     * A feature module, or a split that could not be classified.
     *
     * **It is installed**, the prudent default in the right direction: one split too many costs
     * space, one split too few is a missing part of the app — and the symptom arrives long after
     * installation, inside the app, where nobody connects it to us.
     */
    FEATURE,

    /** Game data destined for `Android/obb/<package>/`. */
    EXPANSION,

    /** `manifest.json`, `icon.png`, `META-INF/`: none of this gets installed. */
    METADATA,
}

/**
 * One part of a container.
 *
 * [tag] is what the container declares after `config.` — `arm64_v8a`, `xxhdpi`, `it` — and stays
 * `null` for base, metadata and expansions.
 */
data class BundlePart(
    val entryName: String,
    val kind: SplitKind,
    val sizeBytes: Long,
    val tag: String? = null,
)

/**
 * What will be installed out of a container, and what will not.
 *
 * It reaches the UI because it is news that concerns the user: 238 MB were downloaded and 180
 * will be installed, and the difference is not waste but the price of a format that carries
 * every architecture at once.
 */
data class BundleSummary(
    val artifactType: ArtifactType,
    val install: List<BundlePart>,
    val skipped: List<BundlePart>,
    val expansions: List<BundlePart>,
) {
    val installBytes: Long get() = install.sumOf { it.sizeBytes }
    val expansionBytes: Long get() = expansions.sumOf { it.sizeBytes }
    val hasExpansions: Boolean get() = expansions.isNotEmpty()
}

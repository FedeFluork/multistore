package com.multistore.core.installer.container

import com.multistore.core.model.SplitKind

/**
 * How a split's identifier is read.
 *
 * The two measured formats write it differently — `config.arm64_v8a` in an XAPK,
 * `split_config.arm64_v8a.apk` in an APKM — but the part that counts is the same: the one after
 * `config.`. What bears the weight is **looking for a marker** instead of cutting at the first dot:
 * a feature module's split is called `<module>.config.<tag>`, and `split(".")[1]` on that would
 * return `config`.
 *
 * `lastIndexOf` and not `indexOf`, and it has to be said that this **is not a defence**: on every
 * real identifier the two give the same answer, because `config.` appears only once. The injection
 * swapping them stays green, and stays green for the right reason. It is written this way because if
 * one day an id with two markers appeared the last would be the split's; but no measurement says
 * that day will come.
 */
internal fun splitTag(id: String): String? {
    val at = id.lastIndexOf(CONFIG_MARKER)
    if (at < 0) return null
    return id.substring(at + CONFIG_MARKER.length).takeIf { it.isNotEmpty() }
}

/**
 * What a split is for, given its tag.
 *
 * A tag that is not recognised becomes [SplitKind.FEATURE], i.e. **it is installed**. It is the
 * prudent default in the right direction: one split too many costs space, one split too few is a part
 * of the app that is missing — and the symptom of the latter arrives long after installation, inside
 * the app, where nobody links it to whoever installed it.
 */
internal fun kindOfTag(tag: String?): SplitKind = when {
    tag == null -> SplitKind.FEATURE
    tag in ABI_TAGS -> SplitKind.ABI
    tag in DENSITY_TAGS -> SplitKind.DENSITY
    LANGUAGE_TAG.matches(tag) -> SplitKind.LANGUAGE
    else -> SplitKind.FEATURE
}

/**
 * The density buckets Android defines, with their `densityDpi`.
 *
 * `nodpi` and `anydpi` are deliberately absent: they are not buckets but declarations of density
 * independence, and do not appear as splits.
 */
internal val DENSITY_BUCKETS: Map<String, Int> = linkedMapOf(
    "ldpi" to 120,
    "mdpi" to 160,
    "tvdpi" to 213,
    "hdpi" to 240,
    "xhdpi" to 320,
    "xxhdpi" to 480,
    "xxxhdpi" to 640,
)

private const val CONFIG_MARKER = "config."

/**
 * Android's four ABIs, **with the underscore**: in splits `arm64-v8a` is written `arm64_v8a`,
 * because the hyphen is not allowed in a split name.
 */
private val ABI_TAGS: Set<String> = setOf("armeabi_v7a", "arm64_v8a", "x86", "x86_64")

private val DENSITY_TAGS: Set<String> = DENSITY_BUCKETS.keys

/** `en`, `pt_BR`; not `b+sr+Latn`: that is written `config.b_sr_Latn` and falls under FEATURE. */
private val LANGUAGE_TAG = Regex("[a-z]{2,3}(_[A-Za-z0-9]{2,8})?")

/** `arm64_v8a` -> `arm64-v8a`: as `Build.SUPPORTED_ABIS` writes it. */
internal fun abiOfTag(tag: String): String = tag.replace('_', '-')

package com.multistore.core.ui

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import com.multistore.core.model.UsesPermission

/**
 * A permission as it can be shown to a person: what Android calls it, and how much it grants.
 *
 * [label] is `null` for a permission this device knows nothing about — one declared by another app,
 * or introduced by a newer Android than this one. That is not an error to hide: the row shows the
 * name instead, and saying "unknown" would be less informative than the identifier itself.
 */
data class PermissionEntry(
    val permission: UsesPermission,
    val label: String?,
    val description: String?,
    val dangerous: Boolean,
) {
    /** What to draw: the system's own wording where there is one, the identifier where there is not. */
    val displayName: String get() = label ?: permission.shortName
}

/**
 * What the platform says about the permissions a build asks for.
 *
 * ### The labels come from Android, not from `strings.xml`, and that is the point
 *
 * `PermissionInfo.loadLabel` returns the same wording the system's own permission dialogs use, in
 * the device's language, for every permission this Android version knows. A table of our own would
 * be several hundred strings in five languages, would disagree with what the system says when it
 * asks the same question a minute later, and would go stale with every Android release. It is not a
 * shortcut around rule 1: rule 1 is about **our** interface text, and these are the names of other
 * people's API surface, authored and translated by the platform.
 *
 * ### "Sensitive" is the platform's judgement too
 *
 * `PROTECTION_DANGEROUS` is exactly the class Android itself gates behind a runtime prompt —
 * location, contacts, microphone, camera. Deciding it here from a hand-written list would be this
 * project inventing a taxonomy in a place where a verified one already exists, and the list would
 * be wrong the day a permission changes class.
 *
 * ### The three failure modes, all of them ordinary
 *
 *  - a permission the device does not know: `NameNotFoundException`. Common — every store's
 *    catalogue contains apps declaring their own permissions — so the entry keeps its identifier and
 *    is **not** called dangerous, because nothing said it was.
 *  - a label the platform has none for: the identifier is shown.
 *  - `maxSdk` below this device: the permission is not requested here at all and does not appear.
 *    See [UsesPermission.isRequestedOn].
 */
object PermissionCatalog {

    /**
     * The permissions a device on [sdkInt] would really be asked for, sensitive ones first.
     *
     * The ordering is the grouping: "sensitive" is the only distinction that changes what a reader
     * should do, and a longer taxonomy would be a list of headings for a list of a dozen rows. Ties
     * break on the displayed name so two runs give the same order — `PackageManager` makes no
     * promise about the order it returns things in, and a list that reshuffles between two openings
     * of the same listing reads as a list of different apps.
     */
    fun describe(
        context: Context,
        permissions: List<UsesPermission>,
        sdkInt: Int,
    ): List<PermissionEntry> = permissions
        .filter { it.isRequestedOn(sdkInt) }
        .map { entry(context.packageManager, it) }
        .sortedWith(compareByDescending<PermissionEntry> { it.dangerous }.thenBy { it.displayName })

    private fun entry(packageManager: PackageManager, permission: UsesPermission): PermissionEntry {
        val info = runCatching {
            packageManager.getPermissionInfo(permission.name, 0)
        }.getOrNull() ?: return PermissionEntry(
            permission = permission,
            label = null,
            description = null,
            // Not dangerous, and that is a deliberate default rather than a fallback: this device
            // has never heard of the permission, so nothing has judged it. Marking it sensitive
            // would put an alarm on every app that declares one of its own.
            dangerous = false,
        )
        return PermissionEntry(
            permission = permission,
            // `loadLabel` returns the identifier itself when there is no label, which would make the
            // row say the same thing twice; `takeIf` sends it back down the `displayName` path.
            label = info.loadLabel(packageManager).toString()
                .takeIf { it.isNotBlank() && it != permission.name },
            description = info.loadDescription(packageManager)?.toString()?.takeIf { it.isNotBlank() },
            dangerous = info.isDangerous(),
        )
    }

    /**
     * `true` for the class Android itself gates behind a runtime prompt.
     *
     * ### Two defects in one line, and the lint found the first
     *
     * `PermissionInfo.getProtection()` exists **from API 28** and `minSdk` is 26 — the same family as
     * `longVersionCode` and `GET_SIGNING_CERTIFICATES`, and caught the same way: by lint, before a
     * device. The deprecated `protectionLevel` field loses nothing here, because below 28 it carries
     * the base level and the flags packed into one `int` rather than being absent.
     *
     * ### And that packing is why this is an equality and not an `and`
     *
     * The first draft wrote `(protection and PROTECTION_DANGEROUS) != 0`, which reads like a flag
     * test and is not one: the base level is a small enumeration — `NORMAL` 0, `DANGEROUS` 1,
     * `SIGNATURE` 2 — not a bit field. `PROTECTION_SIGNATURE_OR_SYSTEM` is **3**, so `3 and 1` is
     * `1` and every one of those would have been marked sensitive. The flags (`PROTECTION_FLAG_*`)
     * live in the high bits, which is what `PROTECTION_MASK_BASE` strips before the comparison.
     */
    @Suppress("DEPRECATION")
    private fun PermissionInfo.isDangerous(): Boolean =
        (protectionLevel and PermissionInfo.PROTECTION_MASK_BASE) == PermissionInfo.PROTECTION_DANGEROUS
}

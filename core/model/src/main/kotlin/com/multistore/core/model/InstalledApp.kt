package com.multistore.core.model

import kotlin.time.Instant

/**
 * An app installed **through MultiStore**.
 *
 * That is the perimeter: "My apps" does not list what the user installed elsewhere. The device's
 * other apps stay visible only where they are genuinely needed, i.e. on the detail screen when it
 * has to say "already installed, version X".
 *
 * [updateChannelRef] is what makes update handling correct in a multi-store context: an app taken
 * from one store is updated **from that same store**. The first store with a higher versionCode
 * would almost always have a different signer, and the update would fail at OS level.
 */
data class InstalledApp(
    val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    val signerSha256: Sha256?,
    val installedAt: Instant,
    val installerKind: InstallerKind,
    val sourceStoreId: StoreId? = null,
    val sourceRef: StoreAppRef? = null,
    val apkSha256: Sha256? = null,
    /**
     * The listing this app updates from, when it is known.
     *
     * The three fields come from the row pointed to by `installed_apps.update_channel_listing_id`,
     * not from [sourceStoreId]/[sourceRef]. They coincide until someone changes channel, and stop
     * coinciding at the exact moment someone does — the only moment the difference matters.
     *
     * They stay `null` when the channel points at a listing a sync has deleted. No foreign key
     * prevents that, and none should: a package withdrawn from the store is no reason to forget
     * the user has it installed.
     */
    val updateChannelListingId: Long? = null,
    val updateChannelStoreId: StoreId? = null,
    val updateChannelRef: StoreAppRef? = null,
    /** The user asked not to be notified about this app's updates. */
    val ignoreUpdates: Boolean = false,
    /**
     * The `versionCode` beyond which the user asked not to go.
     *
     * Read by `VersionSelection`: it is a decision a person made, and it holds even against what
     * the rule would pick on its own.
     */
    val pinnedVersionCode: Long? = null,
    /**
     * The icon of the listing the app came from, not the one the system draws.
     *
     * They are the same image in the vast majority of cases, but only one of the two is a URL:
     * `PackageManager`'s is a `Drawable`, and routing it through a screen's state would mean
     * holding bitmaps in there. It stays `null` for apps whose listing is not (or no longer) in
     * the local catalogue, and there the row shows the placeholder.
     */
    val iconUrl: String? = null,
)

/**
 * What the operating system says about an installed package.
 *
 * Used by the detail screen ("installed / updatable / not installed") and by the signature check
 * of the pre-install pipeline. Separate from [InstalledApp] because it answers a different
 * question: not "what did I install" but "what is on the device right now".
 */
data class InstalledPackage(
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val signerSha256: Sha256?,
)

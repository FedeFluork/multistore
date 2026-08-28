package com.multistore.core.data.repository

import com.multistore.core.model.InstalledApp
import com.multistore.core.model.InstalledPackage
import com.multistore.core.model.InstallerKind
import com.multistore.core.model.Sha256
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import kotlinx.coroutines.flow.Flow

/**
 * The apps installed **through MultiStore**, and what the system says about packages in general.
 *
 * The scope of "My apps" is an explicit choice: it lists only what came through here. The
 * `PackageManager` is still needed, for two different things:
 *
 *  1. **reconciling** — an app can disappear without going through us (uninstalled from the system
 *     settings, removed with the secondary user). Without reconciliation the list would show ghosts
 *     and the update check would try to update them;
 *  2. **answering "already installed"** on the detail screen, and providing the current signer to
 *     the verification pipeline — where the authoritative source is the operating system, never a
 *     column of ours, which says only what was there when we wrote it.
 */
interface InstalledAppsRepository {

    fun observe(): Flow<List<InstalledApp>>

    suspend fun get(packageName: String): InstalledApp?

    /** The list read once: it is what an update check needs. */
    suspend fun all(): List<InstalledApp>

    /**
     * The installed app corresponding to **this listing**, if there is one.
     *
     * It is [get]'s inverse path, and it is needed where [get] cannot be used: four stores out of
     * nine do not publish the `packageName`, so from one of their listings there is no telling which
     * package to look for. From the listing, though, one can work back, because the installation
     * recorded it.
     */
    suspend fun forListing(storeId: StoreId, ref: StoreAppRef): InstalledApp?

    /**
     * What is on the device now, read from the `PackageManager`.
     *
     * `null` if the package is not installed. The signer is the certificate's SHA-256 in DER, the
     * same form `apksig` produces for an archive: it is the only way for the pre-install pipeline's
     * two comparisons to be talking about the same thing.
     */
    suspend fun installedPackage(packageName: String): InstalledPackage?

    /** Removes from the list the rows of packages that are no longer on the device. */
    suspend fun reconcile()

    /** Records a successful installation, with its provenance. */
    suspend fun record(
        packageName: String,
        label: String,
        storeId: StoreId,
        ref: StoreAppRef,
        listingId: Long?,
        apkSha256: Sha256?,
        installerKind: InstallerKind,
    )

    suspend fun forget(packageName: String)

    /** Suspends update notices for this app. It does not stop it being installed by hand. */
    suspend fun setIgnoreUpdates(packageName: String, ignore: Boolean)

    /**
     * Pins the app to a `versionCode`, or removes the pin with `null`.
     *
     * Unlike [setIgnoreUpdates], this changes **what is offered**: it is read by `VersionSelection`,
     * so it applies to the detail screen too and not only to the periodic check.
     */
    suspend fun setPinnedVersionCode(packageName: String, versionCode: Long?)

    /**
     * Changes the store this app will be updated from.
     *
     * `false` if that listing does not exist in the local catalogue: a channel pointing at nothing
     * would not fail visibly, it would simply stop updating that app forever.
     *
     * **Whoever changes channel has to be warned of the signature risk**: two stores redistributing
     * the same app almost never sign it with the same key, and the update would fail at the operating
     * system level. This method cannot warn anybody — the warning belongs to the UI, before calling
     * it.
     */
    suspend fun setUpdateChannel(packageName: String, storeId: StoreId, ref: StoreAppRef): Boolean
}

package com.multistore.core.domain.usecase

import com.multistore.core.data.repository.InstalledAppUpdate
import com.multistore.core.data.repository.InstalledAppsRepository
import com.multistore.core.data.repository.UpdateCheckReport
import com.multistore.core.data.repository.UpdateRepository
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/**
 * What there is to update, and the two decisions the user can take about it.
 *
 * The three things live together because they arrive together in the same list row: the row says
 * whether there is an update, and the two commands it offers — "pause the notices" and "pin at this
 * version" — change that very answer. Separating them would force every screen to put them back
 * together, which is the work a use case exists to do once.
 *
 * [setIgnoreUpdates] and [setPinnedVersionCode] are not two ways of saying the same thing:
 *
 *  - **pausing** does not change which version is the best, it changes whether to disturb the user.
 *    It is read by the update check, which does not even query that app; the detail screen goes on
 *    offering the "Update" button, because whoever gets there opened it on purpose.
 *  - **pinning** really does change what is offered, everywhere: it is read by `VersionSelection`, so
 *    it applies to the listing too. The pin says "no further", not "nothing" — if there is something
 *    at the pin newer than what is installed, that is offered all the same.
 */
class ObserveUpdatesUseCase @Inject constructor(
    private val updates: UpdateRepository,
    private val installedApps: InstalledAppsRepository,
) {

    /** Every app installed through MultiStore, updatable or not. */
    operator fun invoke(): Flow<List<InstalledAppUpdate>> = updates.observeAll()

    /** Only those with an update to offer. */
    fun available(): Flow<List<InstalledAppUpdate>> = updates.observeAvailable()

    /** Queries the channels. Downloads nothing and installs nothing. */
    suspend fun check(force: Boolean = false): UpdateCheckReport = updates.check(force)

    suspend fun setIgnoreUpdates(packageName: String, ignore: Boolean) =
        installedApps.setIgnoreUpdates(packageName, ignore)

    suspend fun setPinnedVersionCode(packageName: String, versionCode: Long?) =
        installedApps.setPinnedVersionCode(packageName, versionCode)

    /**
     * Changes the store this app will be updated from.
     *
     * `false` if that listing is not in the local catalogue. **The caller must have warned the user
     * of the signature risk**: two stores redistributing the same app almost never sign it with the
     * same key, and then the update is refused by the operating system.
     */
    suspend fun setUpdateChannel(packageName: String, storeId: StoreId, ref: StoreAppRef): Boolean =
        installedApps.setUpdateChannel(packageName, storeId, ref)
}

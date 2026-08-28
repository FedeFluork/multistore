package com.multistore.core.domain.usecase

import com.multistore.core.data.repository.InstallRepository
import com.multistore.core.data.repository.InstallStep
import com.multistore.core.data.repository.InstalledAppsRepository
import com.multistore.core.model.InstalledApp
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/**
 * Uninstalls, and keeps the list aligned.
 *
 * The permission needed is `REQUEST_DELETE_PACKAGES`, not `DELETE_PACKAGES`: the latter is
 * `signature|privileged` and is never granted to a normal app — with `targetSdk 36`
 * `PackageInstaller.uninstall()` would throw `SecurityException`.
 */
class UninstallAppUseCase @Inject constructor(
    private val installs: InstallRepository,
) {
    operator fun invoke(packageName: String): Flow<InstallStep> = installs.uninstall(packageName)
}

/**
 * "My apps", reconciled with the device.
 *
 * [reconcile] has to be called when the screen comes back to the foreground, not only at startup:
 * between one visit and the next the user may have uninstalled something from the system settings,
 * and the list would show an app that is not there.
 */
class ObserveInstalledAppsUseCase @Inject constructor(
    private val installedApps: InstalledAppsRepository,
) {
    operator fun invoke(): Flow<List<InstalledApp>> = installedApps.observe()

    suspend fun reconcile() = installedApps.reconcile()

    suspend fun setIgnoreUpdates(packageName: String, ignore: Boolean) =
        installedApps.setIgnoreUpdates(packageName, ignore)
}

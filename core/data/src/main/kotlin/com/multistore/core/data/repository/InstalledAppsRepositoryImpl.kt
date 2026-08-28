package com.multistore.core.data.repository

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.multistore.core.common.coroutine.IoDispatcher
import com.multistore.core.database.dao.CatalogDao
import com.multistore.core.database.dao.InstalledAppDao
import com.multistore.core.database.dao.InstalledAppRow
import com.multistore.core.database.entity.InstalledAppEntity
import com.multistore.core.model.InstalledApp
import com.multistore.core.model.InstalledPackage
import com.multistore.core.model.InstallerKind
import com.multistore.core.model.Sha256
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@Singleton
internal class InstalledAppsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: InstalledAppDao,
    private val catalogDao: CatalogDao,
    private val clock: Clock,
    @IoDispatcher private val io: CoroutineDispatcher,
) : InstalledAppsRepository {

    private val packageManager: PackageManager get() = context.packageManager

    override fun observe(): Flow<List<InstalledApp>> =
        dao.observeAll().map { rows -> rows.map { it.toModel() } }

    override suspend fun get(packageName: String): InstalledApp? =
        withContext(io) { dao.row(packageName)?.toModel() }

    override suspend fun all(): List<InstalledApp> = withContext(io) { dao.all().map { it.toModel() } }

    override suspend fun forListing(storeId: StoreId, ref: StoreAppRef): InstalledApp? =
        withContext(io) { dao.forListing(storeId, ref.value)?.toModel() }

    override suspend fun installedPackage(packageName: String): InstalledPackage? = withContext(io) {
        val info = packageInfo(packageName) ?: return@withContext null
        InstalledPackage(
            packageName = packageName,
            versionName = info.versionName,
            versionCode = longVersionCode(info),
            signerSha256 = signerOf(info),
        )
    }

    /**
     * Realigns the table with what the device really has.
     *
     * Two things, and the second is not an extra:
     *
     *  - **what is no longer there goes away.** An app can disappear without going through us,
     *    uninstalled from the system settings, and would remain haunting "My apps" and the list the
     *    update check queries.
     *  - **what has changed is rewritten.** A package can be updated — or downgraded — without going
     *    through us: another store, a sideload, `adb install`. The three version columns say what it
     *    was at **our** last installation, and on their own they would never find out. The fault is
     *    silent and credible: the row announces "1.3" while the phone has 1.2, and the comparison with
     *    the store — which reads the `PackageManager` instead — would say something different from the
     *    sentence in front of the user.
     */
    override suspend fun reconcile() = withContext(io) {
        val known = dao.packageNames()
        if (known.isEmpty()) return@withContext

        val present = known.mapNotNull { name -> packageInfo(name)?.let { name to it } }
        if (present.size != known.size) {
            // `retainOnly` with an empty list would delete everything, and that is exactly what is
            // needed when the user has uninstalled the last app that came through here.
            dao.retainOnly(present.map { it.first })
        }

        for ((name, info) in present) {
            val row = dao.get(name) ?: continue
            val versionCode = longVersionCode(info)
            val versionName = info.versionName.orEmpty()
            val signer = signerOf(info)
            val unchanged = row.installedVersionCode == versionCode &&
                row.installedVersionName == versionName &&
                row.installedSignerSha256 == signer
            // Only what has changed is written: `installed_apps` feeds a `Flow`, and rewriting
            // identical rows would make "My apps" recompose at every launch.
            if (!unchanged) dao.setInstalledVersion(name, versionName, versionCode, signer?.hex)
        }
    }

    override suspend fun record(
        packageName: String,
        label: String,
        storeId: StoreId,
        ref: StoreAppRef,
        listingId: Long?,
        apkSha256: Sha256?,
        installerKind: InstallerKind,
    ) = withContext(io) {
        val info = packageInfo(packageName)
        val existing = dao.get(packageName)
        // Where this app will be updated from. The caller can pass it, but almost never has it: they
        // know the opaque store and ref, not the row id. Resolving it here is what makes the
        // multi-store rule true — an app taken from a store is updated **from that one**, because the
        // first store with a higher versionCode would almost always have a different signer and the
        // update would fail at the operating system level. Leaving it `null` compiled, installed, and
        // would only have shown up the day `UpdateCheckWorker` exists.
        val identity = catalogDao.listingIdentity(storeId, ref.value)
        dao.upsert(
            InstalledAppEntity(
                packageName = packageName,
                appKey = existing?.appKey ?: identity?.appKey,
                label = label,
                sourceStoreId = storeId,
                sourceRef = ref.value,
                // What the system reports, not what we thought we were installing: if the installation
                // produced something different, the row has to say so.
                installedVersionName = info?.versionName.orEmpty(),
                installedVersionCode = info?.let(::longVersionCode) ?: 0L,
                installedSignerSha256 = info?.let(::signerOf),
                installedApkSha256 = apkSha256,
                installedAt = clock.now(),
                installerKind = installerKind,
                // The order matters: a channel chosen **explicitly** by the user must not be
                // overwritten by the deduced one, but a channel never set has to be filled in.
                updateChannelListingId = listingId
                    ?: existing?.updateChannelListingId
                    ?: identity?.listingId,
                // The user's choices survive an update: whoever had paused an app's updates does not
                // find them switched back on because they updated it.
                ignoreUpdates = existing?.ignoreUpdates ?: false,
                pinnedVersionCode = existing?.pinnedVersionCode,
            ),
        )
    }

    override suspend fun forget(packageName: String) = withContext(io) { dao.delete(packageName) }

    override suspend fun setIgnoreUpdates(packageName: String, ignore: Boolean) =
        withContext(io) { dao.setIgnoreUpdates(packageName, ignore) }

    override suspend fun setPinnedVersionCode(packageName: String, versionCode: Long?) =
        withContext(io) { dao.setPinnedVersionCode(packageName, versionCode) }

    /**
     * Changes the store this app will be updated from.
     *
     * The channel is expressed as `(storeId, ref)` and not as a row id because that is what whoever
     * changes it has to hand: the user has just looked at a listing, not at a table. Resolution to an
     * id happens here, and it is also the point at which a channel that does not exist is refused
     * rather than written — an `update_channel_listing_id` pointing at nothing makes nothing fail
     * visibly, it simply never updates that app again.
     */
    override suspend fun setUpdateChannel(
        packageName: String,
        storeId: StoreId,
        ref: StoreAppRef,
    ): Boolean = withContext(io) {
        val listingId = catalogDao.listingId(storeId, ref.value) ?: return@withContext false
        dao.setUpdateChannel(packageName, listingId)
        true
    }

    private fun packageInfo(packageName: String): PackageInfo? = try {
        packageManager.getPackageInfo(packageName, signingFlags())
    } catch (notFound: PackageManager.NameNotFoundException) {
        null
    }

    /**
     * The flag to ask for the certificates with, which **changes at API 28**.
     *
     * Here, unlike reading an archive, `apksig` is not an alternative: the package is already
     * installed and the file is not necessarily reachable. The branch therefore has to be written,
     * and written properly — on API 26 and 27 `GET_SIGNING_CERTIFICATES` is zero and the signer would
     * always come out absent, i.e. a signature check that always passes.
     */
    @Suppress("DEPRECATION")
    private fun signingFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }

    /**
     * The signer's fingerprint: SHA-256 of the certificate in DER.
     *
     * The same form `ApksigApkArchiveReader` computes for an archive — Android's `Signature` *is* the
     * DER-encoded certificate — because the two values are compared with each other at step 5 of the
     * pipeline, and two digests computed over different encodings would never match.
     *
     * `apkContentsSigners` is taken, i.e. who signed **the file installed now**, and not
     * `signingCertificateHistory`, which is the key lineage. They are two different questions: the
     * first is "which key is what is there signed with", and it is the one the pre-install pipeline
     * has to answer; the second serves to establish whether a new key is the legitimate heir of the
     * old one, and lives on the side of the archive to be installed.
     */
    @Suppress("DEPRECATION")
    private fun signerOf(info: PackageInfo): Sha256? {
        val der: ByteArray? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
        } else {
            info.signatures?.firstOrNull()?.toByteArray()
        }
        return der?.let { Sha256.ofBytes(MessageDigest.getInstance("SHA-256").digest(it)) }
    }

    @Suppress("DEPRECATION")
    private fun longVersionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()

    /**
     * From row to model, **channel included**.
     *
     * The channel comes from the join on `update_channel_listing_id`, not from a second copy of
     * `source_ref`: they were the same thing while nobody could change it, and from now on they are
     * not.
     */
    private fun InstalledAppRow.toModel() = InstalledApp(
        packageName = app.packageName,
        label = app.label,
        versionName = app.installedVersionName,
        versionCode = app.installedVersionCode,
        signerSha256 = app.installedSignerSha256,
        installedAt = app.installedAt,
        installerKind = app.installerKind,
        sourceStoreId = app.sourceStoreId,
        sourceRef = app.sourceRef?.takeIf { it.isNotBlank() }?.let(::StoreAppRef),
        apkSha256 = app.installedApkSha256,
        updateChannelListingId = app.updateChannelListingId,
        updateChannelStoreId = channelStoreId,
        updateChannelRef = channelRef?.takeIf { it.isNotBlank() }?.let(::StoreAppRef),
        ignoreUpdates = app.ignoreUpdates,
        pinnedVersionCode = app.pinnedVersionCode,
        iconUrl = iconUrl,
    )
}

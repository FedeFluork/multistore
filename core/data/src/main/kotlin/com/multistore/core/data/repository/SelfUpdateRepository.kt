package com.multistore.core.data.repository

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import com.multistore.core.model.OwnPackage
import com.multistore.core.remoteconfig.RemoteIndexStore
import com.multistore.core.remoteconfig.SelfUpdateRelease
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * There is a new version of MultiStore, and it can be installed on this device.
 *
 * [installedVersionCode] is there because the screen shows it next to the one offered: "0.1.0 →
 * 0.2.0" says what happens, "update available" does not.
 */
data class SelfUpdateOffer(
    val release: SelfUpdateRelease,
    val installedVersionCode: Long,
    val installedVersionName: String,
)

/**
 * MultiStore's own update check.
 *
 * ### Why it does not go through `UpdateCheckWorker`
 *
 * That worker queries the **installed apps' channels**, i.e. the rows of `installed_apps`.
 * MultiStore has none and must not have one — see the note on `InstallPlan.storeId` — so for it
 * MultiStore does not exist. The self-update was postponed precisely here: "there is no distribution
 * channel, a check against a URL would be a branch no configuration takes". Now the channel exists,
 * and it is `index.json`: **the same request the Home already makes**, not a second one.
 *
 * The consequence to know: the offer appears when the index updates, not at a rhythm of its own.
 * With the fetcher's six-hour interval it is exactly the frequency needed, and it costs no extra
 * request.
 */
interface SelfUpdateRepository {

    /**
     * The offer, or `null` — which covers five different cases with the same outcome: no index, the
     * index declares no release, the release is not newer than the installed one, it does not run on
     * this device, or the user has switched the check off.
     */
    val offer: Flow<SelfUpdateOffer?>
}

@Singleton
internal class SelfUpdateRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val index: RemoteIndexStore,
    private val settings: SettingsRepository,
    private val ownPackage: OwnPackage,
) : SelfUpdateRepository {

    override val offer: Flow<SelfUpdateOffer?> =
        combine(index.document, settings.remoteConfig) { document, remote ->
            // Two switches, and the first wins: with the index blocked there is nothing to read, and
            // asking the user to switch off the second as well would be asking them to switch off the
            // same thing twice. The Settings screen says so by disabling the entry.
            if (remote.blockRemoteIndex || remote.blockSelfUpdateCheck) return@combine null
            document?.selfUpdate?.let(::offerOf)
        }

    private fun offerOf(release: SelfUpdateRelease): SelfUpdateOffer? {
        if (release.url.isBlank() || release.versionCode <= 0) return null
        // A `minSdk` of zero means "not declared", not "runs everywhere": it is accepted, because we
        // write the document and the pipeline reads it from the APK. A declared value that is too
        // high instead rules the offer out before downloading five megabytes for nothing — and before
        // the verification step refuses it with a more obscure message.
        if (release.minSdk > 0 && release.minSdk > Build.VERSION.SDK_INT) return null

        val installed = installed() ?: return null
        if (release.versionCode <= installed.first) return null
        return SelfUpdateOffer(
            release = release,
            installedVersionCode = installed.first,
            installedVersionName = installed.second,
        )
    }

    /**
     * What is installed **now**, read from the `PackageManager` and not from `BuildConfig`.
     *
     * It is the same rule by which `VersionSelection` receives `installedVersionCode` from the
     * system: the number compiled into the APK is *this* process's, and after a successful update the
     * process answering is still the old one until it restarts.
     */
    private fun installed(): Pair<Long, String>? = runCatching {
        val info = context.packageManager.getPackageInfo(ownPackage.name, 0)
        versionCodeOf(info) to (info.versionName ?: "")
    }.getOrNull()

    /**
     * The `versionCode`, and **`longVersionCode` exists only from API 28**.
     *
     * The `minSdk` is 26. Written without the branch, on Android 8.0 and 8.1 the call does not exist
     * and the app goes into `NoSuchMethodError` at the first read — i.e. the self-update would be
     * broken precisely on the older devices, which are also the ones an app is installed on from
     * alternative sources most often. It is the same trap already met with
     * `GET_SIGNING_CERTIFICATES` in pre-install verification, where the consequence was worse: there
     * the two steps would have become **silent** no-ops.
     *
     * The deprecated field loses no information: up to API 27 the `versionCode` **is** 32-bit, and
     * the high bits `longVersionCode` adds do not exist on those systems.
     */
    @Suppress("DEPRECATION")
    private fun versionCodeOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else info.versionCode.toLong()
}

package com.multistore.core.data.mapper

import com.multistore.core.common.version.VersionSelection
import com.multistore.core.data.repository.VersionOffer
import com.multistore.core.model.AppVersion
import com.multistore.core.model.DeviceProfile
import com.multistore.core.model.InstalledPackage
import com.multistore.core.model.StoreListingDetail
import com.multistore.core.model.VersionSettings

/**
 * The only point in `:core:data` where a [VersionSelection.Request] is built.
 *
 * It exists because there are two callers doing the same thing for different reasons: the detail
 * screen decides what to show under the button, the update check decides whether to disturb the user.
 * If they each built the request on their own, sooner or later one of the two would forget an
 * argument — and the forgotten argument would be the version pin or the signer, i.e. exactly the ones
 * that exist to prevent damage.
 *
 * @param installed what the `PackageManager` says **now**. The caller must have looked it up with the
 * right `packageName`, which for four stores out of nine is not the listing's — those stores do not
 * publish it — but the one `installed_apps` learned from the APK.
 * @param settings the settings that change **which** version is the right answer. They are an
 * argument and not a read made in here because this function is pure and its two callers are
 * repositories that already have the flow to hand; they are an object and not a `Boolean` because the
 * next field of [VersionSettings] must change neither this signature nor the two callers'.
 */
internal fun selectVersion(
    listing: StoreListingDetail,
    device: DeviceProfile,
    installed: InstalledPackage?,
    pinnedVersionCode: Long?,
    settings: VersionSettings,
): VersionSelection.Outcome = VersionSelection.select(
    VersionSelection.Request(
        versions = listing.versions,
        device = device,
        preferredSigner = listing.preferredSignerSha256,
        installedSigner = installed?.signerSha256,
        installedVersionCode = installed?.versionCode,
        pinnedVersionCode = pinnedVersionCode,
        allowNonDefaultChannels = settings.allowPreviewChannels,
    ),
)

/**
 * Every published version, newest first, with the verdict for **this** device.
 *
 * It sits next to [selectVersion] because it is made of the same comparisons, and so as not to forget
 * the constraint tying them: the two answers must be able to coexist on the same screen without
 * contradicting each other. The version [selectVersion] offers is also
 * [VersionSelection.Installability.INSTALLABLE] here — and must stay so, because an "Install" button
 * at the top and a "cannot" on the same version ten rows below would be two answers to the same
 * question.
 *
 * The order is **decreasing version number**, with an absent `versionCode` at the bottom rather than
 * at zero: it is the same rule by which the search orders by rating — treating "the store does not
 * publish it" as "the lowest" would say something about those versions that nobody said.
 */
internal fun versionOffers(
    listing: StoreListingDetail,
    device: DeviceProfile,
    installed: InstalledPackage?,
): List<VersionOffer> = listing.versions
    .sortedWith(
        compareByDescending<AppVersion> { it.versionCode ?: Long.MIN_VALUE }
            .thenByDescending { it.publishedAt?.toEpochMilliseconds() ?: Long.MIN_VALUE }
            .thenBy { it.ref.value },
    )
    .map { version ->
        VersionOffer(
            version = version,
            installability = VersionSelection.installability(
                version = version,
                device = device,
                installedVersionCode = installed?.versionCode,
            ),
        )
    }

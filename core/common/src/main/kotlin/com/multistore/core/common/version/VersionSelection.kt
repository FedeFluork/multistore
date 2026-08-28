package com.multistore.core.common.version

import com.multistore.core.model.AppVersion
import com.multistore.core.model.ArtifactType
import com.multistore.core.model.DeviceProfile
import com.multistore.core.model.Sha256

/**
 * Which version to offer, out of everything a store publishes.
 *
 * It looks like "take the highest versionCode". It is not, and the difference is measurable: in
 * the real F-Droid index, **14 packages out of 4,257** have a raw maximum different from the
 * correct version. Two reasons, both able to break an installation:
 *
 *  1. **Release channel.** 28 versions sit in the `Beta` channel. The raw maximum of
 *     `org.fdroid.fdroid` is `2.0-rc0` (2000040); the version to offer is `1.23.2` (1023052).
 *  2. **Signer.** 15 packages have more than one signer: those are the cases where F-Droid
 *     publishes both the reproducible developer-signed build and its own. `juloo.keyboard2` goes
 *     as far as publishing **the same versionCode 50 twice**, under different keys. Offering the
 *     wrongly-signed version to a user who already has the app produces an update the operating
 *     system refuses.
 *
 * The rule below was compared against the official API's `suggestedVersionCode` on 20 packages:
 * it agrees on 17. All three divergences are cases where **the API is wrong for our purposes** —
 * it returns the raw maximum ignoring the signer (2 cases), or points at an OTA `.zip` that
 * `PackageInstaller` cannot install (1 case). Hence: `/api/v1/packages` is not an update oracle
 * for a client that respects signatures.
 */
object VersionSelection {

    data class Request(
        val versions: List<AppVersion>,
        val device: DeviceProfile,
        /** The signer the store recommends for a **fresh** installation. */
        val preferredSigner: Sha256? = null,
        /** The signer of the **already installed** package, if any. Beats [preferredSigner]. */
        val installedSigner: Sha256? = null,
        val installedVersionCode: Long? = null,
        /**
         * The `versionCode` the user **pinned** this app to, if they did.
         *
         * Not an ordinary filter: a decision a person made, to be respected even where the rule
         * on its own would choose otherwise.
         *
         * The pin says "no further", not "nothing": if a version newer than the installed one
         * exists at or below the pin, that one is offered.
         */
        val pinnedVersionCode: Long? = null,
        /** The user explicitly asked to see betas and non-default channels. */
        val allowNonDefaultChannels: Boolean = false,
        /**
         * Which artifacts can be installed.
         *
         * The default is **all four**. When it was `setOf(APK)` this line silently discarded
         * every container, and an app published only as a bundle came out as "no installable
         * artifact" — the one outcome of this function that sounds like a property of the store
         * rather than of us.
         *
         * It stays a parameter rather than a constant because it is the only way to **test** the
         * difference: the test covering the old behaviour passes `setOf(APK)`.
         */
        val supportedArtifactTypes: Set<ArtifactType> = ArtifactType.entries.toSet(),
    )

    sealed interface Outcome {
        /** There is an installable version. */
        data class Offer(val version: AppVersion, val isUpdate: Boolean) : Outcome

        /**
         * The app is installed and no published version is newer.
         *
         * [comparable] is `false` when the store **does not publish the `versionCode`** and the
         * comparison could not be made: all that is known is that there is no evidence to the
         * contrary. Not a textbook case — uptodown publishes it nowhere on the site, and without
         * this distinction every app taken from there would say "up to date" forever, with the
         * same confident face as one that really is.
         *
         * A field and not a separate outcome because it does not lead to a different action: it
         * changes the sentence, not what can be done.
         */
        data class UpToDate(val version: AppVersion, val comparable: Boolean = true) : Outcome

        /**
         * Compatible versions exist, but **none with the right signature**.
         *
         * Not "nothing to do": this is the case where the user should be offered uninstall and
         * reinstall, told they will lose their data. Showing it as a generic error would hide the
         * only possible action.
         */
        data class SignerConflict(val available: List<AppVersion>) : Outcome

        /** No version runs on this device (minSdk too high, ABI absent). */
        data object Incompatible : Outcome

        /** The store publishes no installable artifact for this listing. */
        data object NothingInstallable : Outcome

        /**
         * Installable versions exist, but **none in the default channel**.
         *
         * A separate outcome and not a `NothingInstallable`, because the two lead the user to two
         * different actions: "this store has no package for this app" is a dead end, "it only
         * exists as a beta" is a choice somebody might want to make. They are also more common
         * than they look: in the F-Droid index 28 versions sit in `Beta`, including the highest
         * of `org.fdroid.fdroid`.
         *
         * [channels] carries the names as the store publishes them — `Beta`, `Alpha` — because
         * naming them is what makes the sentence useful rather than alarming.
         */
        data class OnlyOtherChannels(
            val available: List<AppVersion>,
            val channels: Set<String>,
        ) : Outcome

        /**
         * The user pinned the app, and the pin is holding something back.
         *
         * It appears **only** when it changes the answer: when without the pin a version beyond
         * it would have been offered, and with the pin it is not. A pin on an app that has
         * nothing newer anyway stays invisible, and must — saying "pinned" when nothing is being
         * held back would be a warning about nothing.
         *
         * [offer] is what can still be done **within** the pin, and it is rarely nothing:
         * pinning at 100 with 90 installed means wanting to reach 100, not to stay at 90. It is
         * `null` only when nothing installable remains at or below the pin.
         *
         * [heldBack] is the version the pin is withholding, i.e. the reason this outcome exists:
         * without naming it, the user would have no way to know what they are giving up.
         */
        data class Pinned(
            val pinnedVersionCode: Long,
            val offer: Offer?,
            val heldBack: AppVersion,
        ) : Outcome
    }

    /**
     * The version to offer, taking the user's pin into account.
     *
     * The pin is applied by **comparing two answers** rather than by filtering at the input, and
     * that is the only way to blame the right thing. Filtering first, a beta newer than the pin
     * would produce a "pinned" outcome even though the release channel would have discarded it
     * anyway: the user would read that their pin is denying them an update they would never have
     * received.
     */
    fun select(request: Request): Outcome {
        val unpinned = decide(request.versions, request)
        val pin = request.pinnedVersionCode ?: return unpinned

        // The pin only speaks when it withholds: if the rule, unpinned, would not have offered
        // anything beyond it, the outcome is the ordinary one.
        val heldBack = (unpinned as? Outcome.Offer)
            ?.version
            ?.takeIf { (it.versionCode ?: Long.MIN_VALUE) > pin }
            ?: return unpinned

        val within = decide(
            request.versions.filter { (it.versionCode ?: Long.MIN_VALUE) <= pin },
            request,
        )
        return Outcome.Pinned(
            pinnedVersionCode = pin,
            offer = within as? Outcome.Offer,
            heldBack = heldBack,
        )
    }

    private fun decide(versions: List<AppVersion>, request: Request): Outcome {
        val installable = versions.filter { it.artifactType in request.supportedArtifactTypes }
        if (installable.isEmpty()) return Outcome.NothingInstallable

        val inChannel = installable.filter { request.allowNonDefaultChannels || it.isDefaultChannel }
        if (inChannel.isEmpty()) {
            return Outcome.OnlyOtherChannels(
                available = installable.sortedWith(BEST_FIRST(request.device)),
                channels = installable.flatMap { it.releaseChannels }.toSet(),
            )
        }

        val compatible = inChannel.filter { isCompatible(it, request.device) }
        if (compatible.isEmpty()) return Outcome.Incompatible

        val wantedSigner = request.installedSigner ?: request.preferredSigner
        val signed = compatible.filter { matchesSigner(it, wantedSigner) }
        if (signed.isEmpty()) {
            return Outcome.SignerConflict(compatible.sortedWith(BEST_FIRST(request.device)))
        }

        val best = signed.minWithOrNull(BEST_FIRST(request.device)) ?: return Outcome.NothingInstallable
        val installed = request.installedVersionCode
        return when {
            installed == null -> Outcome.Offer(best, isUpdate = false)
            (best.versionCode ?: Long.MIN_VALUE) > installed -> Outcome.Offer(best, isUpdate = true)
            // `comparable` carries the difference between "nothing newer exists" and "it
            // cannot be known": without the store's versionCode the comparison above is between
            // a number and nothing, and always ends the same way.
            else -> Outcome.UpToDate(best, comparable = best.versionCode != null)
        }
    }

    /**
     * What can be done with **this** version, looked at on its own.
     *
     * Used by the version history, where the user chooses by hand instead of receiving [select]'s
     * answer. The two functions answer different questions and must not be confused: [select]
     * says "which to offer out of all of them", this one says "if you press here, what happens".
     *
     * ### Why an "older than installed" outcome exists
     *
     * Because Android **does not replace an app with an earlier version**: the install session
     * fails, and neither `SessionInstaller` nor `ShellInstaller` passes a downgrade flag — the
     * first has no public one, the second creates the session with `-r` and not `-d`. Showing
     * those versions as installable would mean a tap, a whole download and a refusal from the
     * system at the end. They stay visible because knowing they were released is half the reason
     * to look at a history.
     *
     * ### The case where it cannot be known
     *
     * With a `versionCode` missing on either side the comparison is not made and the outcome is
     * [Installability.INSTALLABLE]: the same honesty as `UpToDate.comparable`, where a missing
     * number does not disguise itself as an answer. It concerns the stores that do not publish it
     * — uptodown and an1 across their whole sites — and there the system will decide, since it
     * has the real number.
     *
     * ### What this function does **not** look at
     *
     * The **signer**. A version signed by a key different from the installed one really exists —
     * `juloo.keyboard2` publishes the same versionCode 50 under two keys — and its outcome is not
     * "you cannot" but "uninstall and reinstall, losing your data". That is a choice with a
     * consequence, made by step 5 of the verification pipeline, which already has the sentence and
     * the gesture. Anticipating it here would say it twice, in two places that can diverge.
     */
    enum class Installability {
        /** It can be pressed. Whether the signature matches is for the verification pipeline. */
        INSTALLABLE,

        /** This is what is on the device right now. */
        INSTALLED,

        /** Android would refuse: the app has to be uninstalled first, losing its data. */
        OLDER_THAN_INSTALLED,

        /** `minSdk` too high, or no ABI in common with the device. */
        INCOMPATIBLE,

        /** An artifact type this app cannot open — today only `APKS`. */
        UNSUPPORTED_ARTIFACT,
    }

    fun installability(
        version: AppVersion,
        device: DeviceProfile,
        installedVersionCode: Long?,
        supportedArtifactTypes: Set<ArtifactType> = ArtifactType.entries.toSet(),
    ): Installability {
        if (version.artifactType !in supportedArtifactTypes) {
            return Installability.UNSUPPORTED_ARTIFACT
        }
        if (!isCompatible(version, device)) return Installability.INCOMPATIBLE

        val published = version.versionCode ?: return Installability.INSTALLABLE
        val installed = installedVersionCode ?: return Installability.INSTALLABLE
        return when {
            published == installed -> Installability.INSTALLED
            published < installed -> Installability.OLDER_THAN_INSTALLED
            else -> Installability.INSTALLABLE
        }
    }

    /**
     * Compatibility with the device.
     *
     * Missing `minSdk` = assumed compatible: the only real case on F-Droid is the 3 `.zip`
     * entries, already discarded earlier by type. Assuming the opposite would hide perfectly
     * installable apps on stores that do not publish minSdk.
     *
     * Empty `abis` = universal APK. An APK with native ABIs is compatible if it contains at least
     * one of the device's.
     */
    fun isCompatible(version: AppVersion, device: DeviceProfile): Boolean {
        val minSdk = version.minSdk
        if (minSdk != null && minSdk > device.sdkInt) return false
        if (version.abis.isEmpty()) return true
        if (device.supportedAbis.isEmpty()) return true
        return version.abis.any { it in device.supportedAbis }
    }

    private fun matchesSigner(version: AppVersion, wanted: Sha256?): Boolean {
        if (wanted == null) return true
        // Stores that do not publish the signer: nothing can be excluded here. The real check
        // stays the pre-install pipeline, which reads the signature off the downloaded APK.
        val signer = version.signerSha256 ?: return true
        return signer == wanted
    }

    /**
     * Orders from best version to worst.
     *
     * On equal `versionCode` — which really happens, see `juloo.keyboard2` — the APK whose first
     * ABI is closest to the device's preference wins: on an arm64 phone with
     * `InfinityLoop1309.NewPipeEnhanced`, which publishes one build per ABI, that is the
     * difference between downloading 30 useful MB and 30 MB that will not run.
     */
    private val BEST_FIRST: (DeviceProfile) -> Comparator<AppVersion> = { device ->
        compareByDescending<AppVersion> { it.versionCode ?: Long.MIN_VALUE }
            .thenBy { abiRank(it, device) }
            .thenByDescending { it.publishedAt?.toEpochMilliseconds() ?: Long.MIN_VALUE }
            .thenBy { it.ref.value }
    }

    private fun abiRank(version: AppVersion, device: DeviceProfile): Int {
        if (version.abis.isEmpty()) return UNIVERSAL_RANK
        val best = version.abis.mapNotNull { abi ->
            device.supportedAbis.indexOf(abi).takeIf { it >= 0 }
        }.minOrNull()
        return best ?: Int.MAX_VALUE
    }

    /**
     * A universal APK ranks *after* those with native ABIs but before the incompatible ones.
     *
     * Not first, because where both exist the per-ABI build is smaller and often faster; not
     * last, because it is installable all the same.
     */
    private const val UNIVERSAL_RANK = 1000
}

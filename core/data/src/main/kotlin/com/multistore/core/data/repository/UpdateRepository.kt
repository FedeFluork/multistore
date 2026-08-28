package com.multistore.core.data.repository

import com.multistore.core.common.result.AppError
import com.multistore.core.common.version.VersionSelection
import com.multistore.core.model.AppVersion
import com.multistore.core.model.InstalledApp
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import kotlinx.coroutines.flow.Flow

/**
 * The listing an installed app updates from.
 *
 * It is not just `(storeId, ref)`: the title and the icon are needed by anyone drawing an "update
 * available" row, and re-reading them from the UI would mean a second query for data this read
 * already has to hand.
 */
data class UpdateChannel(
    val storeId: StoreId,
    val ref: StoreAppRef,
    val listingId: Long,
    val title: String,
    val iconUrl: String?,
)

/**
 * What is known about **one** app installed through MultiStore, update-wise.
 *
 * ### Why two fields and not an enum
 *
 * "Is there an update?" has more than two answers, and the negative answers are not
 * interchangeable: "it is already up to date", "you paused it", "you pinned it at 100", "this store
 * does not publish the versionCode, so it cannot be known" and "I do not even know which listing to
 * look at it from" lead to five different sentences and five different actions.
 *
 * Instead of inventing an enum listing them, this type reuses the vocabulary that already exists:
 * [selection] is `VersionSelection`'s outcome **on the update channel**, with all its variants;
 * [InstalledApp.ignoreUpdates] stays separate because it is not a property of the version but of the
 * user — it does not change which version is the best, it changes whether we want to disturb them.
 */
data class InstalledAppUpdate(
    val app: InstalledApp,
    /** `null` when it is unknown which listing to update from: no channel, or channel gone. */
    val channel: UpdateChannel?,
    /** `null` when there is no channel to apply the rule to. */
    val selection: VersionSelection.Outcome?,
) {

    /**
     * The version the rule offers, pin included.
     *
     * A pin holding back 120 while the pin is at 100 and the device has 90 **is an available
     * update**: the user said "no further than 100", not "leave me at 90".
     */
    private val offer: VersionSelection.Outcome.Offer?
        get() = when (val outcome = selection) {
            is VersionSelection.Outcome.Offer -> outcome
            is VersionSelection.Outcome.Pinned -> outcome.offer
            else -> null
        }

    /**
     * The version to install, if there is an update to offer.
     *
     * `null` for a paused app: it is the only point where `ignore_updates` is **read**, and it is why
     * the column exists. It used to be written with nobody consulting it — a periodic check would
     * have updated the app the user had paused all the same.
     */
    val available: AppVersion?
        get() = if (app.ignoreUpdates) null else offer?.takeIf { it.isUpdate }?.version

    /** `true` when the update exists but the user has asked not to be disturbed. */
    val suppressed: Boolean
        get() = app.ignoreUpdates && offer?.isUpdate == true
}

/** A check round's outcome. It does not say what changed: the catalogue says that. */
data class UpdateCheckReport(
    /** How many apps actually had their channel queried. */
    val checked: Int,
    /**
     * The stores that did not answer, with why.
     *
     * Per store and not per app: if apkmirror is unreachable, the six apps coming from it fail for
     * the same reason, and six identical rows in a report help nobody understand what happened.
     */
    val failures: Map<StoreId, AppError> = emptyMap(),
) {
    val complete: Boolean get() = failures.isEmpty()
}

/**
 * What there is to update, and from where.
 *
 * ### The rule that makes this correct
 *
 * An app is updated **from the store it came from**, i.e. from its `update_channel_listing_id`, and
 * not from the first store publishing a higher `versionCode`. It is not an aesthetic preference: two
 * stores redistributing the same app almost never sign it with the same key, and an update with a
 * different signature is refused by the operating system, not by us. The user can change channel —
 * and is the only one who can, after being warned.
 *
 * ### What it does NOT do
 *
 * It installs and downloads nothing: it answers the question "what is new". Downloading and
 * installing is `InstallAppUseCase`'s job, which goes through the same verification pipeline as a
 * manually requested installation — verification is identical for all 9 stores, with no privileged
 * path.
 */
interface UpdateRepository {

    /** The state of **every** app installed through MultiStore, updatable or not. */
    fun observeAll(): Flow<List<InstalledAppUpdate>>

    /** Only those with an update to offer, in the order they should be shown. */
    fun observeAvailable(): Flow<List<InstalledAppUpdate>>

    /** The same as [observeAll], read once. */
    suspend fun all(): List<InstalledAppUpdate>

    /**
     * Queries the update channels and rewrites the local catalogue.
     *
     * It queries **only** the installed apps' channels, never the whole catalogue: it is the
     * difference between an update check and the mass crawling this project forbids.
     *
     * @param force ignore the listings' TTL. It serves the manual "check now" gesture, which has to
     * do something even when the data is formally fresh.
     */
    suspend fun check(force: Boolean = false): UpdateCheckReport
}

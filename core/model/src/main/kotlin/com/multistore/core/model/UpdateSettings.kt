package com.multistore.core.model

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/**
 * How often MultiStore checks whether anything needs updating.
 *
 * **[DAILY] is the first value, and the ordering is not incidental.** proto3 has no explicit
 * defaults: the zero value *is* the default. Writing the enum in its natural order — manual, 6
 * hours, 12 hours, daily, weekly — would have started the app in manual mode, i.e. with the
 * update check switched off.
 */
enum class UpdateInterval(val wireName: String) {
    DAILY("daily"),
    MANUAL("manual"),
    EVERY_6_HOURS("h6"),
    EVERY_12_HOURS("h12"),
    WEEKLY("weekly"),
    ;

    /**
     * How often to repeat the check, or `null` for [MANUAL].
     *
     * `null` is not "never": it is "not by itself". The "check now" button stays, and stays the
     * only way to make anything happen.
     */
    val period: Duration?
        get() = when (this) {
            MANUAL -> null
            EVERY_6_HOURS -> 6.hours
            EVERY_12_HOURS -> 12.hours
            DAILY -> 1.days
            WEEKLY -> 7.days
        }

    companion object {
        fun fromWireNameOrNull(wireName: String): UpdateInterval? =
            entries.firstOrNull { it.wireName == wireName }
    }
}

/**
 * How the update check behaves when it runs on its own.
 *
 * There is deliberately **no "Wi-Fi only"** switch: `metered_network_allowed` already means
 * "allow heavy traffic on a metered network", which covers exactly this question. Two overlapping
 * switches are two values that can diverge, with the check reading one and Settings the other —
 * the same reason store enablement never went into the DataStore.
 */
data class UpdateSettings(
    val interval: UpdateInterval = UpdateInterval.DAILY,
    /** Only check while the device is charging. */
    val onlyWhenCharging: Boolean = false,
    /**
     * Download what it finds, without waiting for a tap.
     *
     * It does not imply [autoInstall]: the file lands in staging and the listing offers "Install"
     * on an already-completed download. Two distinct decisions, because the second has a
     * prerequisite the first does not — an installer that asks no confirmation — and tying them
     * together would mean the first cannot be enabled on an ordinary device.
     */
    val autoDownload: Boolean = false,
    /**
     * Install by itself, showing nothing.
     *
     * Meaningful **only** with Shizuku or root: with just the system confirmation an "automatic"
     * update would be a screen appearing out of nowhere, and from API 34 it would not appear at
     * all from the background. The Settings screen disables the entry when no silent channel
     * exists, and the periodic check asks for `selectSilent`, which answers `null` rather than
     * degrading.
     */
    val autoInstall: Boolean = false,
    /**
     * Do not show the "updates available" notification.
     *
     * Negative for the same reason as the two security fields: the zero value is the default, and
     * the default needed here is "do notify". A field named `notifyUpdatesAvailable` would have
     * started off, leaving the feature invisible to anyone who never went looking for it.
     */
    val muteNotifications: Boolean = false,
) {
    /** The transfer starts on its own in both cases: installing presupposes downloading. */
    val downloadsByItself: Boolean get() = autoDownload || autoInstall
}

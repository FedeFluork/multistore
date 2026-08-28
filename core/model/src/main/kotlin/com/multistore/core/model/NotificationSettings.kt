package com.multistore.core.model

/**
 * What the app says when **nobody is watching**.
 *
 * That criterion holds the three fields together, and also decides what is not here. Every
 * notice below concerns an event that arose on its own — an overnight transfer, an update
 * installed while the phone was in a pocket, a store that did not answer the periodic check.
 * What the user has just asked for is told by the screen in front of them, in more detail than
 * fits a notification.
 *
 * All three are phrased **negatively**: the proto3 zero value is the default, and the default
 * needed here is "do notify". Named the other way round they would have started off, leaving
 * three notices invisible to anyone who never opened Settings.
 *
 * `muteUpdateNotifications` lives in [UpdateSettings] instead, because domain groups follow
 * **who reads them**: that one is read by the periodic check's final report. In Settings all
 * four appear in the same section, because there the question is a different one — "what does
 * MultiStore send me?" — and the two groupings are right not to coincide.
 */
data class NotificationSettings(
    /**
     * `true` = do not say that a file downloaded unattended is ready and waiting for a tap.
     *
     * Covers the unattended download-only path, and the one where silent installation is
     * unavailable and the file stays in staging. It does **not** cover a download the user has
     * just asked for.
     */
    val muteDownloadComplete: Boolean = false,
    /**
     * `true` = do not report the outcome of an unattended installation.
     *
     * Staying silent costs most on failure: an update refused for a signature mismatch does not
     * come back on its own, and without a notice the app falls behind unnoticed.
     */
    val muteInstallResult: Boolean = false,
    /**
     * `true` = do not say that a store failed to answer the periodic check.
     *
     * Not about searches — there the shortfall next to the results says it already. Here the set
     * of stores queried **is** the set of update channels of the installed apps, so a silent
     * store means apps that stop updating, and nothing else would say so.
     */
    val muteStoreAlerts: Boolean = false,
)

/**
 * How much the local diagnostic log records.
 *
 * A type of its own rather than a field of [NotificationSettings] because the consumer is
 * disjoint: it is read by the network layer on every request, not by whoever decides to show a
 * notice. Merging them would wake each on every change of the other.
 */
data class DiagnosticsSettings(
    /**
     * `true` = also record **successful** requests: address, status, duration.
     *
     * Off (the zero value) leaves the log doing what it always did: only what goes wrong. The
     * switch exists because the most common questions have the opposite shape — "why is search
     * so slow?" — and for those the failure log is empty precisely because nothing failed.
     */
    val logRequests: Boolean = false,
)

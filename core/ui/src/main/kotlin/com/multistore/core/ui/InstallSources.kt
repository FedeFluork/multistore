package com.multistore.core.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Taking the user to where "install unknown apps" is granted, without being able to bring the app
 * down.
 *
 * ### The defect that was there, and why it was invisible
 *
 * The button used to do `context.startActivity(Intent(ACTION_MANAGE_UNKNOWN_APP_SOURCES, …))` and
 * nothing else. On every device this project has been tried on that screen exists, so nothing ever
 * happened — but `startActivity` towards an intent nobody resolves throws
 * `ActivityNotFoundException`, and it would be **the app closing** on precisely the person already
 * having the problem that button is meant to solve.
 *
 * It is not a textbook case: it is the R6 risk — ROMs that obstruct `REQUEST_INSTALL_PACKAGES` — and
 * the ROMs that obstruct it are exactly the ones that might have moved or removed that screen. The
 * defence costs one `resolveActivity`.
 *
 * ### Three rungs, and the third is not a silent failure
 *
 * 1. the screen **for this app**, which is the right one: a single switch, already pointed at
 *    MultiStore;
 * 2. the app's page in the system settings, from which the same entry is one tap further. It exists
 *    everywhere, because it is the screen one uninstalls from;
 * 3. neither of the two: `false` is returned and the caller **says so**. A button that does nothing
 *    and does not say so is worse than an absent button.
 */
object InstallSources {

    /**
     * Opens the permission screen. Returns `false` if it does not exist on this device.
     *
     * `FLAG_ACTIVITY_NEW_TASK` because the caller may hold the application's `Context` and not an
     * Activity's — it is already the rule this app follows for the intents arriving from the system's
     * `PendingIntent`.
     */
    fun open(context: Context): Boolean {
        val target = Uri.fromParts(SCHEME_PACKAGE, context.packageName, null)
        val candidates = listOf(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, target),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, target),
        )
        for (intent in candidates) {
            val launchable = intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (context.packageManager.resolveActivity(launchable, 0) == null) continue
            // `resolveActivity` can say yes and `startActivity` fail all the same: between the two there
            // is an instant, and on a ROM filtering by caller the answer can depend on who is asking.
            // The `runCatching` is not belt and braces, it is the case the first half does not cover.
            if (runCatching { context.startActivity(launchable) }.isSuccess) return true
        }
        return false
    }

    private const val SCHEME_PACKAGE = "package"
}

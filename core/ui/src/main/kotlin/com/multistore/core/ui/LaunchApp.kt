package com.multistore.core.ui

import android.content.Context
import android.content.Intent

/**
 * Opening an app that is already on the device.
 *
 * ### Why it exists at all
 *
 * `getLaunchIntentForPackage` appeared **once** in the whole repository before this release, inside
 * the notification that reopens MultiStore. Everywhere else the last step of the flow the app exists
 * to complete — install it, then use it — happened outside the app: the user left, found the
 * launcher, and looked for the icon.
 *
 * ### Why `null` is a real answer and not an edge case
 *
 * A package with no launcher activity is ordinary, not theoretical: input methods, device
 * administrators, wallpapers, Wear companions and plenty of libraries-shipped-as-apps declare no
 * `CATEGORY_LAUNCHER` intent filter, and F-Droid publishes several. There is nothing to open, so the
 * button must not be drawn — the same rule [ExternalLinks.canOpen] applies to the store page, and
 * for the same reason: an absent button promises nothing, a disabled one makes people wonder what
 * they did wrong.
 *
 * ### Why the intent is not built by hand
 *
 * `PackageManager` returns the activity the launcher itself would start, with the flags the launcher
 * would use. Constructing `Intent(ACTION_MAIN).addCategory(CATEGORY_LAUNCHER).setPackage(…)` looks
 * equivalent and is not: it resolves to *a* matching activity rather than to the declared entry
 * point, and on an app with several it can open the wrong screen.
 */
object LaunchApp {

    /** `true` if [packageName] is installed **and** has something to open. */
    fun canOpen(context: Context, packageName: String?): Boolean =
        intentFor(context, packageName) != null

    /**
     * Opens the app. Returns `false` if there was nothing to open, or if the start failed.
     *
     * `runCatching` for the same reason as [ExternalLinks.open]: between the resolution and the
     * `startActivity` the package can be uninstalled, disabled, or hidden by a work-profile policy,
     * and the exception would land on a screen that is not expecting one.
     */
    fun open(context: Context, packageName: String?): Boolean {
        val intent = intentFor(context, packageName) ?: return false
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /**
     * `FLAG_ACTIVITY_NEW_TASK` because the caller may hold the application `Context` — the same
     * reason it is set in [ExternalLinks] and [InstallSources].
     */
    private fun intentFor(context: Context, packageName: String?): Intent? {
        if (packageName.isNullOrEmpty()) return null
        return runCatching { context.packageManager.getLaunchIntentForPackage(packageName) }
            .getOrNull()
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

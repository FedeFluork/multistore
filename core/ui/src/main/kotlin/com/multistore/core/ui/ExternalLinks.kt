package com.multistore.core.ui

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Opening an address in the user's browser, without being able to bring the app down.
 *
 * ### Why not just `startActivity`
 *
 * It is the same lesson as [InstallSources], applied to a different intent: `startActivity` towards
 * something nobody resolves throws `ActivityNotFoundException`. A device with no browser at all is
 * rare but exists — emulators with no Play image, corporate devices with the browser removed by
 * policy — and there an "open the store's page" button would close the app instead of opening a page.
 *
 * ### Why the browser and not a WebView
 *
 * `:feature:webviewdownload` exists for one thing: **intercepting the file** when the store demands a
 * human gesture. Here there is no gesture — one is reading the original page — and bringing it inside
 * the app would mean a WebView with no purpose, without the address bar with which the user sees
 * where they are, and without their cookies and their extensions. Outside is also the only place
 * where the user is master of what they are looking at.
 */
object ExternalLinks {

    /**
     * Opens [url] in the default browser. Returns `false` if there is nothing that would open it.
     *
     * `FLAG_ACTIVITY_NEW_TASK` for the same reason as [InstallSources.open]: the caller may hold the
     * application's `Context`.
     */
    /**
     * `true` if something on this device would open [url].
     *
     * It serves to **not draw** the button where it would do nothing, which is the only honest
     * alternative to an error message: this screen has no notice bar, and a button that does nothing
     * and does not say so is worse than an absent button (see [InstallSources]).
     */
    fun canOpen(context: Context, url: String): Boolean = intentFor(context, url) != null

    fun open(context: Context, url: String): Boolean {
        val intent = intentFor(context, url) ?: return false
        // `resolveActivity` can say yes and `startActivity` fail all the same: it is the same window
        // described in `InstallSources.open`.
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    private fun intentFor(context: Context, url: String): Intent? {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in ALLOWED_SCHEMES) return null
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return intent.takeIf { context.packageManager.resolveActivity(it, 0) != null }
    }

    /**
     * `http` and `https` only.
     *
     * The addresses passing through here are built by an adapter from a `StoreAppRef`, which is text
     * arrived from a downloaded page. A different scheme — `intent:`, `file:`, another app's scheme —
     * would be a way of making MultiStore open something MultiStore did not choose, and the contract
     * test would not catch it: that one looks at the host, not the scheme, and on a test bench the
     * host is `localhost`.
     */
    private val ALLOWED_SCHEMES = setOf("http", "https")
}

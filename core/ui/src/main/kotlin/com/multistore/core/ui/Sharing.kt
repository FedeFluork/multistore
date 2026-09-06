package com.multistore.core.ui

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Handing something to another app: a sentence, or a file.
 *
 * Before this release the repository contained **no** `ACTION_SEND` at all. Neither half of the gap
 * was cosmetic, and the second was the larger one: a verified APK sits in `filesDir/staging`, a
 * directory private to the app that no file manager can open, so "I have already downloaded it, let
 * me pass it on" had no answer at all.
 *
 * ### Two functions and not one
 *
 * They differ in more than the extra `Uri`. [shareFile] has to grant the receiving app a **temporary
 * read permission** on a `content://` URI it cannot otherwise open, and it has to declare a MIME
 * type, because the chooser filters on it — with `text/plain` on a file the list would offer apps
 * that cannot read an APK. Folding them together would mean one function with a nullable `Uri` whose
 * two halves share no line.
 *
 * ### Why `createChooser` and not the bare intent
 *
 * Without it Android may remember a default and send everything to the same app forever, which for a
 * one-off "send this to a friend" is the wrong memory to build. The chooser also gives the honest
 * failure: with nothing installed that can receive the content it simply says so, instead of
 * throwing `ActivityNotFoundException` at a screen that is not expecting one.
 */
object Sharing {

    /** Shares plain text. Returns `false` if nothing could be started. */
    fun shareText(context: Context, text: String, chooserTitle: String): Boolean {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = MIME_TEXT
            putExtra(Intent.EXTRA_TEXT, text)
        }
        return start(context, intent, chooserTitle)
    }

    /**
     * Shares a file, with [text] alongside it.
     *
     * [uri] must come from a `FileProvider`: a `file://` URI would make the receiving app throw
     * `FileUriExposedException` from API 24 on, and the failure would land on the *other* app.
     *
     * ### Which of the two things grants the read, measured
     *
     * `FLAG_GRANT_READ_URI_PERMISSION` is set here, and it is **not** what carries the weight:
     * `Intent.createChooser` calls `migrateExtraStreamToClipData` on an `ACTION_SEND`, which builds
     * the `ClipData` and adds that same flag by itself. Removing the line below leaves the outgoing
     * intent with `grant=1` and a clip data all the same — measured by injection, not assumed.
     *
     * It stays because it is what makes the intent correct **before** the chooser touches it, and
     * the day something here sends without a chooser it becomes load-bearing. What must not happen
     * is somebody reading it as the defence: the defence is the chooser, and a comment claiming
     * otherwise is the kind of caption this project treats as worse than no comment at all.
     */
    fun shareFile(
        context: Context,
        uri: Uri,
        mimeType: String,
        text: String,
        chooserTitle: String,
    ): Boolean {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return start(context, intent, chooserTitle)
    }

    private fun start(context: Context, intent: Intent, chooserTitle: String): Boolean {
        val chooser = Intent.createChooser(intent, chooserTitle)
            // The caller may hold the application `Context`: the same reason it is set in
            // `ExternalLinks`, `InstallSources` and `LaunchApp`.
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(chooser) }.isSuccess
    }

    private const val MIME_TEXT = "text/plain"

    /**
     * What an APK is, as far as a chooser is concerned.
     *
     * It is here rather than in `:feature:downloads` because it is a property of the thing being
     * shared, not of the screen sharing it — and because a container (`.xapk`, `.apkm`) is a zip
     * whose real type nobody agrees on, so the honest answer for those is the same one the store
     * gave us. See the caller.
     */
    const val MIME_APK: String = "application/vnd.android.package-archive"
}

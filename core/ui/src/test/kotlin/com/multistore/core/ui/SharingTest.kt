package com.multistore.core.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * What actually leaves the app when something is shared.
 *
 * ### Why this is worth a test at all
 *
 * Everything here is intent construction, which normally proves itself by working. What does not is
 * everything that fails **on the other side**: a `content://` URI the receiver may not open lands in
 * *their* stack trace, and a wrong MIME type makes the chooser offer apps that cannot read the file.
 * Neither shows up on this side, so neither would be noticed by using the feature.
 *
 * ### What these assertions do and do not prove
 *
 * The read grant is asserted as an **outcome**, not as a line of our code, and that distinction was
 * settled by injection: removing our `addFlags` leaves the outgoing intent with the flag anyway,
 * because `Intent.createChooser` calls `migrateExtraStreamToClipData` on an `ACTION_SEND` and adds it
 * itself. So this test would stay green on that edit — and it is still worth having, because what it
 * checks is the property the receiver depends on, whoever set it. The note is here so nobody reads
 * it as a guard on the line above.
 *
 * The chooser is asserted for a smaller but real reason: without it Android may remember a default
 * and send every share to the same app for ever, which for "send this to a friend once" is the wrong
 * memory to build.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SharingTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val application: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `sharing text goes out as a chooser over an ACTION_SEND`() {
        Sharing.shareText(context, "F-Droid on F-Droid\nhttps://f-droid.org", "Share")

        val chooser = requireNotNull(shadowOf(application).nextStartedActivity)
        assertThat(chooser.action).isEqualTo(Intent.ACTION_CHOOSER)

        val inner = requireNotNull(chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java))
        assertThat(inner.action).isEqualTo(Intent.ACTION_SEND)
        assertThat(inner.type).isEqualTo("text/plain")
        assertThat(inner.getStringExtra(Intent.EXTRA_TEXT)).contains("https://f-droid.org")
    }

    /**
     * The file's URI travels with a read grant and with the type the chooser filters on.
     *
     * A staged APK lives in `filesDir`, which no other app can read; the grant is what makes the URI
     * usable for as long as the receiving task lives, and no longer. Of the two, the **type** is the
     * one this test really guards — see the note on the class for who sets the grant.
     */
    @Test
    fun `sharing a file grants read access and declares the APK type`() {
        val uri = Uri.parse("content://com.multistore.test.staging/staging/1.apk")

        Sharing.shareFile(
            context = context,
            uri = uri,
            mimeType = Sharing.MIME_APK,
            text = "F-Droid, downloaded from F-Droid with MultiStore.",
            chooserTitle = "Share the file",
        )

        val chooser = requireNotNull(shadowOf(application).nextStartedActivity)
        val inner = requireNotNull(chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java))

        assertThat(inner.action).isEqualTo(Intent.ACTION_SEND)
        assertThat(inner.type).isEqualTo("application/vnd.android.package-archive")
        assertThat(inner.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)).isEqualTo(uri)
        // The property the receiver depends on. See the note on this class about who sets it.
        assertThat(inner.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)
    }

    /**
     * A package with nothing to open is a real case, not a defensive branch.
     *
     * Input methods, wallpapers and device administrators declare no launcher activity, and F-Droid
     * publishes several of them. The answer has to be "no" rather than a crash, because the caller
     * uses it to decide whether to draw the button at all.
     */
    @Test
    fun `an app with no launcher activity cannot be opened, and neither can a missing name`() {
        assertThat(LaunchApp.canOpen(context, "org.example.nothing.here")).isFalse()
        assertThat(LaunchApp.canOpen(context, null)).isFalse()
        assertThat(LaunchApp.canOpen(context, "")).isFalse()

        // And asking anyway does nothing rather than throwing: `startActivity` towards an intent
        // nobody resolves is `ActivityNotFoundException`, on a screen not expecting one.
        assertThat(LaunchApp.open(context, "org.example.nothing.here")).isFalse()
        assertThat(shadowOf(application).nextStartedActivity).isNull()
    }
}

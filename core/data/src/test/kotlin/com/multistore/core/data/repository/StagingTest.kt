package com.multistore.core.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import android.content.pm.PathPermission
import android.content.pm.ProviderInfo
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.multistore.core.data.R
import java.io.File
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/**
 * Letting a staged APK out of the app, and refusing to let anything else out.
 *
 * ### The provider is registered by hand here, and the reason is not a shortcut
 *
 * In the app the manifest's `${applicationId}.staging` and `context.packageName` are the same
 * string. Under Robolectric they are not: AGP resolves `${applicationId}` in a **unit-test**
 * manifest to the test application id (`…core.data.test`), while `context.packageName` stays
 * `…core.data`. Nothing about the product is wrong; the environment simply registers the provider
 * under an authority one suffix away from the one the code computes. Registering it under
 * [Staging.authority] restores the relationship the device has, and keeps the assertions about
 * behaviour rather than about the merger.
 *
 * ### Why the refusal is the part worth testing
 *
 * `FileProvider.getUriForFile` **throws** `IllegalArgumentException` for a path outside the declared
 * subtree, and the caller is a screen holding a `File` that came out of a database row — a row whose
 * path may have been written by an older version of the app, or by a code path that put the file
 * somewhere else. Left to throw, a Share button would close the app; caught, it simply is not drawn.
 *
 * The declared subtree is the other half of the security model: `staging_paths.xml` names
 * `files/staging` and nothing above it, so the Room database, the DataStore and the parsers cache
 * cannot be handed to another app by any URI this function can produce.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class StagingTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun registerProvider() {
        val info = ProviderInfo().apply {
            authority = Staging.authority(context)
            grantUriPermissions = true
            // The same paths file the manifest points at: a test registering a wider subtree would
            // prove nothing about the one that ships.
            metaData = android.os.Bundle().apply {
                putInt("android.support.FILE_PROVIDER_PATHS", R.xml.staging_paths)
            }
            pathPermissions = emptyArray<PathPermission>()
        }
        Robolectric.buildContentProvider(FileProvider::class.java).create(info)
    }

    @Test
    fun `the authority is derived from this build's package name`() {
        // Three variants live side by side on one device, and two providers claiming the same
        // authority make the second install fail with INSTALL_FAILED_CONFLICTING_PROVIDER.
        assertThat(Staging.authority(context)).isEqualTo("${context.packageName}.staging")
    }

    @Test
    fun `a staged file gets a content URI on this build's own authority`() {
        val file = File(Staging.dir(context), "1.apk").apply { writeText("not really an apk") }

        val uri = requireNotNull(Staging.shareableUri(context, file))

        assertThat(uri.scheme).isEqualTo("content")
        assertThat(uri.authority).isEqualTo(Staging.authority(context))
        assertThat(uri.path).endsWith("1.apk")
    }

    /** A container's pieces sit **next to** their download, so they are inside the subtree too. */
    @Test
    fun `a file extracted from a container is shareable as well`() {
        val download = File(Staging.dir(context), "7.apk")
        val splits = Staging.splitsOf(download).apply { mkdirs() }
        val base = File(splits, "base.apk").apply { writeText("base") }

        assertThat(Staging.shareableUri(context, base)).isNotNull()
    }

    /**
     * Anything outside staging gets `null`, not an exception.
     *
     * `filesDir` itself is the case that matters: it holds the database and the settings, and it is
     * exactly one directory above the one the provider declares.
     */
    @Test
    fun `a path outside staging is refused instead of throwing`() {
        val outside = File(context.filesDir, "multistore.db")

        assertThat(Staging.shareableUri(context, outside)).isNull()
        assertThat(Staging.shareableUri(context, File("/etc/hosts"))).isNull()
    }
}

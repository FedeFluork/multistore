package com.multistore.core.installer.verify

import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Reading a downloaded archive's permission list, and the three answers it can give.
 *
 * The one that matters is the third. `PackageManager.getPackageArchiveInfo` returns
 * `requestedPermissions = null` for a package that declares none, so the natural `?: return null`
 * turns "this build asks for nothing" into "nobody could tell" — the two sentences the whole column
 * exists to keep apart, inverted at the very point where the answer is produced.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PackageManagerPermissionsReaderTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val reader = PackageManagerPermissionsReader(context)

    @Test
    fun `an archive declaring permissions gives them back, without duplicates`() {
        val apk = archive(
            "android.permission.INTERNET",
            "android.permission.CAMERA",
            // A manifest can list the same permission twice; the row would otherwise be drawn twice.
            "android.permission.INTERNET",
            // And the platform has been seen to return blanks in that array.
            "",
        )

        val permissions = reader.read(apk)

        assertThat(permissions?.map { it.name })
            .containsExactly("android.permission.INTERNET", "android.permission.CAMERA")
            .inOrder()
        // No ceiling comes through this API — it returns names — so every entry is "still
        // requested". It is the prudent of the two errors, and it is why F-Droid's index, which does
        // publish the ceiling, is not read through here.
        assertThat(permissions?.all { it.maxSdk == null }).isTrue()
    }

    @Test
    fun `an archive that asks for nothing gives the empty list, not 'unknown'`() {
        // The platform answers `null` here, not an empty array, which is exactly the trap: passed
        // straight through it would say "nobody has read this build" about a build that has been
        // read and asks for nothing. On F-Droid that is an ordinary app.
        val apk = archive()

        assertThat(reader.read(apk)).isEmpty()
    }

    @Test
    fun `a file that is not a readable archive is 'unknown', and does not fail the installation`() {
        val broken = temp.newFile("truncated.apk").apply { writeText("not an apk") }

        // `null`, and no exception: nothing here refuses an installation, so an unreadable manifest
        // has to leave the pipeline alone and let the screen say it does not know. The opposite —
        // an empty list — would be the most reassuring sentence in the app, produced by a corrupt
        // download.
        assertThat(reader.read(broken)).isNull()
    }

    /**
     * A file the shadow `PackageManager` will answer for.
     *
     * Robolectric resolves `getPackageArchiveInfo` from a registered [PackageInfo] keyed by path, so
     * the file's *content* is irrelevant to these three cases — what is under test is how this class
     * reads the platform's answer, including the `null` that means "none".
     */
    private fun archive(vararg permissions: String): File {
        val file = temp.newFile("app-${permissions.size}-${permissions.hashCode()}.apk")
        file.writeText("apk")
        val info = PackageInfo().apply {
            packageName = "org.example.app"
            requestedPermissions = if (permissions.isEmpty()) null else arrayOf(*permissions)
        }
        shadowOf(context.packageManager).setPackageArchiveInfo(file.absolutePath, info)
        return file
    }
}

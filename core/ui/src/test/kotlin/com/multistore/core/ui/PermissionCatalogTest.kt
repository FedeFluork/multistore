package com.multistore.core.ui

import android.content.Context
import android.content.pm.PermissionInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.multistore.core.model.UsesPermission
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Turning a manifest's permission names into something a person can read.
 *
 * The two behaviours worth protecting are opposites, and both are silent when wrong: a permission
 * the device would **not** be asked for must not appear — otherwise the screen accuses an app of
 * wanting something it stopped wanting — and a permission the device has never heard of must not be
 * called sensitive, because nothing has judged it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PermissionCatalogTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `a permission capped below this device does not appear at all`() {
        // `WRITE_EXTERNAL_STORAGE` up to API 28 is the canonical case: scoped storage retired it,
        // thousands of apps still list it with a ceiling, and on a modern device it is simply not
        // requested. Only F-Droid publishes the ceiling — it is the reason the field is carried.
        val described = PermissionCatalog.describe(
            context = context,
            permissions = listOf(
                UsesPermission("android.permission.INTERNET"),
                UsesPermission("android.permission.WRITE_EXTERNAL_STORAGE", maxSdk = 28),
            ),
            sdkInt = 34,
        )

        assertThat(described.map { it.permission.name })
            .containsExactly("android.permission.INTERNET")
    }

    @Test
    fun `the same permission appears on a device old enough to be asked`() {
        // The other half, without which the filter could be "always drop anything with a ceiling"
        // and this suite would not notice.
        val described = PermissionCatalog.describe(
            context = context,
            permissions = listOf(UsesPermission("android.permission.WRITE_EXTERNAL_STORAGE", maxSdk = 28)),
            sdkInt = 28,
        )

        assertThat(described).hasSize(1)
    }

    @Test
    fun `a permission this device knows nothing about keeps its name and is not called sensitive`() {
        val described = PermissionCatalog.describe(
            context = context,
            permissions = listOf(UsesPermission("com.example.game.permission.C2D_MESSAGE")),
            sdkInt = 34,
        ).single()

        // `getPermissionInfo` throws `NameNotFoundException` for it, which is ordinary — every
        // store's catalogue is full of apps declaring their own. The row falls back to the last
        // segment, because the full dotted name is a line of text that pushes the row apart.
        assertThat(described.label).isNull()
        assertThat(described.displayName).isEqualTo("C2D_MESSAGE")
        // And it is **not** flagged: nothing on this device has judged it, and marking it would put
        // an alarm on every app that ships a permission of its own.
        assertThat(described.dangerous).isFalse()
    }

    /**
     * The base level is an enumeration, not a bit field, and this is the case that proves it.
     *
     * `PROTECTION_SIGNATURE_OR_SYSTEM` is **3**, so a test written as `level and PROTECTION_DANGEROUS
     * != 0` — which reads exactly like a flag test — answers `1`, and every permission of that class
     * would be marked sensitive. It is the shape of the bug the first draft of this file shipped,
     * and the lint that caught the *other* defect on the same line said nothing about it.
     *
     * The permission is **constructed**, and that is stated because it matters: no permission of
     * that class appears among the ones a real listing declares, so the platform's own table cannot
     * exercise this branch. Without a built one, the wrong version of the line stays green.
     */
    @Test
    fun `a signature-or-system permission is not sensitive, though its low bit is set`() {
        val name = "com.example.test.permission.SIGNATURE_OR_SYSTEM"
        Shadows.shadowOf(context.packageManager).addPermissionInfo(
            PermissionInfo().apply {
                this.name = name
                packageName = "com.example.test"
                @Suppress("DEPRECATION")
                protectionLevel = PermissionInfo.PROTECTION_SIGNATURE_OR_SYSTEM
            },
        )

        val described = PermissionCatalog.describe(
            context = context,
            permissions = listOf(UsesPermission(name)),
            sdkInt = 34,
        ).single()

        assertThat(described.dangerous).isFalse()
    }

    @Test
    fun `the sensitive ones come first, and the rest are in a stable order`() {
        val described = PermissionCatalog.describe(
            context = context,
            permissions = listOf(
                UsesPermission("android.permission.INTERNET"),
                UsesPermission("android.permission.READ_CONTACTS"),
                UsesPermission("android.permission.ACCESS_NETWORK_STATE"),
            ),
            sdkInt = 34,
        )

        // The ordering **is** the grouping: "sensitive" is the only distinction that changes what a
        // reader should do about a row.
        assertThat(described.first().permission.name).isEqualTo("android.permission.READ_CONTACTS")
        assertThat(described.first().dangerous).isTrue()
        // And the tail is sorted by what is shown, not by what came in: `PackageManager` promises
        // nothing about order, and a list that reshuffles between two openings of the same listing
        // reads as a list of different apps.
        val rest = described.drop(1).map { it.displayName }
        assertThat(rest).isInOrder()
    }
}

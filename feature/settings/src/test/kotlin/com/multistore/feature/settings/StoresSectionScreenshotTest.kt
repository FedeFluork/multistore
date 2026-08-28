package com.multistore.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import com.multistore.core.common.net.StoreHealth
import com.multistore.core.data.repository.StoreEntry
import com.multistore.core.model.StoreHealthState
import com.multistore.core.model.StoreId
import com.multistore.core.model.ThemeMode
import com.multistore.core.testing.ScreenshotTest
import org.junit.Test

/**
 * The "Stores" section with the three states the user can actually see.
 *
 * As for the Storage section, the `SettingsScreen` golden does not cover it in full: the screen is
 * taller than the device. Here the three cases sit together on purpose, because it is the
 * **comparison** that says whether the UI works:
 *
 * - a healthy, enabled store — no status row;
 * - a store **turned off by the user** — the switch is the only difference, and it has to read;
 * - a store with the **breaker open** — the row in red, which must stand out from the grey
 *   description above without becoming the only thing one sees.
 *
 * The third case is the one the dark theme can ruin: `colorScheme.error` on a dark background is a
 * different red from the one on a light background, and if nobody looks, nobody knows.
 */
class StoresSectionScreenshotTest : ScreenshotTest() {

    @Test
    fun lightTheme() = capture(SCREEN_NAME, ThemeMode.LIGHT) { Content() }

    @Test
    fun darkTheme() = capture(SCREEN_NAME, ThemeMode.DARK) { Content() }

    @Test
    fun dialogLight() = capture(DIALOG_SCREEN_NAME, ThemeMode.LIGHT) { Dialog() }

    @Test
    fun dialogDark() = capture(DIALOG_SCREEN_NAME, ThemeMode.DARK) { Dialog() }

    /**
     * The dialog, which is now **where the list lives**.
     *
     * Without this golden the three states described above would be photographed nowhere: the section
     * has become a single row, and the row does not show them. It is also the only surface in this
     * feature with two Compose roots, hence the only one on which the accessibility check looks at
     * something different from the rest.
     */
    @Composable
    private fun Dialog() {
        Surface(color = MaterialTheme.colorScheme.background) {
            StorePickerDialog(stores = SAMPLE, onDismiss = {}, onSave = {})
        }
    }

    /**
     * The `Surface` is not decoration: in the real screen the section sits inside a `Scaffold`, so it
     * paints no background of its own. Without it the dark golden would come out with light text on a
     * white background — the same trap already met on the Storage section.
     */
    @Composable
    private fun Content() {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column {
                StoresSection(stores = SAMPLE, onStoreEnabledChange = { _, _ -> })
            }
        }
    }

    private companion object {
        const val SCREEN_NAME = "StoresSection"
        const val DIALOG_SCREEN_NAME = "StorePickerDialog"

        val SAMPLE = listOf(
            StoreEntry(
                storeId = StoreId.FDROID,
                displayName = "F-Droid",
                host = "f-droid.org",
                enabled = true,
                health = StoreHealth(StoreId.FDROID),
            ),
            StoreEntry(
                storeId = StoreId.APKCOMBO,
                displayName = "APKCombo",
                host = "apkcombo.com",
                enabled = false,
                health = StoreHealth(StoreId.APKCOMBO),
            ),
            StoreEntry(
                storeId = StoreId.APKMIRROR,
                displayName = "APKMirror",
                host = "www.apkmirror.com",
                enabled = true,
                health = StoreHealth(StoreId.APKMIRROR, state = StoreHealthState.OPEN),
            ),
        )
    }
}

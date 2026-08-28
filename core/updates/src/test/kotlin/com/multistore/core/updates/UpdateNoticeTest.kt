package com.multistore.core.updates

import android.app.NotificationManager
import android.content.Context
import androidx.core.content.getSystemService
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.multistore.core.common.version.VersionSelection
import com.multistore.core.data.repository.InstalledAppUpdate
import com.multistore.core.data.repository.UpdateChannel
import com.multistore.core.domain.usecase.ObserveUpdatesUseCase
import com.multistore.core.model.AppVersion
import com.multistore.core.model.InstalledApp
import com.multistore.core.model.InstallerKind
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.model.VersionRef
import com.multistore.core.testing.FakeInstalledAppsRepository
import com.multistore.core.testing.FakeSettingsRepository
import com.multistore.core.testing.FakeUpdateRepository
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The notice put back in agreement with the list between one check and the next.
 *
 * The case this class exists to close was seen on the emulator: after updating an app from the
 * listing, the notification went on listing it. The notification drawer contradicted the screen the
 * user had just come from, and would have stayed that way until the next day's check.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class UpdateNoticeTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val settings = FakeSettingsRepository()
    private val updates = FakeUpdateRepository()
    private val installedApps = FakeInstalledAppsRepository()
    private val notifications = UpdateNotifications(context)

    private val notice = UpdateNotice(
        updates = ObserveUpdatesUseCase(updates, installedApps),
        settings = settings,
        notifications = notifications,
    )

    @Test
    fun `the notice lists what is there now`() = runTest {
        updates.state.value = listOf(update("AntennaPod"), update("Firefox"))

        notice.refresh()

        assertThat(shown()).contains("AntennaPod")
        assertThat(shown()).contains("Firefox")
    }

    @Test
    fun `a list that empties removes the notification, instead of leaving it lying`() = runTest {
        updates.state.value = listOf(update("AntennaPod"))
        notice.refresh()
        assertThat(shown()).isNotNull()

        // The user has just installed that update from the detail page.
        updates.state.value = emptyList()
        notice.refresh()

        assertThat(shown()).isNull()
    }

    @Test
    fun `'silence the notices' also removes the one already in the drawer`() = runTest {
        updates.state.value = listOf(update("AntennaPod"))
        notice.refresh()
        assertThat(shown()).isNotNull()

        // Switching the switch on while a notification is there has to make it disappear: leaving it
        // until the next check would be a switch that seems not to work.
        settings.updates.value = settings.updates.value.copy(muteNotifications = true)
        notice.refresh()

        assertThat(shown()).isNull()
    }

    private fun shown(): String? {
        val manager = requireNotNull(context.getSystemService<NotificationManager>())
        val notification = shadowOf(manager).allNotifications.firstOrNull() ?: return null
        return buildString {
            append(notification.extras.getCharSequence("android.title"))
            append(' ')
            append(notification.extras.getCharSequence("android.text"))
        }
    }

    private fun update(title: String) = InstalledAppUpdate(
        app = InstalledApp(
            packageName = "org.example.${title.lowercase()}",
            label = title,
            versionName = "1.0",
            versionCode = 1,
            signerSha256 = null,
            installedAt = Instant.fromEpochSeconds(1_756_000_000),
            installerKind = InstallerKind.SESSION,
        ),
        channel = UpdateChannel(
            storeId = StoreId.FDROID,
            ref = StoreAppRef(title),
            listingId = 1L,
            title = title,
            iconUrl = null,
        ),
        selection = VersionSelection.Outcome.Offer(
            version = AppVersion(versionName = "2.0", versionCode = 2, ref = VersionRef("v2")),
            isUpdate = true,
        ),
    )
}

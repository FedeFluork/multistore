package com.multistore.core.updates

import com.multistore.core.data.repository.SettingsRepository
import com.multistore.core.domain.usecase.ObserveUpdatesUseCase
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Puts the notice back in agreement with the list, outside the periodic check.
 *
 * ### Why the worker doing it is not enough
 *
 * The worker rewrites the notification once a day. Between one round and the next the list can get
 * shorter — the user installs an update from the Home, or updates an app elsewhere, or uninstalls it
 * — and the notification would go on listing apps with nothing left to update, with the notification
 * drawer contradicting the screen the user has just come from. Measured on the emulator: after
 * updating Tiny Music Player from the listing, the notification went on saying "2 updates available —
 * NotifyBuddy, Tiny Music Player".
 *
 * ### Why it is not an always-on observer
 *
 * The question "what is there to update" costs: the answer is recomputed at every catalogue
 * revision, and during an index sync that revision changes constantly. Keeping it listening for the
 * process's whole life would mean paying that computation even when nobody is watching.
 *
 * The right signal already exists and is punctual: **a package has changed**. It is the only moment
 * in which the list can get shorter without the worker being involved, and it is the same broadcast
 * `AppStartup` already observes to realign "My apps". One read per event, and none when nothing
 * happens.
 */
@Singleton
class UpdateNotice @Inject constructor(
    private val updates: ObserveUpdatesUseCase,
    private val settings: SettingsRepository,
    private val notifications: UpdateNotifications,
) {

    /**
     * Re-reads the list and rewrites (or removes) the notification.
     *
     * With the "silence the notices" switch on it shows nothing **and leaves nothing**: whoever
     * switches it on while a notification is in the drawer wants it to disappear, not to stay until
     * the next check. `showAvailable(emptyList())` is precisely the cancellation.
     */
    suspend fun refresh() {
        val muted = settings.updates.first().muteNotifications
        val titles = if (muted) emptyList() else updates.available().first().mapNotNull { it.channel?.title }
        notifications.showAvailable(titles)
    }
}

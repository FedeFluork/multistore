package com.multistore.core.updates

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The periodic check's notices: **what happened while nobody was watching**.
 *
 * There are four and not one, and the criterion holding them together is that: every line here tells
 * of an event born by itself. What the user has just asked for is told by the screen in front of
 * them, in more detail than fits in a notification.
 *
 * ### Three channels, four notices
 *
 * The channels are what the user governs from the system settings, and they have to be divided by
 * **kind of news**, not by number of features: "there is an update" and "the update has been
 * installed" are two moments of the same fact, but the first asks for an action and the second
 * concludes it — whoever wants to silence one rarely wants to silence the other. "A store is not
 * answering" is a third thing again, and whoever has an unstable store among the nine wants to be
 * able to switch it off without losing the updates.
 *
 * The "ready to install" notice shares its channel with the installation's outcome, and that is not a
 * compromise: they are the two alternative outcomes of the same moment — the file has arrived, and
 * either it installed itself or it is waiting for a tap.
 *
 * ### One notification per piece of news, not one per app
 *
 * Five updates are a single fact, and five rows in the notification drawer are five gestures to
 * remove them. The count goes in the title, the names in the body.
 *
 * ### `setOnlyAlertOnce`, and why it avoids a table
 *
 * The check runs every six hours or every day, and republishing the same notice on every round would
 * be harassment. The route that looks obvious — remembering in a column what we have already
 * announced — costs a migration and one more value to keep aligned; `setOnlyAlertOnce` achieves the
 * same thing in one line: the notification **updates silently** while it stays in the drawer, and
 * makes itself heard again only if the user dismissed it and there is something new to say.
 *
 * ### The permission is not a prerequisite
 *
 * As for the downloads: with `POST_NOTIFICATIONS` denied nothing bad happens here — the notification
 * is not seen and the check carries on. There is therefore no error branch, and the list on the Home
 * remains the source that does not depend on a permission.
 */
@Singleton
class UpdateNotifications @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    private val manager: NotificationManager? get() = context.getSystemService()

    /**
     * Shows (or updates) the notice.
     *
     * With [titles] empty it **removes** the notification instead of showing one saying zero: a check
     * that no longer finds anything — because the user updated elsewhere, or paused the app — has to
     * make the notice disappear, not leave it lying.
     */
    fun showAvailable(titles: List<String>) {
        val count = titles.size
        if (count == 0) {
            manager?.cancel(NOTIFICATION_ID)
            return
        }
        ensureChannel()

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(
                context.resources.getQuantityString(R.plurals.update_notification_title, count, count),
            )
            .setContentText(titles.joinToString(NAME_SEPARATOR))
            .setStyle(NotificationCompat.BigTextStyle().bigText(titles.joinToString(NAME_SEPARATOR)))
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .apply { launchIntent()?.let(::setContentIntent) }
            .build()

        manager?.notify(NOTIFICATION_ID, notification)
    }

    /**
     * "The file is there, a tap is missing."
     *
     * It is produced by the unattended path when it stops before installing — because the user asked
     * for the download only, or because there is no silent installer. In both cases the transfer is
     * finished, verification passed, and the only thing missing is a person: without this line, the
     * app would silently wait for a gesture nobody knows they have to make.
     *
     * It does not cover the downloads the user has just asked for: there the bar is on the listing they
     * davanti. Vedi `mute_download_notifications` in `settings.proto`.
     */
    fun showReadyToInstall(titles: List<String>) {
        val count = titles.size
        if (count == 0) {
            manager?.cancel(READY_NOTIFICATION_ID)
            return
        }
        ensureInstallChannel()

        val text = titles.joinToString(NAME_SEPARATOR)
        val notification = NotificationCompat.Builder(context, INSTALL_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(
                context.resources.getQuantityString(R.plurals.install_ready_title, count, count),
            )
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .apply { launchIntent()?.let(::setContentIntent) }
            .build()

        manager?.notify(READY_NOTIFICATION_ID, notification)
    }

    /**
     * How an unattended installation went.
     *
     * The two lists sit in the **same** notification and not in two, because they are a single fact —
     * "tonight's check did this" — and two rows in the drawer would be two gestures to remove one piece
     * of news. The title tells the worst case: where something failed, that is the line to read, and a
     * title saying "3 apps updated" above a failure hidden in the body would be the way of making sure
     * nobody reads it.
     *
     * With both lists empty it shows nothing: a check that installed nothing is not news.
     */
    fun showInstallResult(installed: List<String>, failed: List<String>) {
        if (installed.isEmpty() && failed.isEmpty()) return
        ensureInstallChannel()

        val title = if (failed.isNotEmpty()) {
            context.resources.getQuantityString(
                R.plurals.install_result_failed_title,
                failed.size,
                failed.size,
            )
        } else {
            context.resources.getQuantityString(
                R.plurals.install_result_title,
                installed.size,
                installed.size,
            )
        }
        val text = (failed + installed).joinToString(NAME_SEPARATOR)

        val notification = NotificationCompat.Builder(context, INSTALL_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .apply { launchIntent()?.let(::setContentIntent) }
            .build()

        manager?.notify(RESULT_NOTIFICATION_ID, notification)
    }

    /**
     * "These stores did not answer."
     *
     * It is not the same news as a store failing during a search, where the sign next to the results
     * already says so and a notification would be noise about a fact right in front of the user. Here
     * the set of queried stores **is** the set of the installed apps' update channels: a silent store
     * means apps that do not update, and nothing else would say so.
     *
     * The names are the ones the stores present themselves with — a brand, not translated text.
     */
    fun showStoreAlerts(storeNames: List<String>) {
        val count = storeNames.size
        if (count == 0) {
            manager?.cancel(STORE_NOTIFICATION_ID)
            return
        }
        ensureStoreChannel()

        val text = storeNames.joinToString(NAME_SEPARATOR)
        val notification = NotificationCompat.Builder(context, STORE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(
                context.resources.getQuantityString(R.plurals.store_alert_title, count, count),
            )
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            // As for "there are updates": it updates silently while it stays in the drawer. A store
            // down for a week must not sound seven times.
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .apply { launchIntent()?.let(::setContentIntent) }
            .build()

        manager?.notify(STORE_NOTIFICATION_ID, notification)
    }

    /**
     * Where the tap leads: our launch activity.
     *
     * Asked of the `PackageManager` instead of being built: this module does not know `:app` and must
     * not — the dependency rule says the only ones knowing the screens are `:app` and the features.
     * `getLaunchIntentForPackage` resolves the same thing without naming any class.
     */
    private fun launchIntent(): PendingIntent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return null
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(context, REQUEST_CODE, intent, flags)
    }

    private fun ensureInstallChannel() = ensure(
        id = INSTALL_CHANNEL_ID,
        nameRes = R.string.install_notification_channel_name,
        descriptionRes = R.string.install_notification_channel_description,
        // `LOW`: the outcome of something that has already happened must interrupt nothing. "Ready to
        // install" does ask for an action, but shares the channel — and between the two, the prudent
        // level is the one that does not wake anybody at four in the morning.
        importance = NotificationManager.IMPORTANCE_LOW,
    )

    private fun ensureStoreChannel() = ensure(
        id = STORE_CHANNEL_ID,
        nameRes = R.string.store_alert_channel_name,
        descriptionRes = R.string.store_alert_channel_description,
        importance = NotificationManager.IMPORTANCE_LOW,
    )

    private fun ensure(id: String, nameRes: Int, descriptionRes: Int, importance: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (manager?.getNotificationChannel(id) != null) return
        manager?.createNotificationChannel(
            NotificationChannel(id, context.getString(nameRes), importance).apply {
                description = context.getString(descriptionRes)
            },
        )
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (manager?.getNotificationChannel(CHANNEL_ID) != null) return
        manager?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.update_notification_channel_name),
                // `DEFAULT` and not `LOW` as for the downloads: a download in progress is something the
                // user has just started and is watching, an available update is news they did not ask
                // for and have to be able to notice. Whoever does not want it has the switch in
                // Settings, and the system's one for the channel.
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.update_notification_channel_description)
            },
        )
    }

    private companion object {
        const val CHANNEL_ID = "updates"

        /** The file has arrived: either it installed itself, or it is waiting for a tap. */
        const val INSTALL_CHANNEL_ID = "installs"

        /** A store is not answering the periodic check. */
        const val STORE_CHANNEL_ID = "stores"

        /**
         * A fixed id: there is only one notification.
         *
         * Outside the 8,000+ space `DownloadNotifications` reserved for itself by convention.
         */
        const val NOTIFICATION_ID = 9_000

        /**
         * Three distinct ids, and not one reused.
         *
         * The three pieces of news can coexist: tonight's check may have installed two apps, left one
         * waiting for a tap, and failed to reach a store. A single id would show one — the last written
         * — and the other two would disappear with nothing saying so.
         */
        const val READY_NOTIFICATION_ID = 9_001
        const val RESULT_NOTIFICATION_ID = 9_002
        const val STORE_NOTIFICATION_ID = 9_003
        const val REQUEST_CODE = 9_000
        const val NAME_SEPARATOR = ", "
    }
}

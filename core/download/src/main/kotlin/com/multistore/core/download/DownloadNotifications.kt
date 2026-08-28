package com.multistore.core.download

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
 * The notification that keeps a download alive when the user leaves the screen.
 *
 * The permission is not a prerequisite. Since Android 13 showing a notification requires
 * `POST_NOTIFICATIONS`, which the user can deny. The foreground service, however, does **not** require
 * it: with the permission denied the download continues and simply is not visible. That is why there
 * is no permission check and no error branch here — building the notification is always legitimate,
 * showing it is a decision.
 *
 * The channel is created here and not in the `Application` because creating it at startup would mean
 * every user sees the "Downloads" category in the system settings even if they have never downloaded
 * anything. Creating it on the first notification costs one idempotent call and shows the user only the
 * categories that really concern them.
 */
@Singleton
class DownloadNotifications @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    private val manager: NotificationManager? get() = context.getSystemService()

    /**
     * The notification's id, one per download.
     *
     * It derives from the row id and not from a counter: two downloads in progress have to produce two
     * distinct notifications, and the same row resumed after a restart has to update its own instead
     * of opening a second.
     */
    fun notificationId(downloadId: Long): Int = (NOTIFICATION_ID_BASE + downloadId).toInt()

    fun build(
        label: String?,
        progress: DownloadProgress?,
        cancelIntent: PendingIntent?,
    ): android.app.Notification {
        ensureChannel()
        val title = label
            ?.let { context.getString(R.string.download_notification_title, it) }
            ?: context.getString(R.string.download_notification_title_unknown)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            // A download is not a notice: it must not be dismissible with a swipe while it is running,
            // otherwise the only thing telling the user something is happening disappears.
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        when {
            progress == null -> builder.setProgress(0, 0, true)
            progress.fraction != null -> builder.setProgress(
                PROGRESS_MAX,
                (progress.fraction!! * PROGRESS_MAX).toInt(),
                false,
            )
            // Unknown size: indeterminate bar. A determinate bar that does not know where it ends
            // would lie twice, about the percentage and about the time remaining.
            else -> builder.setProgress(0, 0, true)
        }

        cancelIntent?.let {
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                context.getString(R.string.download_notification_cancel),
                it,
            )
        }
        return builder.build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val existing = manager?.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return
        manager?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.download_notification_channel_name),
                // `LOW`: no sound and no vibration. A download starting is not an event deserving to
                // interrupt whatever the user is doing.
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.download_notification_channel_description)
                setShowBadge(false)
            },
        )
    }

    private companion object {
        const val CHANNEL_ID = "downloads"
        const val PROGRESS_MAX = 1000

        /**
         * An id space reserved for downloads.
         *
         * Adding the row id to a base keeps the download notifications away from those other parts of
         * the app might show, with no need for a shared registry.
         */
        const val NOTIFICATION_ID_BASE = 8_000L
    }
}

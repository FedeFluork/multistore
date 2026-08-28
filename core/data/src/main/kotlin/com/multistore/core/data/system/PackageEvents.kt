package com.multistore.core.data.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.multistore.core.common.coroutine.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.shareIn

/**
 * When the set of installed packages changes.
 *
 * It exists because Room does not know. A detail screen puts together two sources — the catalogue,
 * which lives in the database, and what the `PackageManager` says **now** — and while the only
 * re-emission signal was the database, uninstalling an app left the listing saying "Installed:
 * 3.12.0" about a package that was no longer there. Seen on the emulator, not deduced.
 *
 * The system broadcast and not a signal of ours after a successful installation, for the case that
 * signal would not cover: the user can uninstall from the system settings while our listing is open,
 * and nothing of ours goes through there.
 *
 * `RECEIVER_NOT_EXPORTED`: system broadcasts arrive all the same, and no other app can send us a fake
 * "package removed".
 */
@Singleton
class PackageEvents @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:ApplicationScope private val scope: CoroutineScope,
) {

    /**
     * The changed package's name, or `null` if the broadcast does not carry it.
     *
     * Shared and without replay: it is an event, not a state. `WhileSubscribed` because a receiver
     * registered when no screen is watching would be work the system spends for nothing.
     */
    val changes: Flow<String?> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                trySend(intent?.data?.schemeSpecificPart)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            // Without the `package` scheme the filter matches nothing: these three broadcasts carry
            // the identity in the `data`, not in the extras.
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        awaitClose { runCatching { context.unregisterReceiver(receiver) } }
    }.shareIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), replay = 0)

    private companion object {
        /** It survives a rotation without registering and removing the receiver every time. */
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

package com.multistore.core.data.store

import com.multistore.core.common.coroutine.ApplicationScope
import com.multistore.core.data.repository.SettingsRepository
import com.multistore.core.data.repository.StoreHealthRepository
import com.multistore.core.model.StoreId
import com.multistore.core.network.http.RequestLog
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * `:core:data`'s end of the request log.
 *
 * Same shape as [ChallengeTierLog] and for the same two reasons — the `Provider` breaking the
 * `StoreHealthRepository → StoreRegistry → adapters → StoreHttpClients → this` cycle, and the write
 * starting on the application scope instead of making a network request's path suspend.
 *
 * ### The switch is read here, and not higher up
 *
 * `:core:network` is pure Kotlin and does not see the DataStore, so the "record or not" choice cannot
 * live there. It could live in whoever builds the client factory — i.e. in `:app`, once at startup —
 * and that would be the faster variant: a `RequestLog.NONE` instead of this, and zero cost with the
 * switch off.
 *
 * It is not done, for the reason that holds for every setting in this project: a value captured when
 * the graph is built is a value that stops following the user. Switching the log on would have no
 * effect until the app restarts — i.e. a switch that seems to do nothing, which is the same defect
 * already fixed on the update check's scheduling and on the challenge strategy. It is re-read on every
 * request, and with the switch off the cost is a `StateFlow` read in a coroutine nobody waits for.
 */
@Singleton
class RequestLogRecorder @Inject constructor(
    private val health: Provider<StoreHealthRepository>,
    private val settings: Provider<SettingsRepository>,
    @param:ApplicationScope private val scope: CoroutineScope,
) : RequestLog {

    override fun record(
        storeId: StoreId,
        method: String,
        url: String,
        code: Int,
        elapsed: Duration,
    ) {
        scope.launch {
            if (!settings.get().diagnostics.first().logRequests) return@launch
            health.get().recordEvent(
                storeId = storeId,
                kind = EVENT_KIND,
                detail = "$method $url → $code",
                durationMillis = elapsed.inWholeMilliseconds,
            )
        }
    }

    private companion object {
        /** The `kind` the row appears under in diagnostics, next to `parse_failure` and the like. */
        const val EVENT_KIND = "request"
    }
}

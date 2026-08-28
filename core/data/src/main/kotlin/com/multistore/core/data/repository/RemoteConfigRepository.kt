package com.multistore.core.data.repository

import com.multistore.core.remoteconfig.FetchAttempt
import com.multistore.core.remoteconfig.ParsersFetcher
import com.multistore.core.remoteconfig.RemoteConfigFetcher
import com.multistore.core.remoteconfig.RemoteConfigStatus
import com.multistore.core.remoteconfig.RemoteConfigStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * What the Settings screen can know and ask about the remote configuration.
 *
 * It lives in `:core:data` for the same reason `installerAvailability()` does: a `:feature:*` talks
 * to the repositories, not to infrastructure modules. The value added here is not only the shape —
 * it is that the "refuse remote fixes" switch lives in the DataStore and the configuration in
 * `:core:remoteconfig`, and **somebody has to hold the two together**. That somebody is a repository,
 * which is exactly the module that sees both.
 */
interface RemoteConfigRepository {

    /** What is active now, and how the last attempt to update it went. */
    val status: Flow<RemoteConfigStatus>

    /**
     * Downloads if enough time has passed, **and** if the user has not forbidden it.
     *
     * It is called by app startup. It returns `null` when it asked for nothing, which is two different
     * cases with the same outcome — recent cache, or channel off — and neither of them is news.
     */
    suspend fun refreshIfStale(): FetchAttempt?

    /**
     * Asks now.
     *
     * It respects the switch all the same: a "Check now" button making a request with the channel off
     * would be a way of bypassing the setting by pressing a key. With the channel off it returns
     * `null`, and the screen says so.
     */
    suspend fun refreshNow(): FetchAttempt?
}

@Singleton
internal class RemoteConfigRepositoryImpl @Inject constructor(
    private val store: RemoteConfigStore,
    @param:ParsersFetcher private val fetcher: RemoteConfigFetcher,
    private val settings: SettingsRepository,
) : RemoteConfigRepository {

    override val status: Flow<RemoteConfigStatus> = store.status

    override suspend fun refreshIfStale(): FetchAttempt? =
        if (blocked()) null else fetcher.refreshIfStale()

    override suspend fun refreshNow(): FetchAttempt? =
        if (blocked()) null else fetcher.refresh()

    private suspend fun blocked(): Boolean = settings.remoteConfig.first().blockRemoteParsers
}

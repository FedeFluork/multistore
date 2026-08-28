package com.multistore.core.domain.di

import android.content.Context
import android.net.ConnectivityManager
import androidx.core.content.getSystemService
import com.multistore.core.common.coroutine.IoDispatcher
import com.multistore.core.domain.NetworkConditions
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

@Module
@InstallIn(SingletonComponent::class)
object DomainModule {

    /**
     * Whether the active connection is metered.
     *
     * `isActiveNetworkMetered` and not an inspection of the `NetworkCapabilities`: it is the same
     * question the system asks itself, it takes the user's manual override into account ("treat this
     * Wi-Fi as metered") and does not require telling hotspots, roaming and limited networks apart by
     * hand. A more "precise" reading of the capabilities would be more precise about what the network
     * is, and less about what the user has decided it should be.
     */
    @Provides
    @Singleton
    fun provideNetworkConditions(
        @ApplicationContext context: Context,
        @IoDispatcher io: CoroutineDispatcher,
    ): NetworkConditions = NetworkConditions {
        withContext(io) {
            context.getSystemService<ConnectivityManager>()?.isActiveNetworkMetered ?: false
        }
    }
}

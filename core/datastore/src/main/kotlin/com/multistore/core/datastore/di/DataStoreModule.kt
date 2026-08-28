package com.multistore.core.datastore.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import com.multistore.core.datastore.SettingsSerializer
import com.multistore.core.datastore.proto.Settings
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {

    private const val SETTINGS_FILE = "settings.pb"

    @Provides
    @Singleton
    fun provideSettingsDataStore(
        @ApplicationContext context: Context,
        serializer: SettingsSerializer,
    ): DataStore<Settings> = DataStoreFactory.create(
        serializer = serializer,
        // A corrupt settings.pb must not prevent startup: we restart from the defaults.
        corruptionHandler = ReplaceFileCorruptionHandler { serializer.defaultValue },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        produceFile = { context.dataStoreFile(SETTINGS_FILE) },
    )

    private fun Context.dataStoreFile(name: String) = java.io.File(filesDir, "datastore/$name")
}

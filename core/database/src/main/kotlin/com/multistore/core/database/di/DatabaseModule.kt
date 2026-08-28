package com.multistore.core.database.di

import android.content.Context
import androidx.room.Room
import com.multistore.core.database.MULTISTORE_MIGRATIONS
import com.multistore.core.database.MultiStoreDatabase
import com.multistore.core.database.dao.CatalogDao
import com.multistore.core.database.dao.DownloadDao
import com.multistore.core.database.dao.IndexDao
import com.multistore.core.database.dao.InstalledAppDao
import com.multistore.core.database.dao.StoreDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): MultiStoreDatabase =
        Room.databaseBuilder(context, MultiStoreDatabase::class.java, MultiStoreDatabase.NAME)
            // WAL: during an index sync there are thousands of writes, and without WAL they
            // would block every read — i.e. the UI. With WAL readers see the last committed
            // state and do not wait for the writer.
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            // No fallbackToDestructiveMigration: deleting the user's data because we forgot a
            // migration is not an acceptable fallback in an app that tracks what it installed
            // and from where. The consequence has to be stated: a version bumped without its
            // migration does not lose data, it does not open the database — the app does not
            // start, and that is discovered on the first launch after the update.
            .addMigrations(*MULTISTORE_MIGRATIONS)
            .build()

    @Provides fun provideStoreDao(db: MultiStoreDatabase): StoreDao = db.storeDao()

    @Provides fun provideIndexDao(db: MultiStoreDatabase): IndexDao = db.indexDao()

    @Provides fun provideCatalogDao(db: MultiStoreDatabase): CatalogDao = db.catalogDao()

    @Provides fun provideInstalledAppDao(db: MultiStoreDatabase): InstalledAppDao = db.installedAppDao()

    @Provides fun provideDownloadDao(db: MultiStoreDatabase): DownloadDao = db.downloadDao()
}

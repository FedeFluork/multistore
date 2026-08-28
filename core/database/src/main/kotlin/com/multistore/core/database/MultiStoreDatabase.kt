package com.multistore.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.multistore.core.database.dao.CatalogDao
import com.multistore.core.database.dao.DownloadDao
import com.multistore.core.database.dao.IndexDao
import com.multistore.core.database.dao.InstalledAppDao
import com.multistore.core.database.dao.StoreDao
import com.multistore.core.database.entity.AppEntity
import com.multistore.core.database.entity.AppVersionEntity
import com.multistore.core.database.entity.DownloadEntity
import com.multistore.core.database.entity.HealthEventEntity
import com.multistore.core.database.entity.IdentityOverrideEntity
import com.multistore.core.database.entity.InstalledAppEntity
import com.multistore.core.database.entity.ListingScreenshotEntity
import com.multistore.core.database.entity.StoreAntiFeatureEntity
import com.multistore.core.database.entity.StoreCategoryEntity
import com.multistore.core.database.entity.StoreEntity
import com.multistore.core.database.entity.StoreIndexEntryEntity
import com.multistore.core.database.entity.StoreIndexStateEntity
import com.multistore.core.database.entity.StoreListingEntity
import com.multistore.core.database.entity.StoreOfficialSignerEntity

/**
 * MultiStore's database.
 *
 * The schema was **complete** from version 1, including tables not yet filled at the time —
 * `identity_overrides` serves cross-store matching, `health_events` the circuit breaker. The
 * reason is arithmetic: one extra migration costs more work, and more risk, than a few empty
 * tables.
 *
 * `exportSchema` stays on: the versioned schemas in `schemas/` are what makes migration tests
 * possible, and one is required at every bump.
 */
@Database(
    entities = [
        StoreEntity::class,
        StoreIndexStateEntity::class,
        StoreIndexEntryEntity::class,
        StoreCategoryEntity::class,
        StoreAntiFeatureEntity::class,
        StoreOfficialSignerEntity::class,
        HealthEventEntity::class,
        AppEntity::class,
        StoreListingEntity::class,
        ListingScreenshotEntity::class,
        AppVersionEntity::class,
        IdentityOverrideEntity::class,
        InstalledAppEntity::class,
        DownloadEntity::class,
    ],
    version = MultiStoreDatabase.VERSION,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class MultiStoreDatabase : RoomDatabase() {

    abstract fun storeDao(): StoreDao

    abstract fun indexDao(): IndexDao

    abstract fun catalogDao(): CatalogDao

    abstract fun installedAppDao(): InstalledAppDao

    abstract fun downloadDao(): DownloadDao

    companion object {
        const val VERSION: Int = 4
        const val NAME: String = "multistore.db"
    }
}

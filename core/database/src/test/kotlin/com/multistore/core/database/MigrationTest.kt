package com.multistore.core.database

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.multistore.core.model.ContentKind
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The project's first migration, tested against the **committed** schema.
 *
 * ### Why not `MigrationTestHelper`
 *
 * Room's helper demands an instrumented test and reads schemas from `androidTest` assets; this
 * project has no instrumented source set and tests everything with Robolectric. Rebuilding version
 * 1 by reading `1.json` costs twenty lines and has an advantage the helper does not give: the
 * starting version is **the real one**, the one users have on their phones, and not a hand-copied
 * list of `CREATE TABLE`s nobody would realign.
 *
 * ### What fails, and how
 *
 * Room, opened on a version-1 database, calls the migration and **then compares the resulting
 * schema with the expected one**. Three different errors therefore turn this test red: a missing
 * migration (`IllegalStateException: A migration from 1 to 2 was required but not found`), a
 * migration producing a column different from the entity's, and a migration that recreates the
 * table losing the rows — the last caught by the data assertion, not by Room.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseFile: File get() = context.getDatabasePath(MultiStoreDatabase.NAME)

    @Before
    fun clean() = deleteDatabase()

    @After
    fun cleanUp() = deleteDatabase()

    @Test
    fun `from 1 to 2 the in-flight download survives, and gains its headers`() = runTest {
        createVersion1 { db ->
            db.insertOrThrow(
                "downloads",
                null,
                ContentValues().apply {
                    put("listing_id", 7L)
                    put("store_id", "apkmirror")
                    put("store_app_ref", "firefox/firefox-browser")
                    put("version_ref", "154.0-arm64")
                    put("package_name", "org.mozilla.firefox")
                    put("state", "PAUSED")
                    put("bytes_downloaded", 1_234L)
                    put("bytes_total", 9_999L)
                    put("validator", "\"etag-abc\"")
                    put("created_at", 1L)
                    put("updated_at", 2L)
                },
            )
        }

        val database = openWithMigrations()
        try {
            val row = requireNotNull(database.downloadDao().get(1L)) {
                "The row written at version 1 is gone after the migration: the migration " +
                    "recreated the table instead of adding a column."
            }
            assertThat(row.storeAppRef).isEqualTo("firefox/firefox-browser")
            assertThat(row.bytesDownloaded).isEqualTo(1_234L)
            assertThat(row.validator).isEqualTo("\"etag-abc\"")
            // Rows written before version 2 have no headers, and that is the right value:
            // "no headers" is also what held for them.
            assertThat(row.requestHeaders).isNull()
        } finally {
            database.close()
        }
    }

    @Test
    fun `after the migration headers are written and read back`() = runTest {
        createVersion1 { }

        val database = openWithMigrations()
        try {
            val dao = database.downloadDao()
            val id = dao.upsert(
                com.multistore.core.database.entity.DownloadEntity(
                    listingId = 1,
                    storeId = com.multistore.core.model.StoreId.APKMIRROR,
                    storeAppRef = "firefox/firefox-browser",
                    versionRef = "154.0-arm64",
                    packageName = "org.mozilla.firefox",
                    // The real case: apkmirror's `download.php` demands the interstitial's
                    // Referer, and that URL cannot be reconstructed later.
                    requestHeaders = mapOf("Referer" to "https://www.apkmirror.com/wp-content/x"),
                    createdAt = kotlin.time.Instant.fromEpochMilliseconds(1),
                    updatedAt = kotlin.time.Instant.fromEpochMilliseconds(1),
                ),
            )
            assertThat(dao.get(id)?.requestHeaders)
                .containsExactly("Referer", "https://www.apkmirror.com/wp-content/x")
        } finally {
            database.close()
        }
    }

    @Test
    fun `from 2 to 3 the already-written events remain, without the two new columns`() = runTest {
        createVersion(2) { db ->
            db.insertOrThrow(
                "health_events",
                null,
                ContentValues().apply {
                    put("store_id", "apkmirror")
                    put("kind", "parse_failure")
                    put("selector", "#content .listWidget")
                    put("at", 1_700_000_000_000L)
                },
            )
        }

        val database = openWithMigrations()
        try {
            val events = database.storeDao().recentEvents()
            val migrated = events.single()
            // The row is still there: a migration recreating the table would throw away the
            // diagnostic log in exactly the update where it matters most.
            assertThat(migrated.kind).isEqualTo("parse_failure")
            assertThat(migrated.selector).isEqualTo("#content .listWidget")
            // And the two new columns are empty, which is the right value: that row is a
            // failure, not a request.
            assertThat(migrated.detail).isNull()
            assertThat(migrated.durationMillis).isNull()
        } finally {
            database.close()
        }
    }

    @Test
    fun `after the migration a recorded request reads back whole`() = runTest {
        createVersion(2) { }

        val database = openWithMigrations()
        try {
            database.storeDao().recordEvent(
                com.multistore.core.database.entity.HealthEventEntity(
                    storeId = com.multistore.core.model.StoreId.PDALIFE,
                    kind = "request",
                    detail = "GET https://pdalife.com/search/telegram/ → 200",
                    durationMillis = 812,
                    at = kotlin.time.Instant.fromEpochMilliseconds(1_700_000_001_000L),
                ),
            )
            val recorded = database.storeDao().recentEvents().single()
            assertThat(recorded.detail).contains("pdalife.com/search/telegram/")
            assertThat(recorded.durationMillis).isEqualTo(812)
        } finally {
            database.close()
        }
    }

    /**
     * 3 → 4: the already-saved listing remains, and the kind is carried over.
     *
     * `UNKNOWN` would be the honest value for rows written when nobody read the kind from the
     * listing. It is also the default declared in the entity, which is what stops Room refusing to
     * open by comparing the resulting schema with the expected one.
     */
    @Test
    fun `from 3 to 4 the listings remain, and the kind is carried over from apps`() = runTest {
        createVersion(3) { db ->
            db.insertOrThrow(
                "apps",
                null,
                ContentValues().apply {
                    put("app_key", "pkg:org.example.solitario")
                    put("title", "Solitario")
                    put("title_norm", "solitario")
                    // The kind sync had already written on the aggregated app: what the
                    // migration has to carry over onto the row.
                    put("content_kind", "GAME")
                    put("updated_at", 1_700_000_000_000L)
                },
            )
            db.insertOrThrow(
                "store_listings",
                null,
                ContentValues().apply {
                    put("app_key", "pkg:org.example.solitario")
                    put("store_id", "apkmirror")
                    put("store_app_ref", "solitario")
                    put("title", "Solitario")
                    put("title_norm", "solitario")
                    put("categories", "[]")
                    put("donate_urls", "[]")
                    put("match_confidence", 1.0)
                    put("match_method", "PACKAGE_NAME")
                    put("fetched_at", 1_700_000_000_000L)
                    put("ttl_seconds", 3600L)
                },
            )
        }

        val database = openWithMigrations()
        try {
            val rows = database.catalogDao().search(
                storeId = com.multistore.core.model.StoreId.APKMIRROR,
                query = "solitario",
                limit = 10,
                offset = 0,
            )
            // Not `UNKNOWN`: stopping at the ALTER TABLE would leave the whole already-downloaded
            // catalogue without a kind — measured on the emulator, 4,278 rows out of 4,278 — i.e. a
            // "games only" filter finding nothing until the next resync, which on F-Droid can be
            // seven days away.
            assertThat(rows.single().listing.contentKind)
                .isEqualTo(com.multistore.core.model.ContentKind.GAME)

            // And the filter finds it immediately, which is what the carry-over makes true.
            val games = database.catalogDao().search(
                storeId = com.multistore.core.model.StoreId.APKMIRROR,
                query = "solitario",
                limit = 10,
                offset = 0,
                kind = ContentKind.GAME,
            )
            assertThat(games).hasSize(1)
        } finally {
            database.close()
        }
    }

    /**
     * And after the migration the filter in the query actually works.
     *
     * Without this, the migration could add the column and leave the search reading it broken —
     * two different things, and the second only shows when queried.
     */
    @Test
    fun `after the migration search filters by kind`() = runTest {
        createVersion(3) { }

        val database = openWithMigrations()
        try {
            val dao = database.catalogDao()
            dao.saveListings(
                listOf(
                    listingWrite("pkg:org.example.solitario", "Solitario", ContentKind.GAME),
                    listingWrite("pkg:org.example.calculator", "Calculator", ContentKind.APP),
                ),
            )

            val games = dao.search(
                storeId = com.multistore.core.model.StoreId.APKMIRROR,
                query = "",
                limit = 10,
                offset = 0,
                kind = ContentKind.GAME,
            )

            assertThat(games.map { it.listing.title }).containsExactly("Solitario")
            assertThat(
                dao.searchCount(
                    storeId = com.multistore.core.model.StoreId.APKMIRROR,
                    query = "",
                    kind = ContentKind.GAME,
                ),
            ).isEqualTo(1)
        } finally {
            database.close()
        }
    }

    /**
     * 4 → 5: the file already waiting to be installed stays, and it stays **un**-proposed.
     *
     * The two assertions on the new columns are the point of the test and neither is decoration.
     * `installedAt` at `null` is what tells this history entry from one whose APK was deleted
     * *after* being installed, and there is nothing to back-fill: a row written before this version
     * never recorded when it was installed.
     *
     * `pendingInstall` at `false` is a **behavioural** default and the reason the migration does not
     * back-fill it either. That row is a download which finished at some unknown point in the past;
     * setting its intent to `1` would make the first launch after the update open the system's
     * installation prompt for it — which is precisely the thing
     * `auto_install_after_download` exists to let the user choose.
     */
    @Test
    fun `from 4 to 5 the ready download stays, and is not marked for installation`() = runTest {
        createVersion(4) { db ->
            db.insertOrThrow(
                "downloads",
                null,
                ContentValues().apply {
                    put("listing_id", 3L)
                    put("store_id", "f-droid")
                    put("store_app_ref", "org.fdroid.fdroid")
                    put("version_ref", "1001000")
                    put("package_name", "org.fdroid.fdroid")
                    put("state", "READY")
                    put("bytes_downloaded", 8_647L)
                    put("bytes_total", 8_647L)
                    put("file_path", "/data/user/0/x/files/staging/1.apk")
                    put("created_at", 1L)
                    put("updated_at", 2L)
                },
            )
        }

        val database = openWithMigrations()
        try {
            val row = requireNotNull(database.downloadDao().get(1L)) {
                "The row written at version 4 is gone after the migration: it recreated the " +
                    "table instead of adding two columns."
            }
            assertThat(row.state).isEqualTo(com.multistore.core.model.DownloadState.READY)
            assertThat(row.filePath).isEqualTo("/data/user/0/x/files/staging/1.apk")
            assertThat(row.installedAt).isNull()
            assertThat(row.pendingInstall).isFalse()
        } finally {
            database.close()
        }
    }

    /**
     * And after the migration the two columns are written and read back.
     *
     * Separate from the test above because they fail for different reasons: that one catches a
     * migration that loses rows, this one a column the entity and the database disagree about — the
     * `NOT NULL DEFAULT 0` whose default has to be declared **twice**, in the migration and in
     * `@ColumnInfo`.
     */
    @Test
    fun `after the migration the installation is recorded and the claim is spent once`() = runTest {
        createVersion(4) { }

        val database = openWithMigrations()
        try {
            val dao = database.downloadDao()
            val id = dao.upsert(
                com.multistore.core.database.entity.DownloadEntity(
                    listingId = 1,
                    storeId = com.multistore.core.model.StoreId.FDROID,
                    storeAppRef = "org.fdroid.fdroid",
                    versionRef = "1001000",
                    packageName = "org.fdroid.fdroid",
                    state = com.multistore.core.model.DownloadState.READY,
                    filePath = "/tmp/1.apk",
                    pendingInstall = true,
                    createdAt = kotlin.time.Instant.fromEpochMilliseconds(1),
                    updatedAt = kotlin.time.Instant.fromEpochMilliseconds(1),
                ),
            )

            // The claim is a token, not a flag: exactly one of two candidates for the same file may
            // proceed, and SQLite is what settles it.
            assertThat(dao.claimPendingInstall(id)).isEqualTo(1)
            assertThat(dao.claimPendingInstall(id)).isEqualTo(0)

            dao.markInstalled(id, kotlin.time.Instant.fromEpochMilliseconds(5_000))
            val installed = requireNotNull(dao.get(id))
            assertThat(installed.installedAt)
                .isEqualTo(kotlin.time.Instant.fromEpochMilliseconds(5_000))
            assertThat(installed.filePath).isNull()
            assertThat(installed.state).isEqualTo(com.multistore.core.model.DownloadState.DONE)
        } finally {
            database.close()
        }
    }

    private fun listingWrite(appKey: String, title: String, kind: ContentKind) =
        com.multistore.core.database.dao.ListingWrite(
            app = com.multistore.core.database.entity.AppEntity(
                appKey = appKey,
                title = title,
                titleNorm = title.lowercase(),
                updatedAt = kotlin.time.Instant.fromEpochMilliseconds(1),
            ),
            listing = com.multistore.core.database.entity.StoreListingEntity(
                appKey = appKey,
                storeId = com.multistore.core.model.StoreId.APKMIRROR,
                storeAppRef = title.lowercase(),
                title = title,
                titleNorm = title.lowercase(),
                contentKind = kind,
                fetchedAt = kotlin.time.Instant.fromEpochMilliseconds(1),
                ttlSeconds = 3600,
            ),
            versions = emptyList(),
            screenshots = emptyList(),
        )

    // --- infrastructure ---------------------------------------------------------------------

    private fun openWithMigrations(): MultiStoreDatabase =
        Room.databaseBuilder(context, MultiStoreDatabase::class.java, MultiStoreDatabase.NAME)
            .addMigrations(*MULTISTORE_MIGRATIONS)
            .allowMainThreadQueries()
            .build()

    /**
     * Rebuilds the database as version 1 wrote it, from the schema exported at the time.
     *
     * `user_version = 1` is what triggers `onUpgrade`: without it SQLite would report the database
     * as new and Room would create the current schema directly, i.e. the test would pass without
     * ever running the migration.
     */
    private fun createVersion1(populate: (SQLiteDatabase) -> Unit) = createVersion(1, populate)

    private fun createVersion(version: Int, populate: (SQLiteDatabase) -> Unit) {
        databaseFile.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(databaseFile, null)
        try {
            schemaStatements(version).forEach(db::execSQL)
            db.version = version
            populate(db)
        } finally {
            db.close()
        }
    }

    private fun schemaStatements(version: Int): List<String> {
        val directory = requireNotNull(System.getProperty(SCHEMA_DIR_PROPERTY)) {
            "The system property $SCHEMA_DIR_PROPERTY is missing: it is set by " +
                "core/database/build.gradle.kts, so this test must be run through Gradle."
        }
        val file = File(directory, "$SCHEMA_PACKAGE/$version.json")
        require(file.isFile) {
            "${file.absolutePath} is missing. The exported schema is this test's source: " +
                "without it, no migration would be under test at all."
        }

        val database = Json.parseToJsonElement(file.readText()).jsonObject
            .getValue("database").jsonObject
        val statements = mutableListOf<String>()
        database.getValue("entities").jsonArray.forEach { element ->
            val entity = element.jsonObject
            val table = entity.getValue("tableName").jsonPrimitive.content
            statements += entity.getValue("createSql").jsonPrimitive.content
                .replace(TABLE_NAME_PLACEHOLDER, "`$table`")
            entity["indices"]?.jsonArray?.forEach { index ->
                statements += index.jsonObject.getValue("createSql").jsonPrimitive.content
                    .replace(TABLE_NAME_PLACEHOLDER, "`$table`")
            }
        }
        check(statements.isNotEmpty()) { "No table read from schema $version." }
        return statements
    }

    private fun deleteDatabase() {
        context.deleteDatabase(MultiStoreDatabase.NAME)
    }

    private companion object {
        const val SCHEMA_DIR_PROPERTY = "multistore.schemaDir"
        const val SCHEMA_PACKAGE = "com.multistore.core.database.MultiStoreDatabase"

        /** Room writes the table name as a placeholder in exported schemas. */
        const val TABLE_NAME_PLACEHOLDER = "`\${TABLE_NAME}`"
    }
}

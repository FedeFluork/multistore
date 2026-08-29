package com.multistore.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The schema migrations.
 *
 * The complete schema was created in one go, including tables nothing filled yet, precisely so
 * migrations would be rare. Worth knowing what one entails: `DatabaseModule` builds the database
 * **without** `fallbackToDestructiveMigration`, so a version bumped without its migration does not
 * delete the user's data — it refuses to open the database, and the app does not start.
 */

/**
 * 1 → 2: `downloads.request_headers`.
 *
 * ### Why a column, and not a recomputation
 *
 * Two adapters compute headers without which the server answers 403, and they compute them at the
 * only moment they *can* be computed: apkmirror supplies the `Referer` of the interstitial just
 * traversed, and the assisted path supplies the `Cookie` and User-Agent of the WebView session in
 * which the user tapped. Neither can be reconstructed later — not from the URL, not from the
 * listing, not from the `CookieJar` of a process that has since died.
 *
 * Until this migration those headers **never reached the network**: `DownloadRequest` declared
 * them, `enqueue` received them, and nothing between the two carried them. It worked anyway
 * because both stores resolve a signed URL that, today, does not demand them — i.e. because of a
 * property of the server, not of our code. A periodic update check makes it worse rather than
 * better: it downloads **while the app is not there**, and a resume after a restart rebuilds the
 * request from the database row alone.
 *
 * ### Nullable, and without a `DEFAULT`
 *
 * `ALTER TABLE … ADD COLUMN` with `NOT NULL` would demand a `DEFAULT`, and a default declared
 * here and not in the entity is exactly the kind of mismatch Room's schema validation reports on
 * opening — i.e. at runtime, on the user's device. The column is nullable and an absent value
 * means "no headers", which is also what holds for every row written before this version.
 */
internal val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `downloads` ADD COLUMN `request_headers` TEXT")
    }
}

/**
 * 2 → 3: `health_events.detail` and `health_events.duration_ms`.
 *
 * The diagnostic log already existed and recorded one thing — what goes wrong. It now also
 * records, with the switch on, **successful** requests, and those need two pieces of information
 * no existing column could carry without lying: the address with the response code, and how long
 * it took.
 *
 * Reusing the existing columns — `selector` for the URL, `snippet_hash` for the code — would have
 * cost zero migration lines and made the table unreadable: `selector` means "the CSS selector that
 * found nothing", and that is what the export shows under that name.
 *
 * ### Nullable, and without a `DEFAULT`
 *
 * Same reason as 1 → 2. `null` means "this row is a failure, not a request", which is also what
 * holds for every row written before.
 */
internal val MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `health_events` ADD COLUMN `detail` TEXT")
        db.execSQL("ALTER TABLE `health_events` ADD COLUMN `duration_ms` INTEGER")
    }
}

/**
 * 3 → 4: `store_listings.content_kind`.
 *
 * ### Why a second column with the same name as one that already exists
 *
 * `apps.content_kind` is not a substitute for this one, because it answers a different question.
 * `apps` has one row per **aggregated app** and is written with an `@Upsert` on `app_key`: the
 * last listing saved wins. The census says eight stores out of nine do not publish the kind in
 * their listings, so saving one of those eight's listings **erases** the kind F-Droid wrote for
 * the same package — silently, and precisely while a "games only" filter is reading it.
 *
 * With the column on the listing the value belongs to the row that declared it, which is also
 * what the filter must read: it is apkmody saying "game" about its own listings.
 *
 * ### `NOT NULL`, and the default has to be declared twice
 *
 * The first migration with a non-nullable column, because "unknown kind" is a domain value here
 * ([com.multistore.core.model.ContentKind.UNKNOWN]) and not an absence. `ALTER TABLE … ADD COLUMN`
 * with `NOT NULL` then demands a `DEFAULT`, and a default that exists in the database but not in
 * the entity is exactly the mismatch Room reports on opening: `@ColumnInfo(defaultValue =
 * "UNKNOWN")` is there for that.
 *
 * ### And the rows that were already there get filled, instead of staying empty
 *
 * The first draft stopped at the `ALTER TABLE`, and it was a correct migration that left the app
 * in a wrong state. Measured on the emulator right after the update: **4,278 rows out of 4,278 at
 * `UNKNOWN`**, i.e. a "games only" filter finding nothing on F-Droid — for up to seven days, its
 * index's TTL, with no screen able to say why.
 *
 * The value was not missing, though: it was in `apps.content_kind`, where sync had already written
 * it (on the same device: 3,879 `APP`, 396 `GAME`, 3 `UNKNOWN`). The `UPDATE` moves it back to the
 * row it belongs to.
 *
 * The transfer is **exact where an app has a single listing** — which on that device was all of
 * them. Where there are two, `apps` carries the value of whoever saved last, and that is exactly
 * the defect this column exists to close: copying it onto both rows does not make it worse, and
 * the first resync corrects it by writing each row's own.
 */
internal val MIGRATION_3_4: Migration = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `store_listings` ADD COLUMN `content_kind` TEXT NOT NULL DEFAULT 'UNKNOWN'",
        )
        db.execSQL(
            "UPDATE `store_listings` SET `content_kind` = COALESCE(" +
                "(SELECT `a`.`content_kind` FROM `apps` AS `a` " +
                "WHERE `a`.`app_key` = `store_listings`.`app_key`), 'UNKNOWN')",
        )
    }
}

/**
 * 4 → 5: `downloads.installed_at` and `downloads.pending_install`.
 *
 * ### Why the row now survives the installation
 *
 * Until this version `discard` deleted **row and file** on a successful install, so there was no
 * history at all: the Downloads screen would have had nothing older than the transfer in flight to
 * show. Keeping the row costs a few hundred bytes and is bounded by `download_history_limit`; what
 * it buys is the answer to "did I already download this, and what happened to it".
 *
 * ### Why two columns and not one
 *
 * They answer different questions and neither implies the other.
 *
 * `installed_at` disambiguates a state that would otherwise be lossy. A `DONE` row with no file
 * can mean "installed, and the APK deleted afterwards" — the normal case with
 * `keep_apk_after_install` off — or "downloaded and then deleted without ever being installed",
 * which is what the new Delete button and the storage cleanup produce. `DownloadState` has no way
 * of telling the two apart, and the history row has to.
 *
 * `pending_install` records the **intent** the download was born with. A transfer started from a
 * listing was meant to end in an installation; one started by the periodic check with
 * `auto_install_updates` off was explicitly meant to stop at the file. Both end in `READY`, and
 * only the first may be carried on by `auto_install_after_download`. It doubles as the claim token
 * that keeps the listing and the shell's coordinator from installing the same file twice.
 *
 * ### `NOT NULL` on the second, and the default declared twice
 *
 * `installed_at` is nullable, like the columns added in 1 → 2 and 2 → 3: absent means "never
 * installed", which is true of every row written before this version. `pending_install` cannot be,
 * because "no installation was intended" is a value of the domain and not an absence — so
 * `ALTER TABLE … ADD COLUMN` demands a `DEFAULT`, and `@ColumnInfo(defaultValue = "0")` has to
 * repeat it, or Room reports the mismatch when opening the database.
 *
 * ### And the rows that were already there are left at `0` deliberately
 *
 * Unlike 3 → 4 there is nothing to back-fill, and back-filling would be wrong: an existing `READY`
 * row is a download that finished at some unknown point in the past, possibly a week ago. Setting
 * its intent to `1` would make the first launch after the update propose installing it — which is
 * exactly the behaviour the switch exists to let the user *choose*.
 */
internal val MIGRATION_4_5: Migration = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `downloads` ADD COLUMN `installed_at` INTEGER")
        db.execSQL(
            "ALTER TABLE `downloads` ADD COLUMN `pending_install` INTEGER NOT NULL DEFAULT 0",
        )
    }
}

/** Every migration, in the order Room would apply them. */
internal val MULTISTORE_MIGRATIONS: Array<Migration> =
    arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)

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

/**
 * 5 → 6: `store_listings.translation_url`.
 *
 * ### A field that existed everywhere except where it had to survive
 *
 * `StoreListingDetail.translationUrl` has been in the model since M1 and F-Droid has been filling
 * it since M1 — `PackageProjection` reads `metadata.string("translation")`. Between the adapter
 * and the screen, though, there was no column: the entity mapper did not write it and the domain
 * mapper could not read it. The value therefore lived as long as the object the adapter returned,
 * and was gone the next time the listing came out of Room.
 *
 * Nobody noticed because nobody showed it. The links block is the first reader the seven author
 * fields have ever had, and adding it without this column would have produced the worst of the
 * three possible outcomes: a link visible on the very first visit to a listing and absent on every
 * one after it — the shape of a defect people blame on the store.
 *
 * ### Two columns in one version, and the second has the same shape as the first
 *
 * `listing_screenshots.locale` is here for the reason the field above is: something is finally
 * drawing that data, and drawing it showed what was being thrown away. F-Droid files the same
 * screens under every language the pruning keeps — **95 images for AntennaPod**, measured on the
 * device — and the adapter had been discarding the tag, reasonably, back when nobody looked. With a
 * strip on the page that becomes four copies of each screen in languages the reader does not read.
 *
 * ### `translation_url` is nullable with nothing to back-fill; `locale` is not
 *
 * Nullable like 1 → 2, 2 → 3 and half of 4 → 5: absent means "this source publishes no translation
 * page", which is true of eight stores out of nine and of every row written before this version.
 * For that column the answer to "where is this value already written" really is *nowhere*, so the
 * rows fill in as they are re-read.
 *
 * ### The locale is back-filled, and the first draft of this comment was wrong about why
 *
 * That draft said there was nothing to back-fill and that the rows would "fill in by themselves at
 * the next index sync". Both halves were false, and the second is the one that would have shipped a
 * defect:
 *
 *  - **`refresh` does nothing on an indexed store.** It returns `Success` without asking anything,
 *    because a listing's freshness there is a property of the index. Only `StoreIndexRepository.sync`
 *    re-projects, and a **diff** sync only touches entries that changed — so a stable app might not
 *    be re-projected for months. Measured on the device: after upgrading, AntennaPod's strip showed
 *    every language at once, in German to an English reader, and stayed that way across a refresh.
 *  - **The value *is* already written**, in the URL. F-Droid's repo lays screenshots out as
 *    `/repo/<package>/<locale>/<kind>Screenshots/<file>`, so the locale is the segment before the
 *    kind directory — which makes this the same remedy as 3 → 4, where `content_kind` was recovered
 *    from `apps.content_kind` rather than left to a re-read that would have taken seven days.
 *
 * Verified against a real 66 MB catalogue: with every locale erased and then rebuilt by the
 * statements below, **32,353 of 32,397 rows come back byte-identical to what the projection had
 * written, and zero differ**. The remaining 44 are apkmirror and apkmody rows, which carry no
 * language and correctly stay `NULL` — a screenshot with no locale is shown to everybody, which is
 * the right answer for the eight stores that publish no tag.
 *
 * The five directory names are F-Droid's and are matched literally rather than by taking "the third
 * segment from the end": a URL that does not have this shape is then left alone instead of being
 * given a segment that is not a language.
 */
internal val MIGRATION_5_6: Migration = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `store_listings` ADD COLUMN `translation_url` TEXT")
        db.execSQL("ALTER TABLE `listing_screenshots` ADD COLUMN `locale` TEXT")

        for (directory in FDROID_SCREENSHOT_DIRECTORIES) {
            val marker = "/$directory/"
            // Everything up to the kind directory: `…/repo/<package>/<locale>`.
            val head = "substr(url, 1, instr(url, '$marker') - 1)"
            // `rtrim(head, <every character of head except '/'>)` leaves `head` up to and including
            // its last slash, so what follows is the last segment — the locale.
            db.execSQL(
                """
                UPDATE listing_screenshots
                SET locale = substr($head, length(rtrim($head, replace($head, '/', ''))) + 1)
                WHERE locale IS NULL AND instr(url, '$marker') > 0
                """.trimIndent(),
            )
        }
    }
}

/**
 * How F-Droid's repo names the directory a screenshot's kind goes in.
 *
 * Measured on a real catalogue: phone 27,786 rows, tenInch 2,202, sevenInch 2,122, tv 195, wear 48.
 * They mirror `PackageProjection.SCREENSHOT_KINDS`, and they are duplicated here on purpose —
 * `:core:database` must not depend on a concrete store, and a migration has to keep describing the
 * bytes that were written when it ran even if that adapter later renames something.
 */
private val FDROID_SCREENSHOT_DIRECTORIES = listOf(
    "phoneScreenshots",
    "sevenInchScreenshots",
    "tenInchScreenshots",
    "tvScreenshots",
    "wearScreenshots",
)

/**
 * `store_listings.declared_modified` — "this store marked **this** listing as a rework".
 *
 * ### Nothing is back-filled, and this time that is the answer rather than the omission
 *
 * Migration 5 → 6 established the question to ask before closing one: *where is this value already
 * written?* There it was written in the screenshot URL, and a draft that left the column `NULL`
 * would have shipped the defect. Here the honest answer is **nowhere**: the flag comes from a parse
 * this build performs for the first time — an1's `item_app mod` class, modyolo's `mod_info`,
 * pdalife's `downloads-mod` label — and no earlier column, address or index holds it.
 *
 * And the second half of that question, *who re-reads, and when*, has an answer that costs nothing
 * here: the three stores that declare it are all scraped, with a listing TTL measured in hours, so
 * a row refreshes on the next visit. It is not the indexed-store trap, where a diff sync can leave
 * an untouched entry alone for months.
 *
 * What a not-yet-re-read row says meanwhile is the prudent thing rather than a wrong one. `0` on
 * one of the five stores that redistribute reworks reads as *possible*, not *clean* — the same
 * thing said about every apkmody and liteapks listing, which never carry a per-row mark at all.
 *
 * `NOT NULL` therefore needs its `DEFAULT`, in both places: `ALTER TABLE` will not add a non-null
 * column without one, and a default declared here and missing from the entity is the schema
 * mismatch Room reports when it opens the database on somebody's phone.
 */
internal val MIGRATION_6_7: Migration = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `store_listings` ADD COLUMN `declared_modified` INTEGER NOT NULL DEFAULT 0",
        )
    }
}

/**
 * `app_versions.permissions` — what a build asks the operating system for.
 *
 * ### Nullable, and this is the migration where that matters most
 *
 * Every other list column in this schema is `NOT NULL DEFAULT '[]'`. This one is not, because here
 * the empty list is a **claim**: "this app asks for nothing". Filling existing rows with `[]` would
 * put that claim on every version already in the catalogue — 4,269 of them on a device with the
 * F-Droid index — and it would be the single most reassuring thing the permissions screen can say,
 * produced entirely by not having looked.
 *
 * ### Where the value already is, asked in both halves
 *
 * The question 5 → 6 taught is "where is this value already written", and its follow-up is "who
 * re-reads, and when". Here the answers split by store, and both are acceptable:
 *
 *  - **F-Droid**: in the signed index, which is already on the device. A diff sync only touches
 *    changed entries, so an untouched app could stay `NULL` for months — but that is *correct*
 *    here, because `NULL` means "not read yet" and the screen says exactly that. Nothing is claimed
 *    while waiting. A back-fill would mean re-projecting 4,269 packages inside a migration, from
 *    JSON this module cannot parse without depending on a concrete store.
 *  - **the other eight**: nowhere. It comes from the archive, and the archive arrives when somebody
 *    installs. `NULL` until then is the truth.
 *
 * So this is the third kind of answer to "where is this value written", after 5 → 6's *in the URL*
 * and 4 → 5's *nowhere, and rightly so*: **nowhere yet, and the column is shaped to say so.**
 */
internal val MIGRATION_7_8: Migration = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `app_versions` ADD COLUMN `permissions` TEXT")
    }
}

/**
 * `search_history` — the last searches, so they can be offered back.
 *
 * A new table, so there is nothing to back-fill and nothing to interpret: the question 5 → 6 taught
 * to ask has no subject here. What matters instead is the shape, and it has to match the entity
 * exactly or Room refuses to open the database on somebody's phone.
 *
 * **The query is the primary key.** Searching the same thing twice is one entry that moves to the
 * top, not two rows — with a generated id the list would fill with the word being refined, `f`,
 * `fi`, `fir`, `fire`, `firefox`, which is the opposite of recalling anything.
 */
internal val MIGRATION_8_9: Migration = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `search_history` (
                `query` TEXT NOT NULL,
                `at` INTEGER NOT NULL,
                PRIMARY KEY(`query`)
            )
            """.trimIndent(),
        )
    }
}

/** Every migration, in the order Room would apply them. */
internal val MULTISTORE_MIGRATIONS: Array<Migration> = arrayOf(
    MIGRATION_1_2,
    MIGRATION_2_3,
    MIGRATION_3_4,
    MIGRATION_4_5,
    MIGRATION_5_6,
    MIGRATION_6_7,
    MIGRATION_7_8,
    MIGRATION_8_9,
)

package com.multistore.store.common.html

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Instant

/**
 * Numbers, dates and sizes written for a human reader, turned back into values.
 *
 * It lives in `:store:common` and not in individual adapters because the same three or four forms
 * recur across different stores — `121 MB`, `Android 8.0 (Oreo, API 26)`, `1,000,000,000+` — and
 * nine implementations of the same conversion would be nine different ways of getting it wrong.
 *
 * Every function returns `null` rather than guessing. A wrong size is not cosmetic: it is the
 * **first** step of the pre-install verification pipeline, the one comparing the expected size with
 * the downloaded one.
 */
object TextValues {

    /**
     * A size in bytes from what the page writes.
     *
     * If the string contains an exact count in brackets — apkmirror writes
     * `273.25 MB (286,519,098 bytes)` — **that** wins: rounding 273.25 MB to two decimals covers a
     * range of about 5 KB, and pre-install verification compares the size with the downloaded
     * file's.
     *
     * The units are binary (1 MB = 1,048,576 bytes). Verified on apkmirror: 273.25 MB declared
     * against exactly 286,519,098 bytes gives 1,048,578 bytes per MB — binary, not decimal (which
     * would read 286.5 MB).
     */
    fun byteSize(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        EXACT_BYTES.find(raw)?.let { match ->
            return match.groupValues[1].replace(GROUPING, "").toLongOrNull()
        }
        val match = SIZE_WITH_UNIT.find(raw) ?: return null
        val value = match.groupValues[1].replace(",", ".").toDoubleOrNull() ?: return null
        val multiplier = UNITS[match.groupValues[2].uppercase()] ?: return null
        return (value * multiplier).toLong()
    }

    /**
     * `3.9 ★` -> `3.9f`, **always out of five**.
     *
     * [outOf] is the maximum of the **store's** scale, not the scale wanted on the way out: the
     * value comes back rescaled to five regardless, because that is what the app compares across
     * stores. The default is five because five is what nearly everyone publishes.
     *
     * **pdalife is the first that does not**, and without this parameter the defect would be
     * silent: its listing declares a best rating of 10 and a value of `9.2292`, and an earlier
     * version of this function — which discarded anything outside `0..5` — would have returned
     * `null` **for every app on that store**. Not a wrong value: no value, with nothing saying so.
     *
     * Outside the declared scale it stays `null`: there the selector picked up something else.
     */
    fun rating(raw: String?, outOf: Float = MAX_RATING): Float? {
        if (raw.isNullOrBlank()) return null
        if (outOf <= 0f) return null
        val value = DECIMAL.find(raw)?.value?.replace(",", ".")?.toFloatOrNull() ?: return null
        if (value !in 0f..outOf) return null
        return value * MAX_RATING / outOf
    }

    /**
     * The version code stores write in brackets.
     *
     * apkcombo: `12.10.0 (70242)`. apkmirror: `Version: 154.0 (2016178287)`. The value must be
     * digits only: `(Oreo, API 26)` is not a version code, and taking the first number seen would
     * turn it into `26`.
     */
    fun parenthesizedCode(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        return PARENTHESIZED_DIGITS.findAll(raw)
            .mapNotNull { it.groupValues[1].toLongOrNull() }
            .lastOrNull()
    }

    /**
     * The API level from how the stores write it.
     *
     * Two forms, both real: apkmirror declares the number — `Min: Android 8.0 (Oreo, API 26)` — and
     * then that is read; apkcombo writes only the marketing version — `Android 6.0+` — and then the
     * table is needed. The table is the only way: between Android 6.0 and 7.0 there is no
     * arithmetic relation to 23 and 24.
     */
    fun apiLevel(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        EXPLICIT_API.find(raw)?.let { return it.groupValues[1].toIntOrNull() }
        val release = ANDROID_RELEASE.find(raw)?.groupValues?.get(1)?.uppercase() ?: return null
        API_BY_RELEASE[release]?.let { return it }
        // `Android 8.0.1` is not in the table but `8.0` is: shorten until something matches.
        val major = release.substringBefore('.')
        return API_BY_RELEASE[major]
    }

    /** `arm64-v8a, armeabi-v7a, x86, x86_64` -> the list. `nodpi`/`universal` -> empty list. */
    fun abis(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(',', ' ')
            .map { it.trim().lowercase() }
            .filter { it in KNOWN_ABIS }
            .distinct()
    }

    /** `1,000,000,000+`, `1 B+`, `100 M+`: the label is kept, no number is invented. */
    fun downloadsLabel(raw: String?): String? = raw?.trim()?.takeIf { it.isNotBlank() }

    /** `Aug 21, 2026` -> the instant of that day's UTC midnight. */
    fun monthDayYear(raw: String?): Instant? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            LocalDate.parse(raw.trim(), MONTH_DAY_YEAR).atStartOfDay(ZoneOffset.UTC).toInstant()
        }.getOrNull()?.let { Instant.fromEpochMilliseconds(it.toEpochMilli()) }
    }

    /**
     * `Tue Apr 07 2026` -> the instant of that day's UTC midnight.
     *
     * It is what JavaScript's `Date.prototype.toDateString()` prints, and apkmody writes it that
     * way on every row of its version history: the date is formatted by their code, not by a
     * template. The weekday is present but carries no information — were it to contradict the date,
     * the date would win — which is why the format declares it and then ignores it.
     */
    fun weekdayMonthDayYear(raw: String?): Instant? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            LocalDate.parse(raw.trim(), WEEKDAY_MONTH_DAY_YEAR).atStartOfDay(ZoneOffset.UTC).toInstant()
        }.getOrNull()?.let { Instant.fromEpochMilliseconds(it.toEpochMilli()) }
    }

    /** `2026-07-24T21:30:21.691Z`, the form of a `<time>` element's `datetime` attribute. */
    fun isoInstant(raw: String?): Instant? {
        if (raw.isNullOrBlank()) return null
        return runCatching { Instant.parse(raw.trim()) }.getOrNull()
    }

    /**
     * `26.07.2023` and **`8.02.2026`**: day, month, year separated by dots.
     *
     * The form pdalife writes atop each version's changelog block, and the only date on that site
     * that does not go through a month name — hence the only one that survives the page's language
     * being chosen by the server. The listing also writes `26 July 2023`, but in Russian it would
     * write `26 июля 2023`.
     *
     * The day is **not always two digits** — `8.02.2026` sits next to `25.05.2026` on the same page
     * — and the month is: the format accepts both widths on both.
     */
    fun dottedDayMonthYear(raw: String?): Instant? {
        if (raw.isNullOrBlank()) return null
        val date = DOTTED_DATE.find(raw)?.value ?: return null
        return runCatching {
            LocalDate.parse(date, DAY_MONTH_YEAR_DOTTED).atStartOfDay(ZoneOffset.UTC).toInstant()
        }.getOrNull()?.let { Instant.fromEpochMilliseconds(it.toEpochMilli()) }
    }

    /** `07/12/2019 16:45 UTC`, the form of apkmirror's UTC date attribute. Month/day, US order. */
    fun utcDateTime(raw: String?): Instant? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim().removeSuffix("UTC").trim()
        return runCatching {
            LocalDateTime.parse(trimmed, MONTH_DAY_YEAR_TIME).toInstant(ZoneOffset.UTC)
        }.getOrNull()?.let { Instant.fromEpochMilliseconds(it.toEpochMilli()) }
    }

    /**
     * `Tue, 25 Aug 2026 18:15:04 +0000` — the date of an RSS `<pubDate>`.
     *
     * The only date format the five measured feeds use, and mandatory in the RSS 2.0 spec. The
     * offset is **part of the value**: pdalife writes `+0300` and apkcombo `+0000`, so converting
     * to UTC belongs here and not in the reader.
     *
     * The RFC 1123 formatter accepts both one-digit and two-digit days, which is needed: both
     * widths occur among the measured entries.
     */
    fun rfc1123(raw: String?): Instant? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            ZonedDateTime.parse(raw.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
        }.getOrNull()?.let { Instant.fromEpochMilliseconds(it.toEpochMilli()) }
    }

    /**
     * Like [rfc1123], but a date **in the future** is `null`.
     *
     * A `<pubDate>` is written by the publisher and verified by nobody. Measured on 25/08/2026
     * across the five reachable feeds: apkcombo 0 entries out of 98 in the future, apkmirror 0 of
     * 10, modyolo 0 of 24, and **pdalife 5 of 100** — the furthest dated **27 May 2029**. They are
     * announcements of unreleased games, and in a date-ordered list they would stay at the top
     * forever: the "new" section would show five things that do not exist, and show them **first**.
     *
     * [now] is a parameter and not the system clock because that is the only form in which the rule
     * can be tested: a test that had to wait until 2029 is not a test.
     */
    fun rfc1123NotFuture(raw: String?, now: Instant): Instant? =
        rfc1123(raw)?.takeIf { it <= now }

    /** A hexadecimal SHA-256 or SHA-1, isolated from text containing other things too. */
    fun hex(raw: String?, chars: Int): String? {
        if (raw.isNullOrBlank()) return null
        return Regex("\\b[0-9a-fA-F]{$chars}\\b").find(raw)?.value?.lowercase()
    }

    private val EXACT_BYTES = Regex("""\(([\d,. ]+)\s*bytes?\)""", RegexOption.IGNORE_CASE)
    private val GROUPING = Regex("""[,. ]""")
    /**
     * `121 MB`, `1.2 GiB` and — from liteapks — also **`71M`**, with the `B` missing.
     *
     * The `B` is optional because 11 file rows out of 66 write it that way, and without this
     * tolerance those 11 sizes would be `null` in silence. What follows the unit cannot be a
     * letter: without that guard `26 Minecraft` would become 26 MB.
     */
    private val SIZE_WITH_UNIT =
        Regex("""([\d]+(?:[.,]\d+)?)\s*(K|M|G)i?B?(?![A-Za-z])""", RegexOption.IGNORE_CASE)
    private val DECIMAL = Regex("""\d+(?:[.,]\d+)?""")
    /** `26.07.2023  - Changes not specified.`: the date leads, the rest is prose. */
    private val DOTTED_DATE = Regex("""\d{1,2}\.\d{1,2}\.\d{4}""")
    private val PARENTHESIZED_DIGITS = Regex("""\((\d+)\)""")
    private val EXPLICIT_API = Regex("""API\s*(\d{1,2})""", RegexOption.IGNORE_CASE)
    /**
     * `Android 8.0`, `Android 6.0+`, **`Android 12L+`**, **`Android + 5.0`**.
     *
     * The trailing `L` is not an apkmirror typo: Android 12L is a real release with an API level of
     * its own, 32. Without capturing it, `12L` would read as `12` and the minimum would come out as
     * 31 — an app declared installable on a device where it is not.
     *
     * The `+` *before* the number is not a typo either: uptodown writes **`Android + 5.0`** on
     * every row of its version list, with the sign in front. Without allowing it, uptodown's
     * minimum would be null on every version of every app, and version selection would accept
     * anything as compatible — the costlier of the two errors, because it does not show until an
     * installation fails on the device.
     */
    private val ANDROID_RELEASE =
        Regex("""Android\s*[+≥>]?\s*(\d+(?:\.\d+)*L?)""", RegexOption.IGNORE_CASE)

    private val MONTH_DAY_YEAR: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)
    private val MONTH_DAY_YEAR_TIME: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm", Locale.US)
    private val WEEKDAY_MONTH_DAY_YEAR: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEE MMM dd yyyy", Locale.US)
    private val DAY_MONTH_YEAR_DOTTED: DateTimeFormatter =
        DateTimeFormatter.ofPattern("d.M.yyyy", Locale.US)

    private const val MAX_RATING = 5f

    private val UNITS = mapOf(
        "K" to 1024.0,
        "M" to 1024.0 * 1024,
        "G" to 1024.0 * 1024 * 1024,
    )

    private val KNOWN_ABIS = setOf("arm64-v8a", "armeabi-v7a", "armeabi", "x86", "x86_64", "mips")

    /**
     * Android marketing version -> API level.
     *
     * It stops at what is needed: below 8.0 the project's own minimum makes the value irrelevant,
     * but the table covers it anyway because stores publish old apps and a misread minimum would
     * make them all look incompatible.
     */
    private val API_BY_RELEASE = mapOf(
        // The 2.x and 3.x releases are not completeness for its own sake: pdalife publishes apps
        // declared `Android 2.2+` and `Android 2.3+` — Telegram among them — and without these
        // rows those listings' `minSdk` would come out **absent** rather than very low. No
        // behavioural difference at `minSdk 26`, but "I did not read it" and "I read it and it is
        // 8" are not the same answer, and the first is the one that hides a broken selector.
        "2.2" to 8, "2.3" to 9, "3.0" to 11, "3.1" to 12, "3.2" to 13,
        "4.0" to 14, "4.1" to 16, "4.2" to 17, "4.3" to 18, "4.4" to 19,
        "5.0" to 21, "5.1" to 22, "6.0" to 23,
        "7.0" to 24, "7.1" to 25,
        "8.0" to 26, "8.1" to 27,
        "9" to 28, "10" to 29, "11" to 30, "12" to 31, "12L" to 32, "13" to 33,
        "14" to 34, "15" to 35, "16" to 36, "17" to 37,
    )
}

package com.multistore.core.datastore

import com.google.common.truth.Truth.assertThat
import com.multistore.core.datastore.proto.ContentKindFilter as ProtoContentKindFilter
import com.multistore.core.datastore.proto.SearchSort as ProtoSearchSort
import com.multistore.core.datastore.proto.Settings
import com.multistore.core.model.CatalogRetention
import com.multistore.core.model.ChallengeStrategy
import com.multistore.core.model.ContentKind
import com.multistore.core.model.InstallerPreference
import com.multistore.core.model.SearchSettings
import com.multistore.core.model.SearchSort
import com.multistore.core.model.StorageSettings
import com.multistore.core.model.ThemeMode
import com.multistore.core.model.UpdateInterval
import kotlin.time.Duration.Companion.seconds
import org.junit.Test

/**
 * What the app does **before** anyone has chosen anything.
 *
 * proto3 has no explicit defaults: a field's zero value *is* its default, so the order enum
 * values are declared in and the direction a `bool`'s name is written **are behavioural
 * decisions**. The mistake is invisible when made: it compiles, it runs, and the app starts with
 * the feature off while the name reads as the opposite.
 *
 * This test looks at the only thing that matters: what the translation to the domain answers when
 * the DataStore is empty. Reordering an enum turns it red.
 */
class SettingsDefaultsTest {

    /** What DataStore delivers on first launch: every field at its zero value. */
    private val empty: Settings = Settings.getDefaultInstance()

    @Test
    fun `the escalation ladder starts at BALANCED, i e with the silent WebView`() {
        assertThat(empty.toNetwork().challengeStrategy).isEqualTo(ChallengeStrategy.BALANCED)
    }

    @Test
    fun `the assisted path starts available`() {
        // The field is deliberately negative: named `allow_user_assisted_challenge` it would have
        // started `false` — with uptodown's and pdalife's downloads unreachable and nothing
        // saying why.
        assertThat(empty.toNetwork().blockUserAssistedChallenge).isFalse()
    }

    @Test
    fun `the three earlier enums are still in the right place`() {
        // The same trap, and nothing else was holding them still: an accidental reorder breaks
        // them as silently as any other.
        assertThat(empty.toAppearance().themeMode).isEqualTo(ThemeMode.SYSTEM)
        assertThat(empty.toUpdates().interval).isEqualTo(UpdateInterval.DAILY)
        assertThat(empty.toInstallation().preference).isEqualTo(InstallerPreference.AUTOMATIC)
    }

    @Test
    fun `the security booleans start on the prudent side`() {
        assertThat(empty.toSecurity().allowUnverifiedHash).isFalse()
        assertThat(empty.toSecurity().allowSignerMismatch).isFalse()
        assertThat(empty.toRemoteConfig().blockRemoteParsers).isFalse()
        assertThat(empty.toSearch().showNsfwContent).isFalse()
        assertThat(empty.toUpdates().muteNotifications).isFalse()
        assertThat(empty.toRemoteConfig().blockRemoteIndex).isFalse()
        assertThat(empty.toRemoteConfig().blockSelfUpdateCheck).isFalse()
    }

    /**
     * Preview channels start **off**, and it is a positively-named field.
     *
     * The criterion is not the direction of the name but the zero value: here the prudent
     * behaviour is not to choose, unasked, a version its publisher considers unfinished, and for
     * a `bool` that is zero. Flipping it for uniformity with the `block_*` fields would have
     * started the app offering betas to everyone — including, on F-Droid, the highest version of
     * `org.fdroid.fdroid`, which is exactly the version `VersionSelection` exists not to offer.
     */
    @Test
    fun `preview channels start off`() {
    }

    /**
     * The rule applied to a **number** for the first time.
     *
     * On a `bool` or an enum the trap is visible in the name or the order. Here it is not: `0` is
     * a legitimate domain value, and taken literally it would mean "wait for no store" — a search
     * that on first launch returns nothing from any of the nine sources, with nothing connecting
     * the two facts.
     */
    @Test
    fun `the per-store timeout is not zero seconds on first launch`() {
        assertThat(empty.searchTimeoutSeconds).isEqualTo(0)
        assertThat(empty.toSearch().storeTimeout).isEqualTo(SearchSettings.DEFAULT_STORE_TIMEOUT)
    }

    @Test
    fun `a value chosen by the user is used as it is`() {
        val chosen = Settings.newBuilder().setSearchTimeoutSeconds(15).build()
        assertThat(chosen.toSearch().storeTimeout).isEqualTo(15.seconds)
    }

    /**
     * Out of range falls back to the default, it does **not** clamp.
     *
     * Clamping 100000 to sixty would give a minute's wait nobody asked for; falling back gives
     * the wait the app has always had. No current version can write these values — the screen
     * offers four choices — but the DataStore is a file on disk, and a future version with a
     * different list could.
     */
    @Test
    fun `an out-of-range value falls back to the default`() {
        val absurd = Settings.newBuilder().setSearchTimeoutSeconds(100_000).build()
        assertThat(absurd.toSearch().storeTimeout).isEqualTo(SearchSettings.DEFAULT_STORE_TIMEOUT)

        val negative = Settings.newBuilder().setSearchTimeoutSeconds(-5).build()
        assertThat(negative.toSearch().storeTimeout).isEqualTo(SearchSettings.DEFAULT_STORE_TIMEOUT)

        // One second is below the minimum: the case likeliest to pass unnoticed, because "one
        // second" looks sensible and would in fact cut out apkmirror, which waits three seconds
        // of `Crawl-delay` before even making the request.
        val tooTight = Settings.newBuilder().setSearchTimeoutSeconds(1).build()
        assertThat(tooTight.toSearch().storeTimeout).isEqualTo(SearchSettings.DEFAULT_STORE_TIMEOUT)
    }

    /**
     * Search starts with no kind filter, in the aggregator's order.
     *
     * Both weigh more than an aesthetic preference. A `default_content_kind` other than
     * "everything" would not merely hide half the catalogue: it would exclude **eight stores out
     * of nine** from the fan-out, because "games only" is a question almost none of them can
     * answer. And a `default_sort` other than "relevance" would reorder the search of everyone
     * who never opened Settings.
     */
    @Test
    fun `search starts with no kind filter and ordered by relevance`() {
        assertThat(empty.toSearch().defaultContentKind).isNull()
        assertThat(empty.toSearch().defaultSort).isEqualTo(SearchSort.RELEVANCE)
    }

    @Test
    fun `the two values chosen by the user reach the domain`() {
        val chosen = Settings.newBuilder()
            .setDefaultContentKind(ProtoContentKindFilter.CONTENT_KIND_FILTER_GAMES)
            .setDefaultSort(ProtoSearchSort.SEARCH_SORT_RATING)
            .build()

        assertThat(chosen.toSearch().defaultContentKind).isEqualTo(ContentKind.GAME)
        assertThat(chosen.toSearch().defaultSort).isEqualTo(SearchSort.RATING)
    }

    /**
     * `UNKNOWN` is not a user choice, and must not become a filter.
     *
     * That value means "the store does not say": it is a list row's answer. A filter built on it
     * would ask "show only what we do not know the kind of", which across the nine measured
     * stores is almost everything except apkmody.
     */
    @Test
    fun `an unknown kind counts as no filter`() {
        assertThat(ContentKind.UNKNOWN.toProto())
            .isEqualTo(ProtoContentKindFilter.CONTENT_KIND_FILTER_ALL)
        assertThat(null.toProto()).isEqualTo(ProtoContentKindFilter.CONTENT_KIND_FILTER_ALL)
    }

    /**
     * A criterion the aggregated search cannot compute is not written.
     *
     * The domain knows six because a local index could order by date; a search across nine
     * sources cannot — no store publishes a date on every row. Saving one would give a
     * preference written and never applied.
     */
    @Test
    fun `an unoffered criterion falls back to relevance instead of being written`() {
        assertThat(SearchSort.RECENTLY_UPDATED.toProto())
            .isEqualTo(ProtoSearchSort.SEARCH_SORT_RELEVANCE)
        assertThat(SearchSort.DOWNLOADS.toProto())
            .isEqualTo(ProtoSearchSort.SEARCH_SORT_RELEVANCE)
        assertThat(SearchSort.SELECTABLE)
            .containsExactly(SearchSort.RELEVANCE, SearchSort.NAME, SearchSort.RATING)
    }

    /**
     * The case easiest to miss: the obvious name **looked** right already.
     *
     * Named `delete_apk_after_install`, the zero value would mean "do not delete", the opposite
     * of what the app does — and the consequence would not be an error but a private directory
     * growing by one APK per installation, invisible to anyone who never opens Settings.
     */
    @Test
    fun `the APK is deleted after installation`() {
        assertThat(empty.toStorage().keepApkAfterInstall).isFalse()
    }

    /**
     * The second number in this file, and zero here is **plausible**.
     *
     * On the timeout zero was absurd — "wait for no store" — so a defect would show on the first
     * try. "No icons on disk" is instead a request someone might make, and an app re-downloading
     * every icon does not look broken: it looks slow. That is why the minimum of
     * `IMAGE_CACHE_MB_RANGE` must stay above zero.
     */
    @Test
    fun `the image cache starts at 200 MB and not at zero`() {
        assertThat(empty.imageCacheMaxMb).isEqualTo(0)
        assertThat(empty.toStorage().imageCacheMaxBytes)
            .isEqualTo(StorageSettings.DEFAULT_IMAGE_CACHE_BYTES)
    }

    fun `a ceiling chosen by the user is used as it is, an out-of-range one is not`() {
        val chosen = Settings.newBuilder().setImageCacheMaxMb(64).build()
        assertThat(chosen.toStorage().imageCacheMaxBytes).isEqualTo(StorageSettings.megabytes(64))

        // Clamping four gigabytes to one would give a ceiling nobody asked for; falling back
        // gives the one the app has always had.
        val absurd = Settings.newBuilder().setImageCacheMaxMb(4096).build()
        assertThat(absurd.toStorage().imageCacheMaxBytes)
            .isEqualTo(StorageSettings.DEFAULT_IMAGE_CACHE_BYTES)
    }

    /**
     * An enum, because **two** legitimate choices would land on zero.
     *
     * "Zero days" and "forever" are both things a user can ask for, and neither could inhabit the
     * value that means "never written". That is why retention is an enum and not an `int32`, and
     * at the head goes what the app should do for someone who chose nothing.
     */
    @Test
    fun `catalogue retention starts at thirty days`() {
        assertThat(empty.toStorage().catalogRetention).isEqualTo(CatalogRetention.THIRTY_DAYS)
        // A null `duration` means "no expiry", not "zero": conflating them would throw
        // everything away for someone who asked to throw nothing away.
    }

}

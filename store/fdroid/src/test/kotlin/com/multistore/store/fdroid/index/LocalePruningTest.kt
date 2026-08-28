package com.multistore.store.fdroid.index

import com.google.common.truth.Truth.assertThat
import com.multistore.core.model.LocalizedText
import com.multistore.store.fdroid.Fixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Language pruning and payload fidelity")
class LocalePruningTest {

    private val json = Json

    private fun obj(text: String) = json.parseToJsonElement(text) as JsonObject

    @Test
    @DisplayName("keeps the app's 5 languages and their regional variants, discards the rest")
    fun keepsDisplayableLanguages() {
        val pruned = LocalePruning.pruneLocaleMap(
            obj("""{"en-US":"a","it":"b","de-DE":"c","ja":"d","zh-CN":"e","fr-FR":"f"}"""),
        )

        assertThat(pruned.keys).containsExactly("en-US", "it", "de-DE", "fr-FR")
    }

    @Test
    @DisplayName("an app translated only into a language we do not show is not left nameless")
    fun keepsOneEntryRatherThanNone() {
        val pruned = LocalePruning.pruneLocaleMap(obj("""{"ja":"名前","ko":"이름"}"""))

        // A field that disappears is not a saving, it is an app with no title.
        assertThat(pruned).hasSize(1)
    }

    @Test
    @DisplayName("on a merge patch the empty map is the right answer")
    fun patchesDoNotGetAFallback() {
        val pruned = LocalePruning.pruneLocaleMap(obj("""{"ja":"nuovo testo"}"""), keepFallback = false)

        // With the fallback on, a patch updating only the Japanese description would inject Japanese
        // into the stored payload, and from then on we would carry it around without ever being able
        // to show it. Empty means "no change", which is exactly what that patch does for us.
        assertThat(pruned).isEmpty()
    }

    @Test
    @DisplayName("screenshots are nested type -> locale, and the pruning respects that")
    fun screenshotsAreNestedTwice() {
        val metadata = Fixtures.slicePackage(Fixtures.PKG_FDROID).getValue("metadata") as JsonObject
        val pruned = LocalePruning.pruneMetadata(metadata)
        val screenshots = pruned.getValue("screenshots") as JsonObject

        // `{phone: {en-US: [...]}}`: pruning at the wrong level would lose every screenshot, or would
        // keep the `phone` key mistaking it for a language.
        assertThat(screenshots.keys).contains("phone")
        val phone = screenshots.getValue("phone") as JsonObject
        assertThat(phone.keys).isNotEmpty()
        assertThat(phone.keys.all { it.contains("-") || it.length <= 3 }).isTrue()
    }

    @Test
    @DisplayName("a version's antiFeatures are id -> locale, and the pruning respects that")
    fun versionAntiFeaturesArePrunedAtTheRightLevel() {
        val versions = Fixtures.slicePackage(Fixtures.PKG_PROTONVPN).getValue("versions") as JsonObject
        val version = versions.values.first { (it as JsonObject).containsKey("antiFeatures") } as JsonObject

        val pruned = LocalePruning.pruneVersion(version).getValue("antiFeatures") as JsonObject

        // `{NonFreeNet: {en-US: "..."}}`: pruning at the wrong level would lose the id, which is the
        // only part the projection actually uses.
        assertThat(pruned.keys).contains("NonFreeNet")
        assertThat((pruned.getValue("NonFreeNet") as JsonObject).keys).contains("en-US")
    }

    @Test
    @DisplayName("a language we do not show does not remain in a version's antiFeatures")
    fun versionAntiFeaturesDropUndisplayedLocales() {
        // On the real index there are 22 distinct locales where `pruning_profile` declares five: that
        // is the gap this pruning closes. The saving is modest (12 KB out of 257), the consistency is
        // not: it is the profile that decides when a full reload is needed.
        val version = Json.parseToJsonElement(
            """
            {
              "versionCode": 1,
              "antiFeatures": {
                "Tracking": { "en-US": "reason", "zh-CN": "\u7406\u7531", "ru-RU": "prichina" },
                "NonFreeNet": {}
              }
            }
            """.trimIndent(),
        ) as JsonObject

        val pruned = LocalePruning.pruneVersion(version).getValue("antiFeatures") as JsonObject

        assertThat((pruned.getValue("Tracking") as JsonObject).keys).containsExactly("en-US")
        // An already-empty map stays empty: it is a real case (`InfinityLoop1309.NewPipeEnhanced`)
        // and must neither become an error nor disappear, because the id is needed by the projection.
        assertThat(pruned.keys).contains("NonFreeNet")
    }

    @Test
    @DisplayName("the repo block's anti-features are id -> localised {name, description}")
    fun repoTaxonomiesArePrunedAtTheRightLevel() {
        val repo = Fixtures.jsonObject(Fixtures.INDEX_SLICE).getValue("repo") as JsonObject
        val pruned = IndexStreamReader.pruneRepo(repo)
        val info = IndexStreamReader.projectCatalog(pruned)

        assertThat(info.antiFeatures.map { it.id }).contains("Tracking")
        val tracking = info.antiFeatures.first { it.id == "Tracking" }
        // The name arrives already translated from the repository: putting it in strings.xml would
        // mean a release for every new anti-feature.
        assertThat(tracking.description.resolve(listOf("it"))).isNotEmpty()
        assertThat(info.categories).isNotEmpty()
    }

    @Test
    @DisplayName("pruning twice gives the same result")
    fun pruningIsIdempotent() {
        val pkg = Fixtures.slicePackage(Fixtures.PKG_FDROID)
        val once = LocalePruning.prunePackage(pkg)
        val twice = LocalePruning.prunePackage(once)

        assertThat(twice).isEqualTo(once)
    }

    @Test
    @DisplayName("numeric literals survive the round trip")
    fun numbersSurviveTheRoundTrip() {
        val pkg = Fixtures.slicePackage(Fixtures.PKG_FDROID)
        val reserialized = json.encodeToString(JsonObject.serializer(), LocalePruning.prunePackage(pkg))

        // It is why the subtrees go through kotlinx.serialization and not through Moshi's value
        // reader, which would turn every number into a Double: a `versionCode` coming back out as
        // `2.00004E6` would make the stored payload different from the one F-Droid signs, and the
        // merge patches would apply to a wrong base.
        assertThat(reserialized).contains("2000040")
        assertThat(reserialized).doesNotContain("E6")
        assertThat(reserialized).contains("1023052")
    }

    @Test
    @DisplayName("the set of kept languages derives from the app's languages")
    fun keptLanguagesFollowTheAppLanguages() {
        // If one day the app gains a sixth language, this is the line that makes the sync start
        // keeping it without anyone having to remember.
        assertThat(LocalizedText.DISPLAYABLE_TAGS).containsExactly("en", "it", "fr", "es", "de")
    }
}

package com.multistore.store.fdroid.index

import com.google.common.truth.Truth.assertThat
import com.multistore.store.fdroid.Fixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Merge patch — the semantics F-Droid expresses an incremental update with")
class JsonMergePatchTest {

    private val json = Json

    private fun parse(text: String) = json.parseToJsonElement(text)

    @Test
    @DisplayName("fields merge recursively")
    fun mergesRecursively() {
        val result = JsonMergePatch.apply(
            parse("""{"a":1,"nested":{"x":1,"y":2}}"""),
            parse("""{"b":2,"nested":{"y":9}}"""),
        ) as JsonObject

        assertThat(result.keys).containsExactly("a", "b", "nested")
        val nested = result["nested"] as JsonObject
        // `x` is not named by the patch, so it survives: it is the difference between a merge patch
        // and a replacement, and it is the mistake that would corrupt the index silently.
        assertThat(nested["x"]).isEqualTo(JsonPrimitive(1))
        assertThat(nested["y"]).isEqualTo(JsonPrimitive(9))
    }

    @Test
    @DisplayName("null deletes the field")
    fun nullRemovesAField() {
        val result = JsonMergePatch.apply(parse("""{"a":1,"b":2}"""), parse("""{"b":null}""")) as JsonObject

        assertThat(result.keys).containsExactly("a")
    }

    @Test
    @DisplayName("a patch that is null deletes the whole document")
    fun nullPatchRemovesEverything() {
        assertThat(JsonMergePatch.apply(parse("""{"a":1}"""), JsonNull)).isNull()
    }

    @Test
    @DisplayName("a patch on an absent document creates it")
    fun patchOnMissingTargetCreatesIt() {
        val result = JsonMergePatch.apply(null, parse("""{"a":1}""")) as JsonObject

        assertThat(result["a"]).isEqualTo(JsonPrimitive(1))
    }

    @Test
    @DisplayName("an array replaces, it does not merge")
    fun arraysAreReplaced() {
        val result = JsonMergePatch.apply(parse("""{"a":[1,2,3]}"""), parse("""{"a":[9]}""")) as JsonObject

        assertThat(result["a"].toString()).isEqualTo("[9]")
    }

    @Test
    @DisplayName("applying the same patch twice gives the same result")
    fun patchIsIdempotent() {
        val base = parse("""{"a":1,"b":2}""")
        val patch = parse("""{"b":null,"c":3}""")

        val once = JsonMergePatch.apply(base, patch)
        val twice = JsonMergePatch.apply(once, patch)

        // It matters because an interrupted sync can be repeated: if the patch were not idempotent,
        // retrying would worsen the state instead of repairing it.
        assertThat(twice).isEqualTo(once)
    }

    @Test
    @DisplayName("the real diff deletes a package, deletes a version and adds one")
    fun realDiffDoesAllThreeOperations() {
        val diff = Fixtures.jsonObject(Fixtures.DIFF_SLICE)
        val packages = diff.getValue("packages") as JsonObject

        assertThat(packages[Fixtures.PKG_SNAKE]).isEqualTo(JsonNull)

        val catimaPatch = packages.getValue(Fixtures.PKG_CATIMA) as JsonObject
        val versionsPatch = catimaPatch.getValue("versions") as JsonObject
        assertThat(versionsPatch.values.any { it is JsonNull }).isTrue()
        assertThat(versionsPatch.values.any { it is JsonObject }).isTrue()

        val before = Fixtures.slicePackage(Fixtures.PKG_CATIMA)
        val after = JsonMergePatch.apply(before, catimaPatch) as JsonObject
        val versionsBefore = (before.getValue("versions") as JsonObject).size
        val versionsAfter = (after.getValue("versions") as JsonObject).size
        // One path added and one removed: the count stays the same, the keys do not.
        assertThat(versionsAfter).isEqualTo(versionsBefore)
        assertThat((after.getValue("versions") as JsonObject).keys)
            .isNotEqualTo((before.getValue("versions") as JsonObject).keys)
    }
}

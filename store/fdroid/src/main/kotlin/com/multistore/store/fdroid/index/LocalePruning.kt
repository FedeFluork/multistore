package com.multistore.store.fdroid.index

import com.multistore.core.model.LocalizedText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Reduces the index's localised maps to the languages the app can show.
 *
 * The count justifying this pruning: the index carries about 30 translations per description, over
 * 4,233 descriptions. They are the biggest contributor to the 57 MB, and none of the translations we
 * discard can appear on screen, because the interface exists in five languages.
 *
 * The pruning happens **before** saving the payload, so it touches the merge patches too: a patch
 * updating the Japanese description is simply ignored, which is the right thing given that
 * description is not there.
 *
 * ### The rule that avoids the damage
 *
 * If a map would be left **empty** — an app translated only into Japanese — the first original entry
 * is kept. A field that disappears is not a saving, it is an app with no name.
 */
object LocalePruning {

    /**
     * The `metadata` fields that are `{locale -> value}` maps.
     *
     * `icon`, `featureGraphic` and their relatives have the same shape, but the value is a file
     * object instead of a string: the pruning works on keys, so it does not tell them apart and does
     * not need to.
     */
    private val LOCALIZED_METADATA_FIELDS = setOf(
        "name", "summary", "description", "video",
        "icon", "featureGraphic", "promoGraphic", "tvBanner",
    )

    /** `screenshots` is nested one level deeper: `{type -> {locale -> [file]}}`. */
    private const val SCREENSHOTS = "screenshots"

    /**
     * A version's `antiFeatures` has the same nested shape as [SCREENSHOTS]:
     * `{id -> {locale -> reason}}`.
     *
     * Measured against the real index: 2,677 versions out of 12,911 have it, with **22 distinct
     * locales** while `pruning_profile` declares five. The saving is modest — 12 KB out of 257 — so
     * it is not a space fix but a consistency one: the stored profile has to tell the truth about
     * what the payload contains, because it is what decides when a full reload is needed.
     *
     * Not to be confused with the `repo` block's `antiFeatures`, which are
     * `{id -> {name|description -> {locale -> text}}}` and are pruned by `IndexStreamReader`.
     */
    private const val ANTI_FEATURES = "antiFeatures"

    /** The localised fields inside a version. */
    private val LOCALIZED_VERSION_FIELDS = setOf("whatsNew")

    private val KEPT: Set<String> = LocalizedText.DISPLAYABLE_TAGS

    /**
     * Prunes a whole package object (`{metadata, versions}`).
     *
     * @param keepFallback keep some language when none is showable. It holds for a **complete**
     * document, where an empty field would be an app with no name. On a **merge patch** it must be
     * off: a patch updating only the Japanese description would otherwise be kept as though Japanese
     * mattered to us, and from then on the stored payload would contain a language we will never
     * show. On a patch the empty map is the right answer: it means "no change", which is exactly
     * what happened as far as we are concerned.
     */
    fun prunePackage(pkg: JsonObject, keepFallback: Boolean = true): JsonObject {
        val out = pkg.toMutableMap()
        (pkg["metadata"] as? JsonObject)?.let { out["metadata"] = pruneMetadata(it, keepFallback) }
        (pkg["versions"] as? JsonObject)?.let { versions ->
            out["versions"] = JsonObject(
                versions.mapValues { (_, v) ->
                    (v as? JsonObject)?.let { pruneVersion(it, keepFallback) } ?: v
                },
            )
        }
        return JsonObject(out)
    }

    fun pruneMetadata(metadata: JsonObject, keepFallback: Boolean = true): JsonObject {
        val out = metadata.toMutableMap()
        for (field in LOCALIZED_METADATA_FIELDS) {
            (metadata[field] as? JsonObject)?.let { out[field] = pruneLocaleMap(it, keepFallback) }
        }
        (metadata[SCREENSHOTS] as? JsonObject)?.let { byKind ->
            out[SCREENSHOTS] = JsonObject(
                byKind.mapValues { (_, v) ->
                    (v as? JsonObject)?.let { pruneLocaleMap(it, keepFallback) } ?: v
                },
            )
        }
        return JsonObject(out)
    }

    fun pruneVersion(version: JsonObject, keepFallback: Boolean = true): JsonObject {
        val out = version.toMutableMap()
        for (field in LOCALIZED_VERSION_FIELDS) {
            (version[field] as? JsonObject)?.let { out[field] = pruneLocaleMap(it, keepFallback) }
        }
        (version[ANTI_FEATURES] as? JsonObject)?.let { byId ->
            out[ANTI_FEATURES] = JsonObject(
                byId.mapValues { (_, v) ->
                    (v as? JsonObject)?.let { pruneLocaleMap(it, keepFallback) } ?: v
                },
            )
        }
        return JsonObject(out)
    }

    /** Keeps the showable languages; see [prunePackage] for [keepFallback]'s role. */
    fun pruneLocaleMap(map: JsonObject, keepFallback: Boolean = true): JsonObject {
        if (map.isEmpty()) return map
        val kept = map.filterKeys { tag ->
            val lower = tag.lowercase()
            lower in KEPT || lower.substringBefore('-') in KEPT
        }
        if (kept.isNotEmpty()) return JsonObject(kept)
        if (!keepFallback) return JsonObject(emptyMap())
        val fallbackKey = map.keys.minOrNull() ?: return map
        return JsonObject(mapOf(fallbackKey to map.getValue(fallbackKey)))
    }

    /** Extracts a `{locale -> string}` map as [LocalizedText]. */
    fun toLocalizedText(element: JsonElement?): LocalizedText {
        val obj = element as? JsonObject ?: return LocalizedText.EMPTY
        val entries = obj.mapNotNull { (tag, value) ->
            (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { tag to it }
        }
        return if (entries.isEmpty()) LocalizedText.EMPTY else LocalizedText(entries.toMap())
    }

    /** The `{locale -> [file]}` entries flattened into `(locale, file object)` pairs. */
    fun localizedFileList(element: JsonElement?): List<Pair<String, JsonObject>> {
        val obj = element as? JsonObject ?: return emptyList()
        return obj.entries.flatMap { (tag, value) ->
            when (value) {
                is JsonArray -> value.filterIsInstance<JsonObject>().map { tag to it }
                is JsonObject -> listOf(tag to value)
                else -> emptyList()
            }
        }
    }
}

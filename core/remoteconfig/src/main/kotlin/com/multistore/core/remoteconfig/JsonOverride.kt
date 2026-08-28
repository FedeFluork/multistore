package com.multistore.core.remoteconfig

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/**
 * Overlays a partial override onto a compiled configuration.
 *
 * ### One rule, applied twice
 *
 * We descend recursively **only when both sides are objects**; in every other case the override's
 * value replaces the default's entirely. Merging an array element by element would look finer and
 * would be a trap: there is no way of **removing** an entry from a merged list, so a list of
 * alternative selectors could only grow.
 *
 * ### Keys the default does not have are discarded, and counted
 *
 * It is not format pedantry: it is the only way to notice a typo. `searchItm` instead of
 * `searchItem` would produce, with a permissive merge, an accepted document, a valid signature, and
 * **no effect** — the worst of diagnoses, the one where everything looks fine. By discarding the key
 * and reporting its path, the Settings screen can say "1 key ignored:
 * uptodown.selectors.searchItm", which is a sentence one can act on.
 *
 * A useful side effect: by filtering before decoding, the parser stays **strict**. There is no need
 * for `ignoreUnknownKeys`, so an unknown key cannot pass unnoticed from either side.
 */
internal object JsonOverride {

    data class Merged(
        val value: JsonObject,
        /** The paths of the override's keys the compiled configuration does not know. */
        val ignored: List<String>,
    )

    fun merge(default: JsonObject, override: JsonObject): Merged {
        val ignored = mutableListOf<String>()
        val value = mergeInto(default, override, prefix = "", ignored = ignored)
        return Merged(value = value, ignored = ignored)
    }

    private fun mergeInto(
        default: JsonObject,
        override: JsonObject,
        prefix: String,
        ignored: MutableList<String>,
    ): JsonObject = buildJsonObject {
        default.forEach { (key, defaultValue) ->
            val overrideValue = override[key]
            val merged = when {
                overrideValue == null -> defaultValue
                defaultValue is JsonObject && overrideValue is JsonObject ->
                    mergeInto(defaultValue, overrideValue, "$prefix$key.", ignored)
                else -> overrideValue
            }
            put(key, merged)
        }
        override.keys
            .filterNot { it in default }
            .forEach { ignored += "$prefix$it" }
    }
}

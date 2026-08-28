package com.multistore.store.fdroid.index

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * The merge patch F-Droid expresses an incremental index update with.
 *
 * It is RFC 7386 semantics, verified against the real diff: an object merges recursively, and
 * **`null` deletes** — both a whole package (`packages.<id> = null`) and a single version
 * (`packages.<id>.versions.<sha> = null`). What the patch does not name stays as it was.
 *
 * The consequence worth keeping in mind: a value that in the original document is *legitimately*
 * `null` is indistinguishable from a deletion. In the F-Droid index that does not happen — no field
 * is ever `null` — but it is this format's known limit, not a defect of this implementation.
 *
 * The measured gain: the smallest published diff weighs 252 KB on the wire against the whole index's
 * 17.8 MB. Seventy times less.
 */
object JsonMergePatch {

    /**
     * Applies [patch] to [target].
     *
     * @return the resulting document, or `null` if the patch deletes the target.
     */
    fun apply(target: JsonElement?, patch: JsonElement): JsonElement? {
        if (patch is JsonNull) return null
        if (patch !is JsonObject) return patch
        val base = (target as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        for ((key, value) in patch) {
            if (value is JsonNull) {
                base.remove(key)
            } else {
                val merged = apply(base[key], value)
                if (merged == null) base.remove(key) else base[key] = merged
            }
        }
        return JsonObject(base)
    }
}

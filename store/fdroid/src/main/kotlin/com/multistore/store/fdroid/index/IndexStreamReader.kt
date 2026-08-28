package com.multistore.store.fdroid.index

import com.multistore.core.model.AntiFeature
import com.multistore.core.model.Category
import com.multistore.store.api.StoreCatalogInfo
import com.squareup.moshi.JsonReader
import java.io.Closeable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.BufferedSource

/**
 * Walks `index-v2.json` (or one of its diffs) without building its tree.
 *
 * The file weighs **57,037,287 bytes** in plaintext. Building its object representation on a phone
 * means a few hundred megabytes of heap and an `OutOfMemoryError` on plenty of devices: that is not
 * a theoretical risk, it is why this class exists.
 *
 * The division of labour between the project's two JSON libraries is deliberate:
 *
 *  - **Moshi's `JsonReader`** walks the root object one field at a time, and with `nextSource()`
 *    hands over the raw bytes of a single package's subtree without decoding them;
 *  - **kotlinx.serialization** works on those bytes, i.e. on ~13 KB at a time, and preserves numeric
 *    literals exactly as written. That matters, because we re-serialise that JSON to save it and
 *    then apply merge patches on top: a `versionCode` coming back out as `1.023052E6` would make the
 *    payload different from the one F-Droid signed.
 *
 * The memory peak is therefore one package at a time, not 4,257.
 *
 * The API is a [Sequence] and not a callback so that the consumer sets the pace: the reader advances
 * only when someone asks for the next entry, and a cancelled sync stops where it is instead of
 * reading to the end to find out.
 */
class IndexStreamReader(
    source: BufferedSource,
    private val json: Json = DEFAULT_JSON,
) : Closeable {

    private val reader = JsonReader.of(source)

    sealed interface Event {
        /** The `repo` block, raw: in a diff it is a merge patch, not a complete document. */
        data class Repo(val raw: JsonObject) : Event

        /**
         * An entry of `packages`.
         *
         * [raw] is `JsonNull` when the diff deletes the package: it is the value F-Droid uses to say
         * "this is no longer here", and it matters that it reaches whoever writes to disk instead of
         * being discarded here as "malformed".
         */
        data class Package(val name: String, val raw: JsonElement) : Event
    }

    fun events(): Sequence<Event> = sequence {
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                FIELD_REPO -> {
                    val raw = json.parseToJsonElement(reader.nextSource().use { it.readUtf8() })
                    if (raw is JsonObject) yield(Event.Repo(raw))
                }

                FIELD_PACKAGES -> {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        val name = reader.nextName()
                        val raw = json.parseToJsonElement(reader.nextSource().use { it.readUtf8() })
                        yield(Event.Package(name, raw))
                    }
                    reader.endObject()
                }

                else -> reader.skipValue()
            }
        }
        reader.endObject()
    }

    override fun close() {
        reader.close()
    }

    companion object {
        const val FIELD_REPO = "repo"
        const val FIELD_PACKAGES = "packages"
        private const val FIELD_CATEGORIES = "categories"
        private const val FIELD_ANTI_FEATURES = "antiFeatures"
        private const val FIELD_TIMESTAMP = "timestamp"

        /**
         * `isLenient = false` on purpose: the index is covered by a signature, so if it is not valid
         * JSON the problem is not the parser's tolerance.
         */
        val DEFAULT_JSON: Json = Json {
            ignoreUnknownKeys = true
            isLenient = false
        }

        /** Prunes the `repo` block to the showable languages only, as is done for the packages. */
        fun pruneRepo(repo: JsonObject): JsonObject {
            val out = repo.toMutableMap()
            (repo[FIELD_CATEGORIES] as? JsonObject)?.let { out[FIELD_CATEGORIES] = pruneTaxonomy(it) }
            (repo[FIELD_ANTI_FEATURES] as? JsonObject)?.let { out[FIELD_ANTI_FEATURES] = pruneTaxonomy(it) }
            return JsonObject(out)
        }

        private fun pruneTaxonomy(taxonomy: JsonObject): JsonObject = JsonObject(
            taxonomy.mapValues { (_, value) ->
                (value as? JsonObject)?.let { LocalePruning.pruneMetadata(it) } ?: value
            },
        )

        /** The readable taxonomies from the `repo` block, with names already localised by the store. */
        fun projectCatalog(repo: JsonObject): StoreCatalogInfo = StoreCatalogInfo(
            categories = (repo[FIELD_CATEGORIES] as? JsonObject)?.map { (id, value) ->
                Category(id = id, name = LocalePruning.toLocalizedText((value as? JsonObject)?.get("name")))
            }.orEmpty(),
            antiFeatures = (repo[FIELD_ANTI_FEATURES] as? JsonObject)?.map { (id, value) ->
                val obj = value as? JsonObject
                AntiFeature(
                    id = id,
                    name = LocalePruning.toLocalizedText(obj?.get("name")),
                    description = LocalePruning.toLocalizedText(obj?.get("description")),
                )
            }.orEmpty(),
        )

        fun repoTimestamp(repo: JsonObject): Long? =
            (repo[FIELD_TIMESTAMP] as? JsonPrimitive)?.content?.toLongOrNull()
    }
}

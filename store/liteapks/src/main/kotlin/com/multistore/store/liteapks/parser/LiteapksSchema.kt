package com.multistore.store.liteapks.parser

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The `SoftwareApplication` schema.org block every liteapks listing publishes.
 *
 * It is not theme markup, and that is the only reason it is read from here instead of from the
 * Tailwind classes next to it: schema.org is a stable vocabulary, `div.value` is a styling choice.
 * The measurement justifying it is in `LiteapksSelectors`: **31 listings out of 31** carry the
 * block, with `name`, `softwareVersion`, `applicationCategory` and a complete `aggregateRating`.
 *
 * ### The rating arrives with its own scale, and that scale is used
 *
 * `"aggregateRating": { "bestRating": 5, "worstRating": 1, "ratingCount": 3883, "ratingValue": 4 }`.
 * `worstRating` at **one** is what makes zero readable: on the card the rating block appears anyway,
 * so a `0` is not a terrible judgement but the absence of judgements. It is exactly the distinction
 * that on pdalife cost a dedicated fixture — here the store declares it, and no guessing is needed.
 */
internal data class LiteapksSchemaApp(
    val name: String?,
    val version: String?,
    val category: String?,
    val rating: Float?,
    val ratingCount: Int?,
    val bestRating: Float?,
) {
    companion object {

        /**
         * The first JSON block of type `SoftwareApplication` among those passed in.
         *
         * The listings have **two**: Yoast's graph (`@graph` with `Article`, `WebPage`, `Person`…)
         * and this one. The choice is by `@type`, not by position: Yoast's graph is the first in
         * the document, and taking it would give an object with none of the needed fields — i.e. a
         * mute listing instead of an error.
         */
        fun firstIn(blocks: List<String>): LiteapksSchemaApp? =
            blocks.asSequence().mapNotNull(::parse).firstOrNull()

        private fun parse(raw: String): LiteapksSchemaApp? {
            val root = runCatching { LENIENT.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
            if (root.string(TYPE) != SOFTWARE_APPLICATION) return null
            val rating = root[RATING] as? JsonObject
            return LiteapksSchemaApp(
                name = root.string(NAME),
                version = root.string(VERSION),
                category = root.string(CATEGORY),
                rating = rating?.number(RATING_VALUE),
                ratingCount = rating?.let { (it[RATING_COUNT] as? JsonPrimitive)?.intOrNull },
                bestRating = rating?.number(BEST_RATING),
            )
        }

        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotBlank() }

        /**
         * A number, which the site writes **in both forms**.
         *
         * `"ratingValue": 4` on Telegram and `"ratingValue": 3.9` on Minecraft are JSON numbers;
         * nothing stops them becoming strings tomorrow, as many WordPress themes do. Reading only
         * the form seen today would turn that change into a rating that disappears with no error.
         */
        private fun JsonObject.number(key: String): Float? {
            val primitive = this[key] as? JsonPrimitive ?: return null
            return primitive.floatOrNull ?: primitive.content.trim().toFloatOrNull()
        }

        private val LENIENT = Json { ignoreUnknownKeys = true; isLenient = true }

        private const val TYPE = "@type"
        private const val SOFTWARE_APPLICATION = "SoftwareApplication"
        private const val NAME = "name"
        private const val VERSION = "softwareVersion"
        private const val CATEGORY = "applicationCategory"
        private const val RATING = "aggregateRating"
        private const val RATING_VALUE = "ratingValue"
        private const val RATING_COUNT = "ratingCount"
        private const val BEST_RATING = "bestRating"
    }
}

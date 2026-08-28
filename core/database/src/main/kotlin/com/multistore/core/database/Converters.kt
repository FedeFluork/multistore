package com.multistore.core.database

import androidx.room.TypeConverter
import com.multistore.core.model.LocalizedText
import com.multistore.core.model.Sha256
import com.multistore.core.model.StoreId
import kotlin.time.Instant
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Conversions between domain types and SQLite columns.
 *
 * Two choices worth stating rather than absorbing:
 *
 *  - **[StoreId] goes through its `wireName`, not the constant's name.** Renaming `StoreId.FDROID`
 *    must not invalidate the database of someone who already has the app installed.
 *  - **[Sha256] stays a normalised string.** The type exists precisely so an uppercase and a
 *    lowercase digest do not look different; letting it return to a raw `String` on the way into
 *    the database would reopen exactly that hole.
 *
 * An unreadable value becomes `null`, not an exception: a database that refuses to open because a
 * row holds an unknown enum is worse than the missing datum, and with a remote configuration that
 * can introduce new values it is not a theoretical scenario.
 */
object Converters {

    private val json = Json { ignoreUnknownKeys = true }

    @TypeConverter
    fun instantToLong(value: Instant?): Long? = value?.toEpochMilliseconds()

    @TypeConverter
    fun longToInstant(value: Long?): Instant? = value?.let(Instant::fromEpochMilliseconds)

    @TypeConverter
    fun storeIdToString(value: StoreId?): String? = value?.wireName

    @TypeConverter
    fun stringToStoreId(value: String?): StoreId? = value?.let(StoreId::fromWireNameOrNull)

    @TypeConverter
    fun sha256ToString(value: Sha256?): String? = value?.hex

    @TypeConverter
    fun stringToSha256(value: String?): Sha256? = Sha256.parseOrNull(value)

    @TypeConverter
    fun stringListToJson(value: List<String>?): String? =
        value?.let { json.encodeToString(ListSerializer(String.serializer()), it) }

    @TypeConverter
    fun jsonToStringList(value: String?): List<String>? = value?.let {
        runCatching { json.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull()
    }

    /**
     * A download request's headers: `Referer`, `Cookie`, the WebView's UA.
     *
     * They are on the row and not recomputed at resume time because they cannot be recomputed:
     * apkmirror's `Referer` is the URL of the interstitial traversed back then, and the assisted
     * path's `Cookie` belongs to the session in which the user made the tap. A download resuming
     * after the process died has no other way of knowing them.
     */
    @TypeConverter
    fun stringMapToJson(value: Map<String, String>?): String? = value?.let {
        json.encodeToString(MapSerializer(String.serializer(), String.serializer()), it)
    }

    @TypeConverter
    fun jsonToStringMap(value: String?): Map<String, String>? = value?.let {
        runCatching {
            json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), it)
        }.getOrNull()
    }

    @TypeConverter
    fun localizedTextToJson(value: LocalizedText?): String? = value?.let {
        json.encodeToString(MapSerializer(String.serializer(), String.serializer()), it.byTag)
    }

    @TypeConverter
    fun jsonToLocalizedText(value: String?): LocalizedText? = value?.let {
        runCatching {
            LocalizedText(json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), it))
        }.getOrNull()
    }
}

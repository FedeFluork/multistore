package com.multistore.core.database

import androidx.room.TypeConverter
import com.multistore.core.model.LocalizedText
import com.multistore.core.model.Sha256
import com.multistore.core.model.StoreId
import com.multistore.core.model.UsesPermission
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

    /**
     * The `<uses-permission>` lines of one build.
     *
     * JSON and not a joined string, because a permission is a **pair** — name and
     * `android:maxSdkVersion` — and packing two fields into `name:max` would make the column
     * unparseable the day a name legitimately contains the separator. It costs a serializer and
     * removes a class of bug entirely.
     *
     * `null` in, `null` out: the column's nullability carries "nobody has read this build's
     * manifest", which is a different answer from "this build asks for nothing". A converter that
     * turned `null` into `[]` would erase that distinction on the way to disk, where nothing could
     * recover it.
     */
    @TypeConverter
    fun permissionsToJson(value: List<UsesPermission>?): String? =
        value?.let { json.encodeToString(ListSerializer(UsesPermission.serializer()), it) }

    @TypeConverter
    fun jsonToPermissions(value: String?): List<UsesPermission>? = value?.let {
        runCatching { json.decodeFromString(ListSerializer(UsesPermission.serializer()), it) }.getOrNull()
    }

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

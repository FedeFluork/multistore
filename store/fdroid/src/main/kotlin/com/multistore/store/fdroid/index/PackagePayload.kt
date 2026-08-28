package com.multistore.store.fdroid.index

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The package name inside the payload we keep.
 *
 * The v2 index holds the `packageName` as the **key** of the `packages` map, not as a field of the
 * object. Extracting that object and saving it alone loses the one piece of information saying who
 * it is about — and the loss is not immediately visible: saving works, the full sync's projection
 * works (whoever reads the stream still has the name in hand), and the fault arrives months later on
 * the first incremental update, when the payload is re-read on its own and it is no longer known
 * which app it belongs to.
 *
 * So the name is written **inside** the payload, under a key the index does not use: the leading
 * hyphen keeps it out of any collision with the real fields, which are all camelCase. It holds for
 * complete documents and for merge patches alike — a patch can introduce a package that was not
 * there before, and that one too must know what it is called.
 */
internal object PackagePayload {

    const val FIELD_PACKAGE_NAME: String = "-packageName"

    fun withPackageName(payload: JsonObject, packageName: String): JsonObject =
        JsonObject(payload + (FIELD_PACKAGE_NAME to JsonPrimitive(packageName)))

    fun packageNameOf(payload: JsonObject): String? =
        (payload[FIELD_PACKAGE_NAME] as? JsonPrimitive)?.takeIf { it.isString }?.content
}

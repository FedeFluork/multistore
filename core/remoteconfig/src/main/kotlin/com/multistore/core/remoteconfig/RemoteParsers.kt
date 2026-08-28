package com.multistore.core.remoteconfig

import com.multistore.core.model.StoreId
import com.multistore.core.model.WebFilterConfig
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * "The compiled defaults, with the override on top if the signature is valid."
 *
 * It is the function `:app` interposes between a configuration's constructor and the adapter
 * receiving it, and it is the only point in the project where the remote config touches anything:
 *
 * ```kotlin
 * @Provides @Singleton
 * fun provideUptodownConfig(parsers: RemoteParsers): UptodownConfig =
 *     parsers.override(StoreId.UPTODOWN, UptodownConfig(), UptodownConfig.serializer())
 * ```
 *
 * ### Why it goes through JSON instead of having a per-store `copy` method
 *
 * Because a per-store method would be a list to keep aligned by hand, and the next time someone adds
 * a field to a configuration they would forget: the field would exist, the document could name it,
 * and nothing would happen. Going through the serialised representation, **every field of every
 * configuration is overridable by construction**, including those that do not exist yet.
 *
 * ### The `KSerializer` is passed by hand, and that is not verbosity
 *
 * The first draft also had a convenient `inline fun <reified T> override(storeId, default)` that
 * resolved the serializer by itself. It compiled, and **failed at runtime**: kotlinx's plugin
 * rewrites `serializer<T>()` only where `T` is known at compile time, and inside the body of an
 * inline function declared here `T` is a type parameter — so the reflective variant came out, which
 * on the first launch said `Serializer for class 'FdroidConfig' is not found`.
 *
 * The reason was real: `FdroidConfig` **was not `@Serializable`**, against point 7 of the
 * new-store checklist, and nobody could notice because nobody had ever serialised it. An API that
 * resolves reflectively turns that omission into a crash on the user's device — and it is also
 * exactly the kind of thing R8 breaks in release and not in debug. With the explicit serializer the
 * same omission **does not compile**, which is where an error of that kind must appear.
 *
 * ### A broken override costs one store, not all of them
 *
 * If a field's value has the wrong type — `permitsPerSecond: "fast"` — decoding fails and **that
 * store** returns to its compiled defaults, while the others stay updated. Discarding the whole
 * document would be simpler to explain and worse to suffer: a typo on an1 would put apkmirror back
 * as it was before the fix being published precisely for it.
 */
@Singleton
class RemoteParsers @Inject constructor(
    private val store: RemoteConfigStore,
) {

    /**
     * The configuration to use for [storeId].
     *
     * It returns [default] unchanged when there is no document, when the document does not name this
     * store, and when the override does not hold. It never throws: a remote configuration that could
     * make startup fail would be a way of breaking the app remotely, i.e. the opposite of what it
     * exists for.
     */
    fun <T> override(storeId: StoreId, default: T, serializer: KSerializer<T>): T {
        val patch: JsonObject = store.parsersFor(storeId) ?: return default

        val encoded = runCatching { JSON.encodeToJsonElement(serializer, default) as? JsonObject }
            .getOrNull()
            ?: return default

        val merged = JsonOverride.merge(encoded, patch)
        store.noteIgnoredKeys(merged.ignored.map { "${storeId.wireName}.$it" })

        return runCatching { JSON.decodeFromJsonElement(serializer, merged.value) }
            .getOrElse {
                store.noteRejectedStore(storeId)
                default
            }
    }

    /**
     * The WebView filter, with the document's override on top if there is one.
     *
     * Same shape as [override] and the same guarantees — partial merge, unknown keys counted and
     * shown in Settings, a wrongly typed value costing only the filter and not the document — with
     * one difference: **it is not per store**, so it has no `StoreId` to label the discarded keys
     * with. The prefix is the section's name.
     *
     * An override that does not hold leaves the **compiled defaults**, i.e. the filter goes on
     * blocking what it has always blocked. That is the cautious direction: the alternative would be
     * a WebView with no filter on pages chosen for being full of advertising.
     */
    fun webFilter(default: WebFilterConfig = WebFilterConfig()): WebFilterConfig {
        val patch: JsonObject = store.webFilter() ?: return default
        val encoded = runCatching {
            JSON.encodeToJsonElement(WebFilterConfig.serializer(), default) as? JsonObject
        }.getOrNull() ?: return default

        val merged = JsonOverride.merge(encoded, patch)
        store.noteIgnoredKeys(merged.ignored.map { "$WEB_FILTER_SECTION.$it" })

        return runCatching { JSON.decodeFromJsonElement(WebFilterConfig.serializer(), merged.value) }
            .getOrElse { default }
    }

    companion object {
        /**
         * `encodeDefaults = true`, and it is the linchpin of the whole mechanism.
         *
         * Without it, kotlinx omits from serialisation every field left at its default — i.e.
         * **all** of them, because a compiled configuration is made only of defaults. The map
         * [JsonOverride] looks for known keys in would be empty, every override key would come out
         * unknown, and the remote config would never apply anything while declaring it had accepted
         * the document.
         */
        val JSON: Json = Json { encodeDefaults = true }

        /** The name a discarded filter key appears under in Settings. */
        const val WEB_FILTER_SECTION: String = "webFilter"
    }
}

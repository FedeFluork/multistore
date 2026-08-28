package com.multistore.store.fdroid.api

import com.multistore.core.model.LocalizedText
import com.multistore.core.model.StoreId
import com.multistore.core.model.StoreListingSummary
import com.multistore.core.network.http.StoreHttpClient
import com.multistore.store.api.PagedResult
import com.multistore.store.api.StoreResult
import com.multistore.store.common.StoreErrors
import com.multistore.store.common.storeCall
import com.multistore.store.fdroid.FdroidRefs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

/**
 * F-Droid's "App Search API", the only remote search this store offers.
 *
 * It sits on a host of its own — `search.f-droid.org`, not `f-droid.org/api/v1/` — and is documented
 * in *All our APIs*. It serves one purpose, and should be used only for that.
 *
 * ### What it can and cannot do
 *
 * Measured on 23/08/2026: it returns **10 results and no more**. `page`, `per_page` and `limit` are
 * ignored — `page=1`, `2` and `3` give the identical response — and so is `lang`. Each result has
 * four fields: `name`, `summary`, `icon`, `url`. No `packageName` (it is derived from the `url`), no
 * version, no hash, no category.
 *
 * It is not a search to build a screen on: ten unpageable results with no version are not enough
 * even to say "updatable". But it covers exactly the gap the local index has by construction: **the
 * first launch**, when the 17.8 MB sync has not finished — or has not even started, because the
 * network is metered and the user has not yet decided. Without this, in that window the search would
 * be an empty screen.
 *
 * The results must therefore be marked partial in the UI: they come from a poorer source than the
 * one the app will use a minute later.
 */
class FdroidSearchApi(
    private val http: StoreHttpClient,
    private val baseUrl: String,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    suspend fun search(query: String): StoreResult<PagedResult<StoreListingSummary>> = storeCall {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@storeCall StoreResult.Success(PagedResult.empty())

        val url = baseUrl.toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter(PARAM_QUERY, trimmed)
            ?.build()
            ?: return@storeCall StoreResult.Failure(StoreErrors.parseFailure(SELECTOR_URL, baseUrl))

        val response = http.execute(Request.Builder().url(url).build())
        val body = response.use {
            if (!it.isSuccessful) return@storeCall StoreResult.Failure(StoreErrors.fromResponse(it))
            it.body?.string()
        } ?: return@storeCall StoreResult.Success(PagedResult.empty())

        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject
            ?: return@storeCall StoreResult.Failure(StoreErrors.parseFailure(SELECTOR_ROOT, body.take(SNIPPET)))
        val apps = root[FIELD_APPS] as? JsonArray
            ?: return@storeCall StoreResult.Failure(StoreErrors.parseFailure(FIELD_APPS, body.take(SNIPPET)))

        StoreResult.Success(
            PagedResult.single(apps.filterIsInstance<JsonObject>().mapNotNull(::toSummary)),
        )
    }

    private fun toSummary(app: JsonObject): StoreListingSummary? {
        val packageName = app.string(FIELD_URL)?.let(::packageNameFromUrl) ?: return null
        val name = app.string(FIELD_NAME) ?: packageName
        return StoreListingSummary(
            storeId = StoreId.FDROID,
            ref = FdroidRefs.appRef(packageName),
            title = name,
            packageName = packageName,
            summary = app.string(FIELD_SUMMARY)?.let(LocalizedText::of) ?: LocalizedText.EMPTY,
            iconUrl = app.string(FIELD_ICON),
        )
    }

    /**
     * The `packageName` from the listing link: `https://f-droid.org/en/packages/<pkg>`.
     *
     * The language segment is nearly always there but not guaranteed, so what follows `packages/` is
     * taken instead of counting positions. A URL without that segment is not a usable result and is
     * discarded: better nine results than one leading to an empty page.
     */
    private fun packageNameFromUrl(url: String): String? {
        val segments = url.toHttpUrlOrNull()?.pathSegments ?: return null
        val index = segments.indexOf(PATH_PACKAGES)
        if (index < 0 || index + 1 >= segments.size) return null
        return segments[index + 1].takeIf { it.isNotBlank() }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }

    companion object {
        /** How many results it returns, always, whatever parameters are passed. */
        const val HARD_RESULT_CAP: Int = 10

        private const val PARAM_QUERY = "q"
        private const val FIELD_APPS = "apps"
        private const val FIELD_NAME = "name"
        private const val FIELD_SUMMARY = "summary"
        private const val FIELD_ICON = "icon"
        private const val FIELD_URL = "url"
        private const val PATH_PACKAGES = "packages"
        private const val SELECTOR_URL = "search_apps/url"
        private const val SELECTOR_ROOT = "search_apps/root"
        private const val SNIPPET = 200
    }
}

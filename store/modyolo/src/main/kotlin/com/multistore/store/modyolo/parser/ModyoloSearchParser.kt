package com.multistore.store.modyolo.parser

import com.multistore.core.model.LocalizedText
import com.multistore.core.model.StoreId
import com.multistore.core.model.StoreListingSummary
import com.multistore.store.api.PagedResult
import com.multistore.store.api.StoreResult
import com.multistore.store.common.StoreErrors
import com.multistore.store.modyolo.ModyoloConfig
import com.multistore.store.modyolo.ModyoloRefs
import kotlinx.serialization.SerializationException

/**
 * The results of `/wp-json/wp/v2/posts?search=`.
 *
 * ### WordPress relevance is not relevance
 *
 * The search is full-text over title **and body**, and the resulting score is poor: searching
 * "telegram" puts "Pixly 3D — Icon Pack" second and "Video Compressor & Converter" fourth, neither
 * of which has the word in its title at all. They are posts that mention it in the description.
 *
 * Reordering here is therefore part of the parser and not a flourish: whoever has the term in the
 * title comes first, and ties keep WordPress's order. Nothing is discarded — an app mentioning the
 * query in its body may be exactly the one wanted — but with nine stores merged into a single list,
 * an off-topic result in second place pushes another store's right result down.
 *
 * ### The page past the last one is a 400, and not a fault
 *
 * `?page=99` answers **400** with `rest_post_invalid_page_number`. It is the only store that does
 * so: the others return an empty page. Treating it as an error would open a healthy store's circuit
 * breaker every time someone scrolls to the end — and the contract test scrolls to page 9999. The
 * translation into "empty page" lives in the adapter, where the HTTP code is known.
 */
internal class ModyoloSearchParser(private val config: ModyoloConfig) {

    fun parse(
        body: String,
        page: Int,
        query: String,
    ): StoreResult<PagedResult<StoreListingSummary>> {
        val posts = try {
            ModyoloJson.DECODER.decodeFromString<List<WpPost>>(body)
        } catch (e: SerializationException) {
            return StoreResult.Failure(
                StoreErrors.parseFailure("wp/v2/posts (schema JSON)", e.message.orEmpty()),
            )
        }

        val items = posts.mapNotNull(::summaryOf)
        if (posts.isNotEmpty() && items.isEmpty()) {
            // Same distinction `mapRowsOrFail` makes for HTML: rows that are there and none
            // readable is a changed schema, not a search without results.
            return StoreResult.Failure(
                StoreErrors.parseFailure("wp/v2/posts (id + slug)", body.take(SNIPPET_CHARS)),
            )
        }

        return StoreResult.Success(
            PagedResult(
                items = rank(items, query),
                page = page,
                // A full page, like the other adapters. WordPress would also send
                // `X-WP-TotalPages`, which is a true count — but reading it would mean carrying the
                // response headers this far, and in exchange it would save one empty request only
                // when the total is an exact multiple of twenty. That empty request is moreover a
                // 400 the adapter already translates.
                hasMore = items.size >= config.pageSize,
            ),
        )
    }

    /** Title matches first, the rest after, original order preserved within each group. */
    private fun rank(items: List<StoreListingSummary>, query: String): List<StoreListingSummary> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return items
        return items.sortedBy { if (it.title.lowercase().contains(needle)) 0 else 1 }
    }

    private fun summaryOf(post: WpPost): StoreListingSummary? {
        val ref = ModyoloRefs.refOf(post.slug, post.id) ?: return null
        val title = Html.text(post.title.rendered).takeIf { it.isNotBlank() } ?: return null

        return StoreListingSummary(
            storeId = StoreId.MODYOLO,
            ref = ref,
            title = title,
            // modyolo publishes the package **only** in the listing, deduced from the Google Play
            // link. It is not in the search results in any form.
            packageName = null,
            summary = LocalizedText.of(Html.text(post.excerpt.rendered).takeIf { it.isNotBlank() }),
            // The publisher is in the listing; here `developer` is a taxonomy id, which would cost
            // a request to resolve.
            developer = null,
            iconUrl = post.embedded?.featuredMedia?.firstOrNull()?.sourceUrl,
        )
    }

    private companion object {
        const val SNIPPET_CHARS = 512
    }
}

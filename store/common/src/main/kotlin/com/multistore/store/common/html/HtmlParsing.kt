package com.multistore.store.common.html

import com.multistore.store.api.StoreError
import com.multistore.store.api.StoreResult
import com.multistore.store.common.StoreErrors
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

/**
 * Reading an HTML page without silently producing empty fields.
 *
 * When markup changes, a failed parse must produce a declared parse failure with the selector's
 * name — never a disguised `NullPointerException` or a silently empty field. With bare Jsoup the
 * default is exactly what that rule forbids: `selectFirst()` returns `null` and `text()` the empty
 * string, so a selector that stops matching produces a listing with an empty title — and nobody
 * notices until a user looks at the screen.
 *
 * Here the distinction between "a field that may be missing" and "a field without which the page
 * makes no sense" is **in the method name**: [one] and [text] fail, [oneOrNull] and [textOrNull]
 * do not. Choosing becomes an explicit act, and the wrong choice is visible on the line.
 *
 * ### Why an exception, in a project where errors are values
 *
 * The contract towards the core is unchanged: no exception leaves a `StoreAdapter` method.
 * [ParseFailureException] never crosses that boundary — [parseHtml] catches it and translates it.
 * The benefit is entirely in the parser body: extracting twenty fields while returning an
 * either-type on every line would make unreadable the code that must stay readable, because it is
 * what gets reopened when a store changes its markup.
 */
class HtmlPage internal constructor(
    /**
     * The node selectors are relative to: the document, or one result row.
     *
     * `internal` on purpose. An adapter able to reach Jsoup's `Element` would again have
     * `selectFirst()` and `text()` available — the two methods returning `null` and the empty
     * string — and the rule about silently empty fields would go back to being a convention
     * instead of a fact of the classpath.
     */
    internal val element: Element,
) {

    /** The first matching node. Fails if there is none. */
    fun one(selector: String): HtmlPage =
        oneOrNull(selector) ?: fail(selector)

    fun oneOrNull(selector: String): HtmlPage? =
        element.selectFirst(selector)?.let(::HtmlPage)

    /** Every matching node, already wrapped: one search result at a time. */
    fun all(selector: String): List<HtmlPage> =
        element.select(selector).map(::HtmlPage)

    /** This node's text, cleaned up. Fails if it is empty. */
    fun text(): String = own().ifBlank { fail(SELF) }

    fun text(selector: String): String = one(selector).text()

    /** This node's text, or `null` if there is none. The variant that does not fail. */
    fun textOrNull(): String? = own().takeIf { it.isNotBlank() }

    fun textOrNull(selector: String): String? = oneOrNull(selector)?.own()?.takeIf { it.isNotBlank() }

    /**
     * The node's text **without** its children's.
     *
     * Needed more often than it looks: in `<span class="author">Someone <a>Category</a></span>`,
     * `text()` would return the two concatenated.
     */
    fun ownTextOrNull(): String? = element.ownText().trim().takeIf { it.isNotBlank() }

    /**
     * The text of the first matching node, **without** the subtrees in [excluding].
     *
     * Needed where the advert does not sit next to the content but **inside** it: apkmirror's
     * description is a notes block with an advertising block in the middle carrying the word
     * "Advertisement", an upsell and an ad slot, plus two "More/Less" links at the end. A normal
     * `text()` glues them onto the app's own text, and the result ends up in the description the
     * user reads beside those of the other eight stores.
     *
     * It works on a **copy** of the subtree: removing nodes from the real document would change
     * what later selectors find, and whoever reads a parser does not expect asking for a text to
     * modify the page.
     *
     * An exclusion matching nothing is not an error: the site may not have placed the advert on
     * that page, and on these sources that is a normal case.
     */
    fun textOrNull(selector: String, excluding: List<String>): String? {
        val node = element.selectFirst(selector) ?: return null
        val copy = node.clone()
        excluding.forEach { copy.select(it).remove() }
        return copy.text().trim().takeIf { it.isNotBlank() }
    }

    fun attr(selector: String, attribute: String): String =
        attrOrNull(selector, attribute) ?: fail("$selector[$attribute]")

    fun attrOrNull(selector: String, attribute: String): String? =
        oneOrNull(selector)?.element?.attr(attribute)?.trim()?.takeIf { it.isNotBlank() }

    fun ownAttrOrNull(attribute: String): String? =
        element.attr(attribute).trim().takeIf { it.isNotBlank() }

    /**
     * An absolute URL, resolved against the document's base URI.
     *
     * Stores write `href="/telegram/org.telegram.messenger/"` and the core must never compose a
     * URL by hand: it is the opaque-ref rule seen from the other side.
     */
    fun absUrl(selector: String, attribute: String): String =
        absUrlOrNull(selector, attribute) ?: fail("$selector[$attribute] (absolute)")

    fun absUrlOrNull(selector: String, attribute: String): String? =
        oneOrNull(selector)?.element?.absUrl(attribute)?.takeIf { it.isNotBlank() }

    fun ownAbsUrlOrNull(attribute: String): String? =
        element.absUrl(attribute).takeIf { it.isNotBlank() }

    /**
     * The **content** of a `<script>` or `<style>`, which is not node text.
     *
     * Jsoup keeps those elements' data in `data()` and not in `text()`, which for them returns the
     * empty string. Without this method an `application/ld+json` block read with [textOrNull] comes
     * out **absent** — not malformed, absent — and the listing is produced without the fields that
     * depend on it, silently. It is the silently empty field this class exists to prevent, reached
     * while believing it had been avoided.
     */
    fun dataOrNull(): String? = element.data().trim().takeIf { it.isNotBlank() }

    /**
     * The nearest matching ancestor, **this node included**.
     *
     * Needed when the datum is not inside the row but in the block containing it, and CSS
     * selectors can descend but not ascend. The case that required it is liteapks: on the file
     * page the version is sometimes on the row and sometimes only on the heading of the block
     * grouping several rows of the same version. Across 66 real rows: 22 carry it, 44 do not.
     *
     * The alternative would be selecting the blocks first and then the rows inside each — and on
     * that store one cannot, because the blocks have two different markup shapes depending on
     * whether there is one version or several.
     */
    fun closest(selector: String): HtmlPage? =
        element.closest(selector)?.let(::HtmlPage)

    /** `true` if the selector finds something. For decisions, not for extraction. */
    fun has(selector: String): Boolean = element.selectFirst(selector) != null

    private fun own(): String = element.text().trim()

    internal fun fail(selector: String): Nothing =
        throw ParseFailureException(selector = selector, snippet = snippet())

    /**
     * The fragment that goes into the diagnostic hash.
     *
     * The first [SNIPPET_CHARS] characters of the node the search failed in, not of the whole
     * page: two different listings from the same store must give different hashes, or "the markup
     * changed here" and "the markup changed everywhere" read alike. The text is never kept, only
     * its hash — there is no telemetry here and a page fragment can contain user data.
     */
    private fun snippet(): String = element.outerHtml().take(SNIPPET_CHARS)

    companion object {
        const val SNIPPET_CHARS: Int = 512
        private const val SELF = "(node text)"

        fun of(html: String, baseUrl: String): HtmlPage =
            HtmlPage(Jsoup.parse(html, baseUrl))

        /**
         * The same, for an **XML** document — an RSS feed.
         *
         * Not a convenience: with the HTML parser a feed reads **wrong, silently**, and it is a
         * case of the same family as [dataOrNull]. In HTML `<link>` is an **empty** element, so
         * `<link>https://…</link>` has no content: the URL becomes a *sibling* text node, and
         * `item > link` reads the **empty string**. Measured with Jsoup 1.23.1 on one `<item>`:
         *
         * ```
         * HTML parser  item > link  -> []
         * XML parser   item > link  -> [https://apkcombo.com/telegram/org.telegram.messenger/]
         * ```
         *
         * Each entry's link is the field the listing's ref is derived from: without it a
         * hundred-entry feed produces a hundred entries with no destination — i.e. no entries — and
         * the hunt for the fault starts in the wrong place, because the selector *looks* right.
         *
         * The XML parser also preserves the case of names (`pubDate`), and Jsoup's selectors stay
         * case-insensitive: both forms find the node.
         */
        fun ofXml(xml: String, baseUrl: String): HtmlPage =
            HtmlPage(Jsoup.parse(xml, baseUrl, Parser.xmlParser()))
    }
}

/**
 * Declares that [selector] did not produce what was needed.
 *
 * [HtmlPage]'s methods fail when a selector **finds nothing**. There is also the case where it
 * finds something and that something is unusable, and then the parser decides: on liteapks the
 * `application/ld+json` block is always there — there are two — but only one is of the right type,
 * and an attribute is always there but must decode to an https URL.
 *
 * Returning an empty field in those two cases would be exactly the "silently empty field"
 * [HtmlPage] exists to prevent; the selector instead travels intact to the diagnostic log and says
 * what to rewrite.
 */
fun HtmlPage.parseFailed(selector: String): Nothing = fail(selector)

/**
 * Transforms a list's rows, and tells **"there was nothing"** from **"I could not read it"**.
 *
 * A bare `mapNotNull` conflates the two, and that is the defect this function exists to close.
 * Every result row is read with the tolerant methods — one malformed card out of thirty-six must
 * not make the other thirty-five vanish — but when the rows are there and **none** produces
 * anything, tolerance stops being prudence and becomes silence: the search answers "no results"
 * while in fact the selector is dead.
 *
 * ### How you get there, measured
 *
 * Seen on the emulator while testing the remote configuration channel: a signed document was
 * published with one store's search-link selector deliberately wrong, and a search for "telegram"
 * returned the other stores' results and **nothing** from that one — no error, no degraded store,
 * no diagnostic row. Exactly what a typo in a document published to repair something else would
 * produce, and nothing in the whole app would have said so.
 *
 * The distinction is sharp and has no grey zone: an **empty** list is a search with no results and
 * stays that; a list **full of unreadable rows** is a parse failure, which trips the circuit
 * breaker and reaches diagnostics with the name of the selector to rewrite.
 */
fun <T : Any> List<HtmlPage>.mapRowsOrFail(
    selector: String,
    transform: (HtmlPage) -> T?,
): List<T> {
    val mapped = mapNotNull(transform)
    if (isNotEmpty() && mapped.isEmpty()) first().fail(selector)
    return mapped
}

/**
 * The selector did not find what it needed.
 *
 * It never leaves an adapter: [parseHtml] catches it. See the note atop [HtmlPage].
 */
class ParseFailureException(
    val selector: String,
    val snippet: String,
) : RuntimeException("Selector with no match: $selector")

/**
 * Runs [block] over a page, translating a broken selector into a declared parse failure.
 *
 * The failing selector travels intact to the diagnostic log: when a store changes markup, the
 * diagnosis says **which** selector to rewrite, not "the adapter stopped working".
 */
inline fun <T> parseHtml(
    html: String,
    baseUrl: String,
    block: (HtmlPage) -> T,
): StoreResult<T> = try {
    StoreResult.Success(block(HtmlPage.of(html, baseUrl)))
} catch (e: ParseFailureException) {
    StoreResult.Failure(StoreErrors.parseFailure(e.selector, e.snippet))
}

/**
 * Like [parseHtml], but over an XML document: an RSS feed.
 *
 * The whole difference is which parser builds the tree — see [HtmlPage.ofXml] — and the error
 * translation stays identical, because a feed changing shape is a selector finding nothing, just
 * as a page is.
 */
inline fun <T> parseXml(
    xml: String,
    baseUrl: String,
    block: (HtmlPage) -> T,
): StoreResult<T> = try {
    StoreResult.Success(block(HtmlPage.ofXml(xml, baseUrl)))
} catch (e: ParseFailureException) {
    StoreResult.Failure(StoreErrors.parseFailure(e.selector, e.snippet))
}

/** Like [parseHtml], but for a parser that can decide for itself that the page is not there. */
inline fun <T : Any> parseHtmlOrNotFound(
    html: String,
    baseUrl: String,
    block: (HtmlPage) -> T?,
): StoreResult<T> = try {
    block(HtmlPage.of(html, baseUrl))
        ?.let { StoreResult.Success(it) }
        ?: StoreResult.Failure(StoreError.NotFound)
} catch (e: ParseFailureException) {
    StoreResult.Failure(StoreErrors.parseFailure(e.selector, e.snippet))
}

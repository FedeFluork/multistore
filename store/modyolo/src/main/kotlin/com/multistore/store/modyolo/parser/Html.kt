package com.multistore.store.modyolo.parser

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser

/**
 * The text inside an HTML fragment WordPress delivers inside a JSON field.
 *
 * Needed because modyolo sends plain text nowhere: `title.rendered` is `Minecraft` but also
 * `Video Compressor &#038; Converter`, and `excerpt.rendered` is a whole `<p>` with `<a>` inside.
 * Showing those values as they are would put HTML entities in the search results — and in "My
 * apps", where the titles end up.
 *
 * `Parser.unescapeEntities` alone is not enough (it would leave the tags) and stripping tags with
 * a regex is not enough either (it would leave the entities): both steps are needed, and Jsoup is
 * already on every adapter's classpath.
 */
internal object Html {

    fun text(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        if (!raw.contains('<') && !raw.contains('&')) return raw.trim()
        return Jsoup.parse(raw, "", Parser.htmlParser()).let(::visibleText)
    }

    private fun visibleText(document: Document): String =
        document.text().replace(NBSP, ' ').trim()

    /** The non-breaking space: WordPress produces it from `&nbsp;`, and it survives in a `String`. */
    private const val NBSP = '\u00A0'
}

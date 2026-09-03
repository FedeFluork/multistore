package com.multistore.store.apkcombo

/**
 * Whether the feed's **own marker** is still being stripped from the titles — decided by how many
 * rows share one bracketed token, and not by whether a bracket is present.
 *
 * ### Why this is a function with an offline test, and not four lines inside the canary
 *
 * Because the canary cannot exercise it. On a healthy day the live feed has no surviving marker and
 * no app whose name opens with a bracket, so **both** of the cases this logic exists to tell apart
 * are absent from every green run — a check that only its own failure could exercise is a check
 * nobody has ever seen work. It is the same reason
 * [com.multistore.store.uptodown.uptodownNotFoundMessage] is a function next door: by the time the
 * network produces the case, whoever reads the result has already been sent somewhere.
 *
 * ### What the old check got wrong, measured
 *
 * The line was `titles.none { it.startsWith("[") }`, and on 01/09/2026 it reddened the nightly on
 * one entry out of ninety-six: `[Official] Atomy shop`. The feed had published
 * `[apk_updated] [Official] Atomy shop`;
 * [ApkComboFeedParser][com.multistore.store.apkcombo.parser.ApkComboFeedParser] stripped the marker
 * exactly as it should have, and what stayed behind was **the app's own name**. It is real, and
 * neither of the two surfaces that prove it passes through the stripping at all: the listing page
 * calls it `[Official] Atomy shop`, search calls it `[Official] Atomy shop`, and that publisher
 * ships four of them — `[Official] Atomy Mobile`, `[Official] CH.ATOMY`, `[Official] Atomy Ticket`.
 *
 * So the check forbade a shape apkcombo legitimately publishes. Worse, it forbade it
 * **intermittently**: the feed is a window that rotates, so the identical line was green the night
 * before and green again on 02 and 03/09 once that entry had scrolled out. A red nobody can
 * reproduce is the worst thing a canary can emit, because it is precisely what teaches people to
 * ignore red.
 *
 * ### The property that separates the two worlds
 *
 * **Breadth, not brackets.** A surviving marker is the *same* token leading *every* row, because
 * that is what a marker is for — it is how apkcombo labels the species of entry inside one format.
 * An app whose real name opens with a bracket is a handful of rows carrying a token of its own.
 * Measured 03/09/2026 through the adapter: `[apk_updated]` on 96 raw titles out of 96 before
 * stripping, and zero bracketed titles after.
 *
 * Two things make that reading survive contact with a marker that is not a fixed string, and both
 * were found by mutating this file rather than by thinking about it:
 *
 *  - **rows are grouped by [markerKey], not by the token verbatim.** A marker with a payload that
 *    varies per row — `[apk_updated 3.1.4]`, or a date — defeats `FEED_PREFIX` (which cannot match
 *    a digit or a space) and therefore survives on **every** row, yet no two rows would share a
 *    token. Grouped verbatim the commonest count is 1 and the check stays green, which is strictly
 *    weaker than the line it replaced. The key is the leading word inside the brackets, so every
 *    payload of one marker collapses onto one group;
 *  - **the threshold has a floor as well as a share.** `titles.size / share` alone is unsafe on the
 *    short windows the canary accepts: `MIN_FEED_ITEMS` is 10, where a quarter is 2, and Atomy's
 *    four real names would reproduce the 01/09 false positive exactly. See [MARKER_FLOOR].
 *
 * [LEADING_BRACKET] is in turn deliberately **wider** than the parser's own `FEED_PREFIX`: the
 * failure being watched for is that regex ceasing to match, and a check written with the same
 * pattern would go on agreeing with it right up to the moment it stopped being true.
 *
 * ### The mirror failure, which this cannot see
 *
 * `FEED_PREFIX` is `IGNORE_CASE` over `[a-z_]+`, so it matches `[Official] ` as readily as
 * `[apk_updated] `. The only thing keeping `[Official] Atomy shop` intact today is that the feed's
 * own marker leads it, on 96 rows of 96 — a **measured fact about the feed, not a property of the
 * regex**. Were apkcombo ever to emit a title without the marker, the parser would amputate a real
 * name to `Atomy shop`, which no longer matches the listing's: the identity-matcher harm, from the
 * other side. Nothing bracketed would be left to count, so neither the old assertion nor this one
 * would notice. It is not introduced here and not fixed here; it is written down so the next reader
 * does not conclude the interaction is covered.
 */
internal data class FeedMarker(
    /** One of the tokens that survived, brackets included — an example for the message. */
    val token: String,
    /** The group they were counted under: see [markerKey]. */
    val key: String,
    /** How many titles the group leads. */
    val count: Int,
    /** Out of how many. Carried so the message can state the ratio and not just a number. */
    val of: Int,
)

/**
 * The bracketed token group leading more than [share]th of [titles] — and never fewer than
 * [floor] rows — or `null` when nothing is common enough to be a marker rather than a name.
 */
internal fun survivingFeedMarker(
    titles: List<String>,
    share: Int = MARKER_SHARE,
    floor: Int = MARKER_FLOOR,
): FeedMarker? {
    // Not a guard against anything: with no titles there are no groups, so the `?: return null`
    // below already answers. Removing this line changes no outcome — it is stated here because a
    // reader is entitled to know that "the feed is empty" is checked by `MIN_FEED_ITEMS` in the
    // canary, and answering it twice would give two lines about one fact.
    val commonest = titles
        .mapNotNull { LEADING_BRACKET.find(it)?.value }
        .groupBy { markerKey(it) }
        .maxByOrNull { it.value.size }
        ?: return null
    val threshold = maxOf(floor, titles.size / share)
    return FeedMarker(
        token = commonest.value.first(),
        key = commonest.key,
        count = commonest.value.size,
        of = titles.size,
    ).takeIf { commonest.value.size > threshold }
}

/**
 * What two bracketed tokens have to share to be counted as one marker: the leading word inside the
 * brackets, lowercased.
 *
 * `[apk_updated]`, `[apk_updated 3.1.4]` and `[APK_UPDATED beta]` are one group; `[Official]` and
 * `[Beta]` are two. A token with no leading word — `[2026-09-03]`, `[]` — keys to the empty string,
 * which groups every varying date together and is the point rather than an accident.
 *
 * **What carries the weight here is `takeWhile`, not `lowercase`.** Removing the case fold is an
 * injection that stays green, and correctly so: a marker is written by one template, so its case
 * does not vary between rows, and the two variants therefore agree on every input the feed can
 * produce. It is kept as cover for a marker emitted by two generators at once, and it is named here
 * as cover rather than as a defence so nobody goes looking for the test that proves it.
 */
private fun markerKey(token: String): String =
    token.trim('[', ']').takeWhile { it.isLetter() || it == '_' || it == '-' }.lowercase()

/**
 * A leading bracketed token, whatever is inside it — see the note above for why it must stay wider
 * than the parser's own prefix.
 */
private val LEADING_BRACKET = Regex("""^\[[^]]*]""")

/**
 * How much of one window a single token group may lead before it is a marker and not a name: a
 * quarter of it.
 *
 * A surviving marker leads **all** the rows, so on the ninety-six entries measured the share puts
 * the threshold at 24 against 96 — decisive, with no reading in between.
 */
internal const val MARKER_SHARE = 4

/**
 * The fewest rows a group must lead to be a marker at all, whatever the share says: eight.
 *
 * The share alone is unsafe precisely where the canary is most permissive. `MIN_FEED_ITEMS` is 10
 * and a feed is a window whose width depends on how much the store published that day, so a thin
 * night is legal — and at ten entries a quarter is two, which Atomy's **four** real
 * `[Official] …` names clear on their own. Without this floor the fix would reproduce the very
 * false positive it was written for, and the message would tell the reader to widen a regex that
 * had not moved.
 *
 * Eight, because four is the largest real cluster measured from one publisher and a surviving
 * marker is on essentially every row: at the narrowest legal window ten-of-ten still clears eight,
 * so the floor costs nothing against the failure and buys a wide margin against the name.
 */
internal const val MARKER_FLOOR = 8

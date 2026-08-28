package com.multistore.store.modyolo.parser

import com.multistore.core.model.ContentKind
import com.multistore.core.model.LocalizedText
import com.multistore.core.model.Screenshot
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.model.StoreListingDetail
import com.multistore.core.model.StoreListingSummary
import com.multistore.store.api.StoreError
import com.multistore.store.api.StoreResult
import com.multistore.store.common.StoreErrors
import com.multistore.store.common.html.TextValues
import kotlinx.serialization.SerializationException

/**
 * The listing, from the theme's post endpoint.
 *
 * ### The `packageName` is there, and comes from an unexpected place
 *
 * modyolo was first assumed to publish none. In fact the original-download field is the Google Play
 * link of the **original** app, and its query carries the package.
 *
 * That the Play page's package is also the modified APK's **is not obvious** — a repackaged build
 * could change it, and that is the kind of error the hard block at step 4 of the pre-install
 * pipeline would turn into "you can no longer install anything from this store". So it was measured
 * rather than assumed: eight APKs downloaded and read with `aapt2 dump packagename`, three of them
 * marked as modified, **eight matches out of eight**.
 *
 * Worth saying why populating it is the prudent choice even on a sample of eight: if a package ever
 * failed to match, the outcome is that *that* installation is blocked — the safe way to be wrong.
 * Not populating it would instead give up the control on all the others.
 *
 * The capability stays `providesPackageName = false`, and that is not a contradiction: the Play
 * link only exists for apps that are on Play. The visual novels distributed via Patreon have a link
 * to Patreon, and other listings leave it empty.
 *
 * ### What is discarded, and why
 *
 * The downloads array, the type and the modification-features fields are **always empty** — 0 out
 * of 81 posts in one sample, 0 across everything sampled here. They are not fields we failed to
 * read: they are fields the theme declares and never fills.
 *
 * The version code **does not exist** in any form: not in the JSON, not in the HTML, not in the
 * CDN's file name — which here carries the *version name* with its dots replaced, not the code.
 * That is the difference from apkmody, where the file name carries it.
 */
internal class ModyoloDetailParser {

    fun parse(body: String, ref: StoreAppRef): StoreResult<StoreListingDetail> {
        val envelope = try {
            ModyoloJson.DECODER.decodeFromString<ThemeEnvelope>(body)
        } catch (e: SerializationException) {
            return StoreResult.Failure(
                StoreErrors.parseFailure("v1/posts (schema JSON)", e.message.orEmpty()),
            )
        }

        // HTTP 200 with a null payload is how modyolo says "does not exist". See [ThemeEnvelope].
        val post = envelope.data ?: return StoreResult.Failure(StoreError.NotFound)
        val title = Html.text(post.title.ifBlank { post.name.orEmpty() })
        if (title.isBlank()) {
            return StoreResult.Failure(
                StoreErrors.parseFailure("v1/posts.data.title", body.take(SNIPPET_CHARS)),
            )
        }

        val genre = Html.text(post.genre).takeIf { it.isNotBlank() }
        val summary = StoreListingSummary(
            storeId = StoreId.MODYOLO,
            ref = ref,
            title = title,
            packageName = packageNameOf(post.originalDownloadUrl),
            developer = Html.text(post.publisher).takeIf { it.isNotBlank() },
            iconUrl = post.images?.thumbnail ?: post.images?.image,
            categories = listOfNotNull(genre),
            // modyolo mixes apps and games under the same Play categories and has nowhere a field
            // saying which of the two something is. Pretending to know would produce a "games only"
            // filter that loses half the games.
            contentKind = ContentKind.UNKNOWN,
            latestVersionName = post.latestVersion?.trim()?.takeIf { it.isNotBlank() },
            // It does not exist in any form: see the note at the top of this class.
            latestVersionCode = null,
            // The rating is only in the page's HTML, inside a 120 KB structured-data block. Not
            // worth a second request, and the capability declares it absent.
            rating = null,
            lastUpdated = TextValues.utcDateTime(post.updatedAt),
        )

        return StoreResult.Success(
            StoreListingDetail(
                summary = summary,
                description = LocalizedText.of(
                    Html.text(post.content).takeIf { it.isNotBlank() },
                ),
                // The modification notes are the most useful thing this store publishes, and they
                // sit in two places saying the same thing two ways: one field is a single line,
                // the tab is a list. The tab is preferred where present, because it explains
                // *what* changes.
                whatsNew = LocalizedText.of(modNotes(post)),
                screenshots = screenshotsOf(post),
                versions = emptyList(),
            ),
        )
    }

    /**
     * The package from the Google Play link.
     *
     * The `id` parameter is read rather than the last segment: modyolo writes both the bare form
     * and one with extra parameters. The value must have the shape of a package name — an `id` that
     * does not means the link is not what we think, and in that case nothing is declared.
     */
    private fun packageNameOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        if (!url.startsWith(PLAY_PREFIX)) return null
        val id = runCatching { java.net.URI(url).rawQuery }.getOrNull()
            ?.split('&')
            ?.firstOrNull { it.startsWith("$PLAY_ID_PARAM=") }
            ?.substringAfter('=')
            ?: return null
        return id.takeIf { PACKAGE_NAME.matches(it) }
    }

    private fun modNotes(post: ThemePost): String? {
        val tab = post.tabs.firstOrNull { MOD_TAB.containsMatchIn(it.title) }
        val fromTab = Html.text(tab?.content).takeIf { it.isNotBlank() }
        return fromTab ?: Html.text(post.modInfo).takeIf { it.isNotBlank() }
    }

    /**
     * The listing's images: cover and banner, which **are not screenshots**.
     *
     * modyolo publishes no gallery. They are the post's illustration — often the same image at two
     * resolutions. They are served as screenshots because that is what they are to the user (the
     * only preview this store gives), but the capability declares it for what it is: one, at most
     * two, not a gallery.
     */
    private fun screenshotsOf(post: ThemePost): List<Screenshot> =
        listOfNotNull(post.images?.image, post.banner)
            .filter { it.isNotBlank() }
            .distinct()
            .map(::Screenshot)

    private companion object {
        const val SNIPPET_CHARS = 512
        const val PLAY_PREFIX = "https://play.google.com/store/apps/details"
        const val PLAY_ID_PARAM = "id"

        /** The five observed spellings of the modification tab's heading, plus its absence. */
        val MOD_TAB = Regex("""mod\s*info""", RegexOption.IGNORE_CASE)
        val PACKAGE_NAME = Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+""")
    }
}

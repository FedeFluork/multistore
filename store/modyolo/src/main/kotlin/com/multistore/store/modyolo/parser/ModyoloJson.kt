package com.multistore.store.modyolo.parser

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * modyolo's two JSON responses, as types.
 *
 * `ignoreUnknownKeys` is not convenience: the WordPress REST API returns thirty fields per post —
 * `yoast_head`, `guid`, `ping_status`, `acf` — and a strict decoder would break on the first plugin
 * that adds one. `isLenient` on the other hand is **not** on: a number written as a string would be
 * a schema change, and we want to know.
 */
internal object ModyoloJson {
    val DECODER: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
}

/** One result from `/wp-json/wp/v2/posts`. */
@Serializable
internal data class WpPost(
    val id: Int = 0,
    val slug: String = "",
    val link: String = "",
    val date: String? = null,
    val title: WpRendered = WpRendered(),
    val excerpt: WpRendered = WpRendered(),
    val categories: List<Int> = emptyList(),
    @SerialName("_embedded") val embedded: WpEmbedded? = null,
)

@Serializable
internal data class WpRendered(val rendered: String = "")

@Serializable
internal data class WpEmbedded(
    @SerialName("wp:featuredmedia") val featuredMedia: List<WpMedia> = emptyList(),
)

@Serializable
internal data class WpMedia(@SerialName("source_url") val sourceUrl: String? = null)

/**
 * The theme endpoint's envelope: `{"status":"200","message":"…","data":{…}}`.
 *
 * **`data` can be `null` with HTTP 200.** That is how modyolo says "this post does not exist":
 * `/wp-json/v1/posts/999999999` answers `200 {"status":200,…,"data":null}`. An adapter trusting the
 * HTTP code would return an empty listing instead of `NotFound`, and the UI would show a nameless
 * app.
 *
 * `status` arrives both as a string (`"200"`) and as a number depending on the endpoint, which is
 * why it **is not read**: the only thing that decides is whether `data` is there.
 */
@Serializable
internal data class ThemeEnvelope(val data: ThemePost? = null)

/** The listing proper. The field names are modyolo's, typos included. */
@Serializable
internal data class ThemePost(
    // `id` is **deliberately not declared**: the theme endpoint returns it as a number
    // (`"id": 19`) while `status` in the same envelope is sometimes `200` and sometimes `"200"`.
    // We do not need it — whoever made the request already knows the ref — and typing a field
    // whose type the store does not hold still means breaking on the first post that changes it.
    val title: String = "",
    val name: String? = null,
    val content: String? = null,
    val images: ThemeImages? = null,
    val banner: String? = null,
    val publisher: String? = null,
    val genre: String? = null,
    val size: String? = null,
    @SerialName("mod_info") val modInfo: String? = null,
    /** The typo is in their schema: `lastest_version`, not `latest_version`. */
    @SerialName("lastest_version") val latestVersion: String? = null,
    @SerialName("original_download_url") val originalDownloadUrl: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val tabs: List<ThemeTab> = emptyList(),
)

@Serializable
internal data class ThemeImages(val thumbnail: String? = null, val image: String? = null)

@Serializable
internal data class ThemeTab(val title: String = "", val content: String = "")

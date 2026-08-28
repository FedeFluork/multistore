package com.multistore.store.common.html

import com.multistore.core.model.ArtifactType
import java.net.URI
import java.net.URLDecoder

/**
 * The few URL operations every scraping adapter repeats.
 *
 * None of them builds a URL from scratch: that remains the adapter's job, as the only one that
 * knows its store's grammar. Here what the page has already written is **read**.
 */
object Urls {

    /**
     * The non-empty segments of the path.
     *
     * Needed to read identity where the store puts it in the URL rather than in the markup:
     * apkcombo writes `/telegram/org.telegram.messenger/` and the second segment **is** the
     * `packageName`; apkmirror writes `/apk/{developer}/{app}/` and the number of segments tells
     * an app's listing from a release's.
     */
    fun segments(url: String): List<String> = runCatching {
        URI(url).path.orEmpty().split('/').filter { it.isNotBlank() }
    }.getOrElse { emptyList() }

    /**
     * A query parameter's value, already decoded.
     *
     * apkcombo puts the file's signed URL inside `?u=` and apkmirror the real image inside a
     * resize endpoint's `src=`: in both cases what is needed is **inside** the query, not in the
     * URL carrying it. Following apkcombo's redirect would work too, but would cost an extra hop on
     * their servers for a value already in hand.
     */
    fun queryParam(url: String, name: String): String? {
        val query = runCatching { URI(url).rawQuery }.getOrNull() ?: return null
        for (pair in query.split('&')) {
            val separator = pair.indexOf('=')
            if (separator <= 0) continue
            if (pair.substring(0, separator) != name) continue
            return runCatching { URLDecoder.decode(pair.substring(separator + 1), Charsets.UTF_8) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
        }
        return null
    }

    /** `true` if the URL is absolute and HTTPS. The contract test demands it on every served link. */
    fun isHttps(url: String): Boolean = url.startsWith("https://", ignoreCase = true)

    /**
     * Like [isHttps], but loopback is also accepted.
     *
     * The exception serves one purpose, the same one the contract test encodes for itself: letting
     * a test double answer in the clear. It concerns **only** the adapters that, having resolved a
     * URL, actually query it — an1 and modyolo, which do a `HEAD` on the file. For the others the
     * file is never reached in a test, and [isHttps] is enough.
     *
     * It is not exploitable by accident: none of the nine stores lives on `localhost`, and a
     * loopback URL arriving from a real page would already be something to look at. Without this
     * exception the double would have to stand up a TLS server with a self-signed certificate to
     * prove things that have nothing to do with TLS.
     */
    fun isSecureOrLoopback(url: String): Boolean {
        if (isHttps(url)) return true
        val host = runCatching { URI(url).host }.getOrNull()?.lowercase() ?: return false
        return host in LOOPBACK_HOSTS
    }

    /**
     * The last segment as a file name, stripped of query and path characters.
     *
     * The name ends up in the staging directory: a segment with `..` or `/` would write outside it.
     * Not theory — the store writes that name, not us.
     */
    fun fileNameOf(url: String, fallback: String): String {
        val raw = runCatching { URI(url).path.orEmpty().substringAfterLast('/') }.getOrNull()
        val decoded = raw?.let { runCatching { URLDecoder.decode(it, Charsets.UTF_8) }.getOrNull() }
        val cleaned = decoded.orEmpty().replace(UNSAFE_IN_NAME, "_").trim('.', ' ')
        return cleaned.takeIf { it.isNotBlank() && it.length <= MAX_NAME } ?: fallback
    }

    /**
     * The artifact type inferred from the file name's suffix.
     *
     * It has to be read rather than assumed: a split container handed to `PackageInstaller` as
     * though it were an APK fails with an error that does not name the cause. The default is `APK`
     * because that is what a file with no recognisable suffix almost always is — but the file
     * decides, not the adapter.
     */
    fun artifactTypeOf(fileName: String): ArtifactType =
        when (fileName.substringAfterLast('.', "").lowercase()) {
            "xapk" -> ArtifactType.XAPK
            "apkm" -> ArtifactType.APKM
            "apks" -> ArtifactType.APKS
            else -> ArtifactType.APK
        }

    /**
     * **Conditional** percent-encoding of a file URL's path.
     *
     * Two stores mix the two forms on the same CDN, and the mixture is historical in both: older
     * entries arrive already encoded (`/The%20Walking%20Zombie/…`), newer ones with raw spaces
     * (`/Bloons TD 6/…`). Encoding **unconditionally** would turn `%20` into `%2520` and the file
     * would no longer exist; not encoding at all produces a URL OkHttp rejects.
     *
     * **Measured on modyolo:** across forty binaries of the oldest layer, without this
     * normalisation twenty-eight came out unreachable — and looked dead. With it, the genuinely
     * dead ones are eleven. The defect would have lied in the worst direction: it would have made a
     * store that works three quarters of the time look useless.
     *
     * **Re-verified on liteapks**, which is why the function moved up here from that adapter: the
     * base64 links on its download pages contain both forms, and its worker answers **404
     * `NoSuchKey`** to a `+` in place of a space. Brackets, on the other hand, pass raw: they are
     * in [SAFE] and stay there, because re-encoding them would be a second way of changing the key.
     */
    fun normalizeFileUrl(url: String): String {
        val builder = StringBuilder(url.length)
        var index = 0
        while (index < url.length) {
            val char = url[index]
            if (char == '%' && ESCAPE.matches(url.substring(index, minOf(index + ESCAPE_LENGTH, url.length)))) {
                builder.append(url, index, index + ESCAPE_LENGTH)
                index += ESCAPE_LENGTH
                continue
            }
            if (char in SAFE) {
                builder.append(char)
            } else {
                char.toString().toByteArray().forEach { builder.append("%%%02X".format(it)) }
            }
            index++
        }
        return builder.toString()
    }

    private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "[::1]", "::1")

    private val UNSAFE_IN_NAME = Regex("""[/\\:*?"<>|\x00-\x1f]""")
    private const val MAX_NAME = 180

    private const val ESCAPE_LENGTH = 3
    private val ESCAPE = Regex("""%[0-9A-Fa-f]{2}""")

    /** The characters that may stay as they are in a URI. */
    private val SAFE: Set<Char> =
        (('A'..'Z') + ('a'..'z') + ('0'..'9') + "-._~:/?#[]@!$&'()*+,;=".toList()).toSet()
}
